# Live integration test — financial analysis + DB-snapshot compare

Runbook for the **other laptop** (the one with access to the real upstreams: Event Store,
OMS Order Universal API, the compare DBs, and OpenAI). This laptop cannot reach those, so
the run happens there and is **diagnosed here from the committed log**
(`autwit-copilot-api/logs/copilot-api.log`). See `autwit-copilot-api/logs/README.md`.

Two flows are exercised:
- **A — DB-snapshot compare:** `snapshot.capture` (real DB dump) and `compare.cross_system`
  (Order DB + Shipment DB + DynamoDB PickPack reconciliation).
- **B — Financial analysis:** `api.fetch_order` (real order) → assemble evidence →
  `financial.analyze_snapshot`.

Both are proven here against the **fake** orchestrator; this is the real-upstream pass.

---

## 0. Before you start

- `git pull` both repos to the tip of `main`.
- Confirm the tip commit builds: `cd autwit-copilot-api && ./mvnw -B test` (needs Docker for
  Testcontainers). It should be green (212 tests).
- Docker Desktop running.

## 1. Bring up copilot-api + UI + Postgres

Compose brings up our three; the orchestrator is separate (step 2).

```bash
cd autwit-copilot            # repo root
# The API runs the integration profile by default in compose, so the wire log is on.
docker compose up --build
```

- UI → http://localhost:5173 · API → http://localhost:8080/api/v1 · Postgres → :55432
- The API reaches the orchestrator at `host.docker.internal:9090` by default. Override with
  `ORCHESTRATOR_URL=… ORCHESTRATOR_TOKEN=… docker compose up` if elsewhere.
- **The wire log is bind-mounted** to `autwit-copilot-api/logs/copilot-api.log` in the
  working tree — that is the file to commit for diagnosis.

## 2. Bring up the orchestrator in **command (real) mode**

In the `autwit-ai-orchestration/orchestration-service` repo, on this laptop:

```bash
npm install         # if not already; the financial build needs the openai dep
PORT=9090 <real-upstream env> npm start
```

**The env is the whole point of this laptop.** `src/config.ts` is the source of truth for
exact names; the categories that MUST be set to real values / command mode:

| Capability | Set (see their `config.ts`) |
|---|---|
| Event Store | `AGENTIC_ORDER_EVENTS_MODE=command`, `AGENTIC_EVENT_CONTENTS_MODE=command`, the `*_URL_TEMPLATE`s |
| OMS Universal API (`api.fetch_order`) | `AGENTIC_UNIVERSAL_ORDER_DETAILS_MODE=command`, its `*_URL_TEMPLATE` |
| Compare DBs (`compare.cross_system`) | `AGENTIC_COMPARE_*`: order/shipment Postgres host/db/user/password, `AGENTIC_COMPARE_DB_PORT`, DynamoDB `AGENTIC_COMPARE_AWS_REGION` + `AGENTIC_COMPARE_DYNAMO_TABLE`, and the compare mode = command |
| `snapshot.capture` real DB dump | the snapshot/compare command-mode flag (per their config) — real mode emits `orders.*`/`shipments.*`/`pickpack.*` part_keys |
| OpenAI (financial AI half) | `OPENAI_API_KEY` (absent → analysis still returns the **deterministic** verdict with `aiAnalysisStatus: UNAVAILABLE`), `OPENAI_FINANCIAL_MODEL`, `OPENAI_STORE_RESPONSES` |

**Auth (do this on purpose):** for the test, either
- run auth-off on both sides — orchestrator `AGENTIC_SKILLS_ALLOW_UNAUTHENTICATED=true`,
  copilot `ORCHESTRATOR_TOKEN` empty (the current default, C8/AUTH-1); or
- run auth-on — set `AGENTIC_SKILLS_AUTH_TOKEN=<t>` on the orchestrator and the **same**
  `ORCHESTRATOR_TOKEN=<t>` on copilot. An empty token against a fail-closed orchestrator is
  a 401 that reads like a network fault; the wire log prints `Bearer <EMPTY …>` so you can
  tell which it was.

Confirm reachability from inside the API container:
```bash
docker compose exec api sh -c "wget -qO- http://host.docker.internal:9090/healthz"   # {"status":"ok"}
```
The catalog should sync (7 skills, `v1/54395dc819e3`): `docker compose logs api | grep "Synced"`.

## 3. Flow A — DB-snapshot compare

Drive from the UI (⌘K palette → `compare.cross_system` / `snapshot.capture`, both render as a
single `order_id` field) or by API. API path:

```bash
API=http://localhost:8080/api/v1
SID=$(curl -s -XPOST $API/sessions -H 'Content-Type: application/json' \
  -d '{"tester_id":"you","env":"<real-env>","subjects":{"order_id":"<REAL_ORDER>"}}' | jq -r .session_id)

# Real DB snapshot dump
curl -s -XPOST $API/sessions/$SID/skills/snapshot.capture -H 'Content-Type: application/json' \
  -d '{"input":{"scope":"order_flow"}}'

# Cross-system compare
curl -s -XPOST $API/sessions/$SID/skills/compare.cross_system -H 'Content-Type: application/json' \
  -d '{"input":{"order_id":"<REAL_ORDER>"}}'
```

**Check:**
- Both runs reach `succeeded` (`GET $API/runs/{runId}`).
- `compare.cross_system` returns a `comparison` artifact (verdict `MATCH`/`MISMATCH`/`FAILED`
  in `output_inline.overall_status`), a `db_snapshot` (`logical_name: downloaded_data`) with
  the real rows, and any findings (category `cross_system_comparison`).
- Findings appear in the session's findings feed in the UI.
- `snapshot.capture` real mode: part_keys are `orders.orders`, `orders.order_lines`,
  `shipments.*`, `pickpack.qa3_pick_pack` (not the demo `oms.*`).

## 4. Flow B — Financial analysis

```bash
# 1) Fetch the real order (Universal API) → an api_response artifact
curl -s -XPOST $API/sessions/$SID/skills/api.fetch_order -H 'Content-Type: application/json' \
  -d '{"input":{"order_id":"<REAL_ORDER>"}}'

# 2) Find that artifact
curl -s "$API/sessions/$SID/artifacts" | jq '.artifacts[] | {artifact_id, artifact_type, logical_name}'

# 3) Assemble + analyse it (or do the whole thing from the UI's "Financial analysis" picker)
curl -s -XPOST $API/sessions/$SID/analyses -H 'Content-Type: application/json' \
  -d '{"analysis_mode":"SNAPSHOT_SANCTITY","order_number":"<REAL_ORDER>",
       "states":[{"kind":"ARTIFACT","id":"<api_response artifact_id>","state_type":"ORDER_SNAPSHOT","source":"ORDER_DB"}]}'
# → 202 with run_id

# 4) Poll the analysis run for the verdict
curl -s "$API/runs/<run_id>" | jq '{status, result_summary}'
```

**Check:**
- The analysis run reaches `succeeded`; `result_summary` has `overall_status`
  (PASS/PASS_WITH_WARNINGS/FAIL/NOT_VERIFIABLE), `ai_analysis_status`, `findings_total/fail`.
- With `OPENAI_API_KEY` set, `ai_analysis_status: OK`; without, `UNAVAILABLE` and the
  **deterministic verdict still stands**.
- The verdict + summary show in the UI picker's result view; findings in the feed.
- **The UI path is the real test** — the "Financial analysis" button → pick the api_response
  → Analyze this → watch the verdict.

## 5. Capture the log and hand it back

```bash
git -C autwit-copilot add autwit-copilot-api/logs/copilot-api.log
git -C autwit-copilot commit -m "Live integration log: financial + compare, order <REAL_ORDER>"
git -C autwit-copilot push
```

The log carries, PII-safely:
- the copilot↔orchestrator **skill** exchange (WireLog: request/response bodies, truncated),
- the **financial** exchange (metadata + verdict only — request bodies are NOT logged; they
  carry order PII, see PII-1),
- every artifact's `content_hash` (declared vs computed), and the run lifecycle.

## 6. Known caveats going in

- **PII-1** (`docs/KNOWN_ISSUES.md`): `api.fetch_order` persists raw order PII (member,
  card) into the artifact and `analysis_state.payload`. This is a **real capture of real
  PII** on that laptop's Postgres — treat the volume/DB accordingly and purge after. The log
  itself does not carry it.
- **FIX-2**: the copilot's own diff-feature financial reconciliation (`autwit.compare.financial`
  in `application.yml`) is keyed on the demo `oms.*` part_keys, so those *sum/cross-source
  invariants* silently no-op over a real `snapshot.capture`. Not a crash; just won't fire.
  Unrelated to the orchestrator's financial analysis (flow B), which is what's under test.
- **AUTH-1 / C8**: auth defaults off on both sides. Decide §2 before running.
- A `content_hash` mismatch on any artifact would print declared-vs-computed + the leading
  canonical bytes in the log — that is the null-retention / canonical-form check working.
