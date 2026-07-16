# AutWit Copilot — Build Brief

**Read this first, then `SKILL_CONTRACT.md`, then `openapi.yaml`.**

Two deliverables:

- `autwit-copilot-api` — Spring Boot, Java 21, the only writer to Postgres
- `autwit-copilot-ui` — React/TS, talks only to copilot-api

Two systems are **given** and out of scope. Do not build, mock beyond the
specified fake, or design them:

- `autwit-ai-orchestrator` — designed in a separate session. Consumed via `SKILL_CONTRACT.md`.
- `autwit-core` — capability services behind the orchestrator. Never contacted directly.

---

## 1. What this is

A manual-QA copilot. A tester runs an exploratory session in a chat UI, and at
each interesting point the system captures database snapshots, API responses,
Dynamo docs, and event streams — then diffs them and reports inconsistencies.

The canonical session:

```
1. start session
2. "I created order XXXX"      → milestone + snapshot (6 order tables + 3 shipment tables)
3. (snapshot capture is a skill, run by the orchestrator)
4. "order is ready for member" → milestone + snapshot + API response artifact
                                 + all events since step 2
5. "I fulfilled the order"     → milestone + snapshot
6. compare snapshots           → consistency + financial validation → findings
7. end session                 → renders an html/md report for download
   throughout: the system narrates its analysis in chat
```

---

## 2. Non-negotiable invariants

Violating any of these means a rewrite. They are the output of a long design
process; do not relitigate them mid-build.

| # | Invariant |
|---|---|
| 1 | **copilot-api is the sole writer to the `autwit` schema.** The orchestrator returns data; it never calls back. There are no `/internal/*` inbound endpoints. |
| 2 | **Everything that touches the orchestrator or takes >1s is a `run`.** Uniform. Even a local diff. |
| 3 | **Submit-only.** POST returns `202 {run_id, step_id}`. Never block a request handler on orchestrator work. |
| 4 | **Truth is `GET /sessions/{id}`.** SSE is a fire-and-forget hint. On any event, the UI refetches. A dropped notification must be harmless. |
| 5 | **`autwit.run` is the queue.** Postgres `SKIP LOCKED`. No Redis, no SQS. Enqueue and step-insert in one transaction. |
| 6 | **Runs are serialized per session** via `pg_try_advisory_lock(hashtext('autwit:session:'||id))`, held for the run's duration. Parallel across sessions, FIFO within one. |
| 7 | **10m client timeout, 12m lease, 60s reaper.** The lease must exceed the timeout. |
| 8 | **`timed_out` ≠ `failed`.** Timed out means outcome UNKNOWN. Never auto-retry. `max_attempts = 1` for anything mutating. |
| 9 | **`part_key` is stable across snapshots.** Comparison is a key-wise join on it. |
| 10 | **Ignore rules are always surfaced**, never applied silently. |
| 11 | **No JPA/Hibernate.** jOOQ or JdbcTemplate. The schema is document-and-diff shaped; an ORM is a tax with no return. |
| 12 | **Java 21+, virtual threads on.** `spring.threads.virtual.enabled=true`. Required for SSE + 10-minute blocking calls. |

---

## 3. Stack

**API**
```
Java 21, Spring Boot 3.3+
jOOQ (or JdbcTemplate) — NOT JPA
Flyway            V1__init.sql is provided, use verbatim
HikariCP          + one pinned connection outside the pool for LISTEN
Jackson           JSON;  JAXB / Woodstox for XML artifacts
Thymeleaf         report rendering
Testcontainers    Postgres. No H2 — the schema uses jsonb, GIN, SKIP LOCKED,
                  advisory locks, and partial indexes. H2 will lie to you.
```

**UI**
```
React 18 + TypeScript, Vite
TanStack Query    server state; the refetch-on-notify pattern IS the design
Tailwind
openapi-typescript  generate the client from openapi.yaml, wired into the build
```

Two languages, two repos. That's fine, but it means no shared types for free —
hence the generated client. Do not hand-write API types in the UI.

---

## 4. Module layout

```
autwit-copilot-api/
  src/main/java/com/autwit/copilot/
    session/      SessionService, StepService, MilestoneService
    artifact/     ArtifactService, ArtifactRepository, hashing, format dispatch
    snapshot/     SnapshotAssembler
    compare/      DiffEngine, FinancialRules, IgnoreRules
    events/       EventIngestService  (ON CONFLICT DO NOTHING)
    report/       ReportRenderer (Thymeleaf → html/md)
    orchestrator/ OrchestratorClient          <- interface (the port)
                  HttpOrchestratorClient      <- @Profile("!fake"), RestClient, 10m
                  FakeOrchestratorClient      <- @Profile("fake"), fixture replay
                  dto/                        <- contract types, incl. unused external_uri
    run/          RunRepository, RunEnqueuer, RunWorker (@Profile("worker","all")),
                  RunReaper (@Scheduled 60s)
    stream/       SseHub, PgNotificationListener
    registry/     SkillCatalogSync (@Scheduled 60s)
    web/          controllers, ProblemDetail handlers
  src/main/resources/
    db/migration/V1__init.sql
  src/test/resources/fixtures/orchestrator/*.json

autwit-copilot-ui/
  src/
    routes/sessions/[id].tsx
    components/
      chat/       Composer, MessageList, SkillPalette (⌘K), PendingRunCard
      timeline/   Timeline, MilestoneCard, SnapshotCard, EventBatchCard
      drawer/     ArtifactViewer, DiffViewer
      findings/   FindingsFeed, SeverityBadge
    hooks/        useSession, useSessionStream, useSubmitRun
    api/          generated/  <- openapi-typescript output, do not edit
```

`OrchestratorClient` as a port with `@Profile("fake")` is what lets everything
except step 8 be built and tested before the other session ships anything.

---

## 5. Process shape

```java
// --mode=api|worker|all   (default: all)
// Worker is in-process for now; separated later. That split must be a config
// change, not a rewrite — so the worker ALWAYS dequeues through Postgres
// SKIP LOCKED even in mode=all. No in-memory shortcut.
```

Worker concurrency: **4**. The advisory lock serializes per session anyway, so
this only buys cross-session parallelism.

**Graceful shutdown matters at 10 minutes:**
```yaml
server.shutdown: graceful
spring.lifecycle.timeout-per-shutdown-phase: 660s
```
On SIGTERM: stop dequeuing, let in-flight runs drain, exit. If the platform's
`terminationGracePeriodSeconds` is shorter than 660s, deploys will kill live
snapshots and they'll land as `timed_out`. Decide explicitly; don't discover it
during a release.

---

## 6. Request lifecycle (implement exactly this)

```
POST /sessions/{id}/messages
  ├─ tx: insert step(kind=user_utterance, actor=user, seq=next_step_seq(session))
  │      insert step(kind=skill_invocation, actor=agent, status=pending)
  │      insert run(status=queued, request={message, session_context})
  │      merge subjects into session.subjects
  ├─ NOTIFY autwit_run {type: run.queued}
  └─ 202 {run_id, step_id}        <- step_id known before execution → optimistic card

RunWorker loop:
  ├─ dequeue (SKIP LOCKED, reclaims expired leases)
  ├─ pg_try_advisory_lock(session)  → if false, release run back to queued, next
  ├─ NOTIFY {type: run.started}
  ├─ build session_context from DB  (subjects, milestones, event_cursors, recent_steps)
  ├─ POST orchestrator /invoke      (10m deadline on the RestClient)
  ├─ tx: persist envelope
  │      - verify content_hash on every artifact; reject on mismatch (catches truncation)
  │      - insert artifacts, resolve client_ref → artifact_id
  │      - insert snapshot + snapshot_part
  │      - insert events ON CONFLICT (session_id, dedupe_hash) DO NOTHING
  │      - insert findings
  │      - insert notes as step(kind=analysis)
  │      - merge subjects_discovered
  │      - update milestone.snapshot_id, .event_cursor, .status
  │      - update step.status, run.status=succeeded
  ├─ NOTIFY {type: run.succeeded}
  └─ pg_advisory_unlock(session)

GET /sessions/{id}/stream
  └─ SSE from SseHub, fed by PgNotificationListener. Thin events. No replay.
```

**On timeout:** worker cancels the HTTP call, marks `timed_out`, unlocks,
NOTIFYs. The reaper only catches workers that died outright.

**On late result:** if a run went terminal (cancelled/timed_out) and the
orchestrator's response arrives afterwards, discard it. Do not persist.

---

## 7. The diff engine

The core of the product. `compare/DiffEngine`.

```
input:  from_snapshot, to_snapshot, compare_type, rules
        join snapshot_part on part_key   <- both sides must have the same keys
        per part: artifact.meta.pk_columns drives the row-level key
output: comparison.part_results[] + finding[]
```

Per part:
1. Load both artifact bodies.
2. Key rows by `meta.pk_columns`. **If absent on an `rdbms_table`, that's a
   contract violation** — raise a `high` finding, mark the part inconclusive.
   Do not guess the key.
3. Apply `ignore_columns` (from `meta`, overridable by `rules`). Record which
   were applied into `part_results[].ignored_columns`.
4. Emit added / removed / modified / unchanged counts.
5. Emit a `finding` per modified field of interest.

`part_key` present on one side only → `high` finding. That's either a real bug
or scope drift, and both matter.

**`financial_validation`** is a rule set over the structural diff, not a
separate engine: tolerance on money fields, sum invariants (order total ==
Σ line items), and cross-source consistency (RDBMS order total == Dynamo doc
total == API response total). Put the rules in config, not in Java.

**Ignore rules are surfaced in the UI.** If `updated_at` diffs vanish without
explanation, nobody trusts the report and the tool dies. This is a product
requirement, not a nicety.

---

## 8. Build order

Only step 8 is blocked on the other session. That's the entire point of the
port + fixtures.

| # | Deliverable | Done when |
|---|---|---|
| 1 | Flyway `V1__init.sql`, Testcontainers harness | migration applies, container boots |
| 2 | Session/step/milestone/artifact CRUD + repositories | can POST an artifact, GET a timeline |
| 3 | `RunEnqueuer`, `RunWorker`, dequeue, lease, advisory lock, `RunReaper`, `FakeOrchestratorClient` | fixture run persists 9 artifacts + snapshot; killing a worker mid-run reclaims it |
| 4 | `PgNotificationListener` → `SseHub` → `/stream`; poll fallback | two tabs both see run.succeeded |
| 5 | UI: session route, timeline, pending cards, artifact drawer | fixture session renders end-to-end |
| 6 | Chat, composer, ⌘K palette (form-generated from `input_schema`) | can drive a fixture session by typing |
| 7 | `DiffEngine`, findings, `FinancialRules`, report render as a run | `/end` produces a downloadable html |
| 8 | Swap `FakeOrchestratorClient` → `HttpOrchestratorClient` | **blocked on the orchestrator session** |

Steps 1–5 contain zero AI and are most of the system. Do not invert this order.

---

## 9. Tests that must exist

These encode the invariants. Without them the invariants are just comments.

```
RunQueueTest
  - two workers, one queued run → executed exactly once
  - worker killed mid-run → lease expires → reclaimed → attempts=2
  - two runs same session → strictly serialized (advisory lock)
  - runs across sessions → parallel
  - lease_until > client timeout (assert the config, it's a real bug class)

IdempotencyTest
  - same Idempotency-Key twice → one run, same run_id returned
  - mutating skill never auto-retried (max_attempts stays 1)

TimeoutTest
  - invoke_slow fixture → run ends timed_out, NOT failed
  - timed_out is never auto-retried
  - late response after terminal → discarded, nothing persisted

ArtifactIntegrityTest
  - content_hash mismatch → rejected
  - body XOR external_uri enforced (one_body)
  - purge nulls the body, keeps the row, GET returns 410

EventDedupeTest
  - invoke_ready_for_member then invoke_events_dedupe
    → only new events land; overlap silently dropped

DiffEngineTest
  - identical snapshots → verdict pass, zero findings
  - part_key on one side only → high finding
  - missing pk_columns → inconclusive + high finding, no guessing
  - ignore_columns applied → reported in part_results.ignored_columns
  - financial: order total != Σ line items → critical finding

CascadeTest
  - DELETE session → every child table empty
```

---

## 10. Deliberately deferred

Don't build these. Don't design around them beyond what's noted.

- **Pre-signed S3 URIs.** Inline bodies only. But define
  `ArtifactDescriptor.external_uri` now and leave it unused (`SKILL_CONTRACT` §7).
  The DB constraint already permits it. Costs nothing today, avoids a contract
  renegotiation later.
- **Separate worker deployment.** In-process, `--mode` flag ready.
- **Session export → runnable test spec.** The highest-value future feature —
  it's what makes QA adopt this — but it needs a stable timeline first. The
  schema already supports it: every step has inputs, outputs, and correlation.
- **Partitioning** `artifact` / `event_record` by month.
- **Auth beyond a bearer token.**

---

## 11. Config

```yaml
autwit:
  orchestrator:
    base-url: ${ORCHESTRATOR_URL}
    token: ${ORCHESTRATOR_TOKEN}
    timeout: 10m            # hard. Must be < run.lease
    catalog-sync-interval: 60s
  run:
    lease: 12m              # MUST exceed orchestrator.timeout
    reaper-interval: 60s
    worker-concurrency: 4
    max-attempts: 1         # never raise this for mutating skills
  artifact:
    max-inline-bytes: 8388608     # 8MB — matches SKILL_CONTRACT §6.1
    max-response-bytes: 33554432  # 32MB
  session:
    default-ttl: 7d

spring:
  threads.virtual.enabled: true
  lifecycle.timeout-per-shutdown-phase: 660s
server:
  shutdown: graceful
```

Add a startup assertion: `run.lease > orchestrator.timeout`. Fail fast if
someone edits one without the other. That misconfiguration is an
order-placed-twice bug and it will not be obvious in review.

---

## 12. Open items — resolve with the orchestrator session, not by guessing

Listed in `SKILL_CONTRACT.md` §11. The two that block:

1. **Does `/invoke` honour `run_id` idempotency replay?** If not, copilot-api
   must never retry anything, and the `retryable` error flag is meaningless.
2. **Is `part_key` naming owned by the scope definition and guaranteed stable?**
   The diff engine breaks *silently* if it drifts. Get this in writing.

Everything else can be built against the fixtures.
