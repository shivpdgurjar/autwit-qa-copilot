# Proposal: Planning Copilot ("Test Plan & Data Studio") — orchestrator scope

**From:** qa-copilot session (`autwit-copilot-api`) · **Date:** 2026-07-29
**Re:** a new *second flavor* of the copilot — a planning/authoring surface that turns
requirement docs + Jira/Confluence context into a test plan and test data.
**Status:** Proposal. Asks a **new** contribution from you: two MCP connectors and two
generation skills. Nothing here touches the execution/financial surfaces — it is additive.

> **DRAFT — not yet sent.** Copy to `message-from-qa-copilot/v1.0.26/`, strip this banner.
> (Final version number is assigned at send time; if your `capture_since` reply lands first
> this becomes v1.0.27.)

## 1. What we're building

A new flavor of the same app — the existing execution/session copilot is untouched; this is a
parallel workspace under its own route namespace (`/plan/*`) and its own backend domain. It is
a **4-step wizard** ("Test Plan & Data Studio"):

1. **Add inputs** — tester uploads design/requirement docs + optional existing test cases, and
   sets a feature key (e.g. `PAY-2481`) + a short "what are we testing" description.
2. **Fetch context** — connect to **Jira & Confluence over MCP**, search + select the relevant
   issues/pages, with a **visible, timestamped fetch console**.
3. **Test plan** — a generated deliverable: overview + scope prose, and a **scenario table**
   (TC-01…, priority, source), with provenance (which tickets + doc version).
4. **Test data** — pick scenarios, optionally paste a sample record, choose edge-case classes
   (boundary / null / negative / malformed), and generate **per-scenario datasets**.

## 2. The ownership split (so scope is unambiguous)

Same shape as the financial feature: **we own durable state; you own the LLM and the external
connectors + their secrets.**

| Concern | Owner |
|---|---|
| `planning_project`, `source_document`, `generation`, `test_plan`, `test_dataset` tables | **copilot-api** |
| File upload, artifact storage, **uploaded-file → text extraction** | **copilot-api** |
| Async run/worker, calling your skills, serving the wizard + streaming the fetch console | **copilot-api** |
| **Jira MCP** + **Confluence MCP** search/fetch | **you** |
| **Test-plan** + **test-data** generation (LLM) | **you** |
| MCP secrets / identity, MCP **client** role, MCP **server** hosting | **you** |

**copilot-api never speaks MCP.** It calls your skills and persists what you return.

## 3. MCP: who is client, where the server lives

- **You are the MCP client.** It sits with the LLM and the other external-fetch skills
  (`api.fetch_order`, `snapshot.capture`) — consistent with the current architecture.
- **The MCP server(s) live in your trust boundary** — either Atlassian's official remote MCP
  server (you connect via OAuth) or a self-hosted connector sidecar next to you. Whichever you
  pick is invisible to us; we only see the skills in §4.
- **Fetch stays explicit and user-driven**, not model-autonomous. The tester searches, checks
  boxes, then fetches — so please expose `search` and `fetch` as discrete skill calls we drive
  from the UI selections, rather than giving the model raw MCP access during generation. This is
  what makes Step 2's console honest (a visible log of exactly what was pulled).

## 4. The four skills we'd ask you to add

Snake_case skill I/O per SKILL_CONTRACT (the financial *HTTP API* is camelCase; these are
catalog skills, so snake_case). All shapes below are our proposal — please correct them to
match what you can actually produce.

### 4.1 `planning.jira_search` — candidate issues for the checklist
```
input:  { feature_key?: string, query: string, project?: string, max_results?: int }
output: { issues: [ { key, title, issue_type, status, updated_at, url } ] }
```

### 4.2 `planning.confluence_search` — candidate pages for the checklist
```
input:  { space?: string, query: string, max_results?: int }
output: { pages: [ { page_id, title, space, edited_by, edited_at, url } ] }
```

### 4.3 `planning.fetch_context` — pull full bodies for the selected items
```
input:  { jira_keys: [string], confluence_page_ids: [string] }
output: { documents: [ { source_type: "jira"|"confluence",
                         external_ref, title, text, fetched_at } ] }
```
**Progress for the console:** please emit one progress event per item as it lands (same
WireLog/step pattern the skill runs already use) so we can render the timestamped fetch log —
e.g. `{ ts, level, source, ref, message }`, "Fetched PAY-2481 — Payment retry logic". If per-item
streaming is hard, a per-item entry in a returned `log[]` array is an acceptable fallback.

### 4.4 `planning.generate_test_plan` — the Step 3 deliverable
```
input:  { feature_key, feature_description,
          source_documents: [ { source_type, title, text } ],   // uploads + fetched, unified
          existing_test_cases?: [ { title, text } ],
          previous_response_id?: string }
output: { overview, scope,
          scenarios: [ { id, title, priority: "High"|"Medium"|"Low", source } ],
          provenance: { sources: [string], doc_version? },
          response_id }
```

### 4.5 `planning.generate_test_data` — the Step 4 deliverable
```
input:  { scenarios: [ { id, title } ],
          example_record?: object,                 // shape-matching, optional
          edge_cases: [ "boundary"|"null"|"negative"|"malformed" ],
          rows_per_scenario: int,
          previous_response_id?: string }
output: { datasets: [ { scenario_id, columns: [string], rows: [ object ] } ],
          response_id }
```

Both generation skills should return a **`response_id`** so a regenerate can chain the prior
conversation — identical to the financial `responseId` seam (v1.0.16 §4), and a
missing/expired token must degrade to a fresh generation, never error.

## 5. What we own (so there's no overlap)

- All persistence: a `generation` is one of our async runs (new `RunType.PLANNING_GENERATION`),
  reusing the run/worker + idempotency machinery.
- Uploaded files land as artifacts; we extract text ourselves (Markdown/text/paste in pass 1;
  **PDF/DOCX deferred to pass 2**, via Tika on our side — no ask on you for parsing).
- We assemble the unified `source_documents[]` (uploads + your fetched context) and call
  §4.4/§4.5. We persist scenarios/datasets and render/export.

## 6. What we need back

1. **Confirm or correct the five skill shapes** in §4 — especially anything you can't populate
   (e.g. `doc_version`, per-item timing).
2. **The MCP auth model:** service account vs per-user OAuth (the wireframe shows "connected as
   \<user>"). If per-user, tell us what identity you need us to pass on the call and how.
3. **Which MCP server** you'll stand up (Atlassian remote vs self-hosted) and a rough ETA — this
   is the one piece that gates a real (non-mock) Step 2 on our side.
4. **Registration + catalog:** these five go through your catalog the same way (our
   `SkillCatalogSync` picks them up hot). Confirm you're OK registering `planning.*` once the
   path is proven, or whether you want them held unregistered first like `analyze_lifecycle` was.

No change to any existing surface. We'll build the copilot-api half (tables, wizard, run wiring,
extraction) against a mock connector in parallel so we're ready the moment §4 lands.
