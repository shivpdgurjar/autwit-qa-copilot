# AutWit Copilot

A manual-QA copilot. A tester narrates an exploratory session in a chat UI; at each
interesting point the system captures database snapshots, API responses, Dynamo docs
and event streams, then diffs them and reports inconsistencies.

Two deliverables live here:

| | |
|---|---|
| `autwit-copilot-api` | Spring Boot, Java 21. **The only writer to the `autwit` schema.** |
| `autwit-copilot-ui` | React 18 + TypeScript + Vite. Talks only to copilot-api. |

Two systems are **given** and out of scope: `autwit-ai-orchestrator` (built in a
separate repo, consumed via `docs/SKILL_CONTRACT.md`) and `autwit-core`.

## Read first

| Doc | What it is |
|---|---|
| `docs/BUILD_BRIEF.md` | The design. §2's invariants are non-negotiable. |
| `docs/SKILL_CONTRACT.md` | **v0.1.1, ratified.** The only shared surface with the orchestrator. |
| `docs/openapi.yaml` | copilot-api's API. The UI's client is generated from it — never hand-written. |
| `docs/ADR-001-reclaim-vs-reap.md` | Why the dequeue and the reaper have mirrored predicates. |
| `docs/CONTRACT_RATIFICATION_REQUEST.md` / `RATIFICATION_RESPONSE.md` | The five contract questions and their answers. |
| `docs/SCHEMA_VERIFICATION.md` | `V1__init.sql` verified against Postgres 16. |

## Prerequisites

- **Java 21+** — required (virtual threads).
- **Docker** — for Testcontainers and for a local Postgres. No H2: the schema uses
  jsonb, GIN, `SKIP LOCKED`, advisory locks and partial indexes, and H2 would lie.
- **Node 20+** — for the UI. Vite 6 will not run on older.
- Maven is **not** needed; `./mvnw` bootstraps it.

## Run it with Docker Compose

The whole stack — Postgres, the API, the UI — in one command:

```bash
docker compose up --build
```

- UI → <http://localhost:5173>
- API → <http://localhost:8080/api/v1>
- Postgres → `localhost:55432`

**The orchestrator is not in the compose file.** It lives in a different repository
whose path differs per machine, and it needs upstream APIs — the Event Store, the OMS —
that are not reachable from everywhere. Run it yourself and point the API at it:

```bash
ORCHESTRATOR_URL=http://host.docker.internal:9090 ORCHESTRATOR_TOKEN=… docker compose up
```

`host.docker.internal` is the default and means "the host from inside the container", so
an orchestrator running on your machine needs no override. Note the token: since contract
v0.1.6 the orchestrator **fails closed**, so leaving it unset yields `Bearer ` and a 401
that reads like a network fault. Either set it, or run the orchestrator with its explicit
`AGENTIC_SKILLS_ALLOW_UNAUTHENTICATED=true` dev opt-in.

With no orchestrator at all, replay the §10 fixtures instead:

```bash
SPRING_PROFILES_ACTIVE=all,fake docker compose up --build
```

The API container runs the `integration` profile by default, so
`autwit-copilot-api/logs/copilot-api.log` is bind-mounted into the working tree and can be
committed straight from there — see [`autwit-copilot-api/logs/README.md`](autwit-copilot-api/logs/README.md).
Drop to `SPRING_PROFILES_ACTIVE=all` for a quieter log without request and response bodies.

**Tests do not run in the image.** They start their own Testcontainers Postgres, which
would need a Docker socket mounted into the builder. Run them on the host with
`./mvnw test`.

## Run it directly

### 1. Postgres

```bash
docker run -d --name autwit-pg \
  -e POSTGRES_USER=autwit -e POSTGRES_PASSWORD=autwit -e POSTGRES_DB=autwit \
  -p 55432:5432 postgres:16-alpine
```

Flyway applies `V1__init.sql` on first boot.

### 2. The API, replaying fixtures

```bash
cd autwit-copilot-api
DB_URL=jdbc:postgresql://localhost:55432/autwit DB_USER=autwit DB_PASSWORD=autwit \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=all,fake
```

→ `http://localhost:8080/api/v1`

**`fake` is opt-in and must stay that way.** It replays `SKILL_CONTRACT` §10's fixtures
instead of calling a real orchestrator. It is not in the default profile set, because a
default that silently serves fixture data is the worst failure this product could have:
every capture would look real, and the whole point of the tool is that a tester can
trust what it shows them.

Against a real orchestrator, drop `fake` and set:

```bash
ORCHESTRATOR_URL=https://orchestrator.internal ORCHESTRATOR_TOKEN=… \
  ./mvnw spring-boot:run
```

Switching a database that has run under `fake` back to a real orchestrator leaves the
**skill catalog** holding fixture skills until the first successful sync replaces them.
That is the cache doing its job — `autwit.skill` is a projection that deliberately
survives an orchestrator outage rather than emptying the ⌘K palette — and `synced_at`
on `GET /skills` shows how stale it is. It does not leak into captured data: with the
real client and no orchestrator, a run lands `failed / upstream_unavailable` with zero
artifacts. Use a fresh database if you want a clean switch.

### 3. The UI

```bash
cd autwit-copilot-ui
npm install
npm run dev          # regenerates the API client first, then serves on :5173
```

Open `http://localhost:5173`, click **New session**, and type "I created order XXXX".

## Profiles

Profiles are BUILD_BRIEF §5's `--mode` flag. The worker always dequeues through
Postgres `SKIP LOCKED` even in `all`, so splitting it out later is a config change
rather than a rewrite.

| Profile | Effect |
|---|---|
| `all` | API + worker in one process. **The default.** |
| `api` | API only; something else must run the worker. |
| `worker` | Worker only. |
| `fake` | Replay fixtures instead of calling the orchestrator. Never in production. |

## Tests

```bash
cd autwit-copilot-api && ./mvnw test     # 177 tests; needs Docker
cd autwit-copilot-ui && npm run build    # typecheck + build
```

The API suite boots one Postgres container for the whole run. The tests in
BUILD_BRIEF §9 encode the invariants — `RunQueueTest`, `IdempotencyTest`,
`TimeoutTest`, `EventDedupeTest`, `DiffEngineTest`, `ArtifactIntegrityTest`,
`CascadeTest`. Without them the invariants are just comments.

## Things that will bite you

**The generated client is the contract.** `npm run dev` and `npm run build` regenerate
`src/api/generated/` from `docs/openapi.yaml`. It is gitignored on purpose — a
checked-in copy is a second source of truth. If the UI stops compiling after an API
change, that is the mechanism working. `SpecConformanceTest` guards the other
direction: the API must not emit fields the spec does not declare, because Jackson will
happily serialise any `isFoo()` helper you add to a DTO and the UI will never see it.

**`run.lease` must exceed `orchestrator.timeout`.** 12m vs 10m. `ConfigAssertions`
fails startup otherwise. If they were equal, a slow-but-alive run would be reclaimed
and re-executed while still running — with a mutating skill that places the order
twice.

**`timed_out` is not `failed`.** It means the outcome is UNKNOWN. Never auto-retried;
a human verifies first.

**Money keeps its scale.** `use-big-decimal-for-floats` is not optional: Jackson
otherwise parses `1200.00` to a double and renders `1200.0`, which breaks
`content_hash` against the orchestrator *and* silently destroys the value the financial
validation is checking. The diff engine compares with `BigDecimal.compareTo`, never
`equals`, for the same reason.

**Ignore rules are always surfaced.** If `updated_at` diffs vanish without explanation,
nobody trusts the report and the tool dies. That is a product requirement, not a
nicety — see `DiffViewer` and the report template.

## Known gaps

- **No authentication.** `openapi.yaml` declares `bearerAuth` on every endpoint; none
  is implemented. §10 defers only "auth *beyond* a bearer token", so the token itself
  is outstanding. Anything that can reach the port can read captured data and invoke
  `order.place`, which places a real order via a browser.
- **The orchestrator's `/skills` + `/invoke` surface does not exist yet.**
  `HttpOrchestratorClient` is built and tested against a stub replaying our fixtures;
  the integration is untested end to end.
- Deliberately not built (BUILD_BRIEF §10): pre-signed S3 URIs, a separate worker
  deployment, session export → runnable test spec, `artifact`/`event_record`
  partitioning.
