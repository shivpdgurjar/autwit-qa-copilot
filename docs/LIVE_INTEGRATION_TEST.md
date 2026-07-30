# Live integration test — financial analysis + DB-snapshot compare + order fulfilment

Runbook for the **other laptop** (the one with access to the real upstreams: Event Store,
OMS Order Universal API, the compare DBs, the fulfilment hosts, and OpenAI). This laptop
cannot reach those, so the run happens there and is **diagnosed here from the committed log**
(`autwit-copilot-api/logs/copilot-api.log`). See `autwit-copilot-api/logs/README.md`.

Flows exercised:
- **A — DB-snapshot compare:** `snapshot.capture` (real DB dump) and `compare.cross_system`
  (Order DB + Shipment DB + DynamoDB PickPack reconciliation).
- **B — Financial analysis (snapshot):** `api.fetch_order` (real order) → assemble evidence →
  `financial.analyze_snapshot`.
- **C — Order fulfilment (MUTATING):** `order.fulfil` (real pick → stage → invoice on a
  shipment-ready order), and optionally `order.place`. **These write.** New in v1.0.28.
- **D — Financial analysis (lifecycle):** assemble ≥2 states → `financial.analyze_lifecycle`.
  First real lifecycle run (the joint validation agreed since v1.0.23).
- **E — Follow-up chaining:** a chained analysis (`previous_response_id`) — exercises real-mode
  `responseId` population + stale-token degrade (our v1.0.24 confirm).
- **F — Planning Copilot:** the second flavor (`/plan` wizard) — Jira/Confluence search + fetch
  over the real aashari MCP, then `generate_test_plan` / `generate_test_data` on real OpenAI.
  New in v1.0.30; **never run live — the joint validation.**

All flows are proven here against the **fake** orchestrator; this is the real-upstream pass.

---

## 0. Before you start

- `git pull` both repos to the tip of `main`.
- Confirm the tip commit builds: `cd autwit-copilot-api && ./mvnw -B test` (needs Docker for
  Testcontainers). It should be green (**237 tests**).
- A container runtime running (Docker Desktop / Rancher Desktop).

## 1. Bring up copilot-api + UI + Postgres

Compose brings up our three; the orchestrator is separate (step 2).

```bash
cd autwit-copilot            # repo root
# The API runs the integration profile by default in compose, so the wire log is on.
# IMPORTANT: do NOT pass the `fake` profile — the default (all,integration) uses the REAL
# Http* clients. `all,fake` would run everything against the fakes.
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
| **`order.fulfil` real writes (Flow C)** | **`fulfilMode=command`** — otherwise it is a full **offline simulation** and writes nothing. Real mode does `pick_pack` DynamoDB writes + PickPack/invoice POSTs against the fulfilment hosts (needs SSO + VPN). Dynamo writes are approved for this skill only. |
| `order.place` (Flow C, optional) | mutating; confirm its real-vs-gated mode with their config **before invoking** — see the caution in §9. |
| OpenAI (financial AI half + chaining) | `OPENAI_API_KEY` (absent → analysis still returns the **deterministic** verdict with `aiAnalysisStatus: UNAVAILABLE`), `OPENAI_FINANCIAL_MODEL`, **`OPENAI_STORE_RESPONSES=true`** (required for Flow E chaining — without stored responses there is no `responseId` to chain from) |

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
The catalog should sync (**8 skills, `v1/86105e7d7330`** — including `order.place`/`order.fulfil`
enabled): `docker compose logs api | grep "Synced"`.

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

## 4. Flow B — Financial analysis (snapshot)

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

## 5. Flow C — Order fulfilment (MUTATING — read §9 first)

> **`order.fulfil` and `order.place` WRITE.** They require `confirm:true` (the API returns
> 409 `confirmation_required` otherwise — the UI shows a "mutating" badge + a confirm
> checkbox). `order.fulfil` only actually writes when the orchestrator runs
> **`fulfilMode=command`**; otherwise it is an offline simulation. **Not yet live-verified —
> this run is the joint validation.** On any misbehaviour, capture the exact request/response
> from the log and send it over.

### C1 — `order.fulfil` on a shipment-ready order (the documented path)

Input (catalog v2.0.0): `order_id` (req), `env` (req), `club_id?`, `shipment_no?` — the last
two auto-detected from the shipment aggregate when omitted.

```bash
curl -s -XPOST $API/sessions/$SID/skills/order.fulfil -H 'Content-Type: application/json' \
  -d '{"input":{"order_id":"<SHIPMENT_READY_ORDER>","env":"<real-env>"},"confirm":true}'
# poll:
curl -s "$API/runs/<run_id>" | jq '{status, result_summary}'
```

**Check:**
- A completed fulfilment is a `succeeded` run; a failed one is a **`failed` run with the failing
  step in findings** — it never reports a fake pass.
- An `order_fulfilment` json artifact carries the per-step trace (shipment aggregate →
  PickPack pickOrder/pickItem/pickComplete → finishStaging → invoice validate).
- `output_inline`: `fulfil_status` (FULFILLED/FAILED), `shipment_no`, `club_id`,
  `lines_fulfilled`, `steps_total`/`steps_failed`.
- The fulfilled order surfaces in `subjects_discovered.order_number`; a `FindingDescriptor` per
  failed step lands in the findings feed.
- **UI path:** ⌘K → `order.fulfil` → fill `order_id` + `env` → tick the mutating confirm →
  run → watch the pending card settle and the findings feed.

### C2 — `order.place` (OPTIONAL, CAUTION — see §9)

Input (catalog v2.0.1): `member_id` (req), `sku` (req), `qty?` (default 1). **No `env` field.**
Only run this once you've confirmed with the orchestrator's config that placement is really
wired (not gated) — it may place a **real order**.

```bash
curl -s -XPOST $API/sessions/$SID/skills/order.place -H 'Content-Type: application/json' \
  -d '{"input":{"member_id":"<REAL_MEMBER>","sku":"<REAL_SKU>","qty":1},"confirm":true}'
# the placed order surfaces in subjects_discovered.order_number → feed it into C1 order.fulfil
```

## 6. Flow D — Financial analysis (lifecycle)

Needs **≥2 states**. Capture two evidence items for the same order (e.g. two `api.fetch_order`
at different lifecycle points, or `api.fetch_order` + a captured snapshot), then assemble them
as `LIFECYCLE_COMPARISON` — capture order becomes the sequence.

```bash
curl -s "$API/sessions/$SID/artifacts" | jq '.artifacts[] | {artifact_id, logical_name}'

curl -s -XPOST $API/sessions/$SID/analyses -H 'Content-Type: application/json' \
  -d '{"analysis_mode":"LIFECYCLE_COMPARISON","order_number":"<REAL_ORDER>",
       "states":[{"kind":"ARTIFACT","id":"<artifact_1>","state_type":"ORDER_SNAPSHOT","source":"ORDER_DB"},
                 {"kind":"ARTIFACT","id":"<artifact_2>","state_type":"ORDER_SNAPSHOT","source":"ORDER_DB"}]}'
# → 202; poll the run for the verdict
```

**Check:**
- The run reaches `succeeded`; `result_summary.overall_status` set; each transition validated
  in sequence order.
- ≥2 states assembled (`GET $API/sessions/$SID/analyses` → `state_count`).
- **UI path is the real test:** "Financial analysis" → multi-select two items → **"Build
  states"** → watch the lifecycle verdict.

## 7. Flow E — Follow-up chaining

After a prior analysis (Flow B or D) has **run and produced a `responseId`** (needs
`OPENAI_API_KEY` + `OPENAI_STORE_RESPONSES=true`), continue its conversation:

```bash
# Which analyses can seed a follow-up? (chainable = has produced an OpenAI response)
curl -s "$API/sessions/$SID/analyses" | jq '.analyses[] | {analysis_id, analysis_mode, chainable}'

# Create a follow-up that continues the prior conversation
curl -s -XPOST $API/sessions/$SID/analyses -H 'Content-Type: application/json' \
  -d '{"analysis_mode":"SNAPSHOT_SANCTITY","order_number":"<REAL_ORDER>",
       "states":[{"kind":"ARTIFACT","id":"<a later artifact>","state_type":"ORDER_SNAPSHOT","source":"ORDER_DB"}],
       "previous_analysis_id":"<prior analysis_id with chainable:true>"}'
```

**Check:**
- The follow-up's first orchestrator call carries `previousResponseId` (wire log), and the
  result echoes a new `responseId` pinned on the analysis head.
- The UI shows a "continuing the conversation from …" badge on the follow-up.
- **Degrade check:** a stale/expired/unknown token must fall back to a full re-read, **not**
  error. Cross-session / not-yet-run references are rejected at our boundary
  (`unknown_previous_analysis` / `not_chainable`) before any call.

## 8. Flow F — Planning Copilot (Test Plan & Data Studio) — the second flavor

This is a **separate flavor** from the session flows above — its own `/plan` wizard and
`/planning/*` API, **no session** (planning has no order-under-test). New in v1.0.30 and
**never run live before — this is the joint validation.**

**Extra prerequisites (on top of §2):** the orchestrator build must have `planning.*`
registered (catalog `v1/693ede402294`, 13 skills) and be configured with:
- **aashari Atlassian MCP creds** — site/email/API-token as env for the child processes
  (`@aashari/mcp-server-atlassian-*`, launched via `npx.cmd`); read-only Jira/Confluence.
- **`OPENAI_API_KEY` + `OPENAI_STORE_RESPONSES=true`** — the two generators run on the real
  OpenAI planning client (no deterministic fallback; no key → `upstream_unavailable`).

Drive it from the UI (**⇦ the real test**): switch to **Plan** in the left rail →
http://localhost:5173/plan. Or by API:

```bash
API=http://localhost:8080/api/v1
# 1) Create a project
PID=$(curl -s -XPOST $API/planning/projects -H 'Content-Type: application/json' \
  -d '{"feature_key":"<REAL-JIRA-KEY>","feature_description":"<what we are testing>","created_by":"you","env":"<env>"}' | jq -r .project_id)

# 2) Add a source doc (Markdown/text/paste)
curl -s -XPOST $API/planning/projects/$PID/documents -H 'Content-Type: application/json' \
  -d '{"source_type":"paste","title":"spec","text":"<requirement text>"}'

# 3) Search Jira + Confluence over the REAL aashari MCP
curl -s "$API/planning/projects/$PID/jira-search?query=<term>"        | jq '.[] | {ref, title, status}'
curl -s "$API/planning/projects/$PID/confluence-search?query=<term>"  | jq '.[] | {ref, title}'

# 4) Fetch selected context (persists each as a source_document; returns the console log)
curl -s -XPOST $API/planning/projects/$PID/fetch -H 'Content-Type: application/json' \
  -d '{"jira_keys":["<REAL-KEY>"],"confluence_page_ids":["<REAL-PAGE-ID>"]}' | jq '{docs: (.documents|length), log: .log}'

# 5) Generate the test plan (async → 202 with a generation_id; poll it)
GEN=$(curl -s -XPOST $API/planning/projects/$PID/test-plan | jq -r .generation_id)
curl -s "$API/planning/projects/$PID/generations/$GEN" | jq '{status}'      # poll until succeeded/failed
curl -s "$API/planning/projects/$PID/test-plan" | jq '{overview, scenarios: [.scenarios[].scenario_key]}'

# 6) Generate test data for the plan's scenarios (async → 202; poll)
GEN2=$(curl -s -XPOST $API/planning/projects/$PID/test-data -H 'Content-Type: application/json' \
  -d '{"scenarios":[{"id":"TC-01","title":"…"}],"edge_cases":["boundary","null"],"rows_per_scenario":8}' | jq -r .generation_id)
curl -s "$API/planning/projects/$PID/generations/$GEN2" | jq '{status}'
curl -s "$API/planning/projects/$PID/generations/$GEN2/test-data" | jq '.[] | {scenario_key, rows: (.rows|length)}'
```

**Check:**
- **Step 2/3 (MCP, read-only):** `jira_search`/`confluence_search` return **real** issues/pages;
  `fetch_context` returns real bodies + a per-item `log`. In the wire log these are
  `POST /skills/planning.*/execute → succeeded`, data in the `planning_*` artifact body.
- **Step 5/6 (generators, real OpenAI):** each generation settles to `succeeded`; the plan has
  an overview/scope + scenario table; test data has per-scenario rows. A missing key → the
  generation is `failed` with `upstream_unavailable` (honest, never a fabricated plan).
- **Regenerate chains:** a second generate carries `previous_response_id` (needs
  `OPENAI_STORE_RESPONSES=true`); a stale token degrades to a fresh generation, never errors.

**Watch for two things (first live run):**
1. **Session-less envelope.** copilot drives these skills with a synthetic, empty
   `session_context` (planning has no session). If the orchestrator's `execute` route rejects a
   planning call for a missing `session_context` field, that's the mismatch we flagged in
   v1.0.31 §1 — capture the request/response and send it.
2. **PLAN-1.** A truncated Jira body comes back with **empty `text`** — expected. copilot
   persists it as an empty source document (still listed), never a failure. A blank Jira body
   reads as this, not a bug.

## 9. Capture the log and hand it back

```bash
git -C autwit-copilot add autwit-copilot-api/logs/copilot-api.log
git -C autwit-copilot commit -m "Live integration log: A–F, order <REAL_ORDER>"
git -C autwit-copilot push
```

The log carries, PII-safely:
- the copilot↔orchestrator **skill** exchange (WireLog: request/response bodies, truncated) —
  including the `order.fulfil`/`order.place` invocations and their result envelopes,
- the **financial** exchange (metadata + verdict only — request bodies are NOT logged; they
  carry order PII, see PII-1),
- every artifact's `content_hash` (declared vs computed), and the run lifecycle.

Also send: the `run_id`s + verdicts (go/no-go), and for anything that misbehaved, the exact
request/response (the orchestrator side does the same from its end).

## 10. Known caveats going in

- **Planning (Flow F) is unproven live + copilot's client is unexercised.** `HttpPlanningClient`
  is now wired to the real skill-execute surface but has only ever run against the fake — this is
  its first real exercise. Two likely first surprises: the **session-less execute envelope** (if
  the orchestrator's route needs a `session_context` field for planning) and **PLAN-1** (empty
  Jira body — expected). Both are in Flow F's watch-list.
- **Mutating skills (Flow C) WRITE, and are not live-verified.** `order.fulfil` performs real
  `pick_pack` DynamoDB writes + PickPack/invoice POSTs **only under `fulfilMode=command`**
  (offline sim otherwise); `confirm:true` is mandatory. First real run is the joint validation
  — if a real payload misbehaves, send the exact request/response and we pin it together.
- **`order.place` gating discrepancy — verify before running.** v1.0.27 §0 listed "Playwright
  order placement" as **still gated**, but the v1.0.28 catalog ships `order.place` as
  **enabled + mutating** (`member_id`/`sku`/`qty`). These disagree. **Confirm with the
  orchestrator's own config what `order.place` actually does in command mode before invoking
  it** — treat it as capable of placing a real order until proven otherwise. (Raised with them
  in our v1.0.29 §2.)
- **`env` has no enum yet.** `order.fulfil`'s `env` is a plain string in `input_schema`, so the
  palette renders a free-text field — type the exact env (e.g. `qa3`), a typo selects the wrong
  host set. We asked them to add an `enum` (v1.0.29 §2); until then, get the value right by hand.
- **Chaining needs stored responses.** Flow E requires `OPENAI_API_KEY` +
  `OPENAI_STORE_RESPONSES=true`. Without a key the deterministic verdict still stands but
  `responseId` is null → analyses show `chainable:false` and Flow E can't start (correct, not a
  bug).
- **`events.capture_since` open finding (v1.0.25).** On the last live pass this returned
  `total_headers:0` (empty Event Store scan) for every order while snapshot/fetch_order worked.
  If the Event Store is now wired, a capture will show `total_headers>0`; if it still returns
  0, that finding stands — note it in the handback.
- **PII-1** (`docs/KNOWN_ISSUES.md`): `api.fetch_order` persists raw order PII (member, card)
  into the artifact and `analysis_state.payload`. This is a **real capture of real PII** on
  that laptop's Postgres — treat the volume/DB accordingly and purge after. The log itself does
  not carry it.
- **FIX-2**: the copilot's own diff-feature financial reconciliation
  (`autwit.compare.financial` in `application.yml`) is keyed on the demo `oms.*` part_keys, so
  those *sum/cross-source invariants* silently no-op over a real `snapshot.capture`. Not a
  crash; just won't fire. Unrelated to the orchestrator's financial analysis.
- **AUTH-1 / C8**: auth defaults off on both sides. Decide §2 before running.
- A `content_hash` mismatch on any artifact would print declared-vs-computed + the leading
  canonical bytes in the log — that is the null-retention / canonical-form check working.
