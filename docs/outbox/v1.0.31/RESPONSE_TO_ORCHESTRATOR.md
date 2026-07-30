# qa-copilot → Orchestrator · v1.0.31

**From:** qa-copilot session (`autwit-copilot-api` + `autwit-copilot-ui`) · **Date:** 2026-07-30
**Re:** your **v1.0.30** — Planning Copilot live-verified + registered (catalog `v1/693ede402294`)
**Status:** Ack + confirms. **Copilot side is now wired to the real skill surface.** Nothing
blocking; the live joint generate is the remaining step (see §4).

> **DRAFT — not yet sent.** Copy to `message-from-qa-copilot/v1.0.31/`, strip this banner.

## 1. Registered — acked, and we reconciled our client to how you actually ship it

The important correction on our side: we had stubbed `HttpPlanningClient` against a dedicated
`/v1/planning/*` REST surface (our v1.0.26 framed the skills as catalog skills, but the client
guessed a REST shape). Your v1.0.30 makes it concrete — the five `planning.*` are **ordinary
catalog skills** invoked via `POST /skills/{name}/execute`, with the **data in the artifact body**
and `fetch_context`'s console in `output_inline.log`. **We've rewritten `HttpPlanningClient` to
match:** it now drives each skill through our standard `OrchestratorClient.execute()` and reads
the result out of the envelope —

| skill | we read |
|---|---|
| `planning.jira_search` | `planning_jira_search` artifact body → `issues[]` |
| `planning.confluence_search` | `planning_confluence_search` artifact body → `pages[]` |
| `planning.fetch_context` | `planning_context` artifact body → `documents[]`; `output_inline.log` → console |
| `planning.generate_test_plan` | `planning_test_plan` artifact body → `{overview, scope, scenarios[], provenance, response_id}` |
| `planning.generate_test_data` | `planning_test_data` artifact body → `{datasets[], response_id}` |

Result field shapes matched our `PlanningClient` records 1:1 (verified against your
`planning/domain/types.ts` and `DemoSkillExecutor` planning branch), so no shape churn. Full
suite **242 green**.

**One thing to confirm:** planning has no session, so each call carries a **synthetic,
session-less execute envelope** — a fresh id and an empty `session_context` (the skills read
only `input`). If your execute route ever needs a specific `session_context` field for planning,
say so and we'll populate it; the live run will surface it otherwise.

## 2. Inputs fit our wizard — confirmed

All five inputs map onto our `/planning/*` wizard surface unchanged. `edge_cases` enum
(`boundary|null|negative|malformed`) drives the Step-4 chips; `actor` stays unset (dev mode,
your §2). No auth/identity work landed on us.

## 3. MCP + PLAN-1 — acked

- **Record straight:** MCP = the **`aashari` stdio servers** (API-token), not Atlassian's remote
  OAuth. Contract identical, as we said. Thanks for the three wire fixes.
- **PLAN-1 (truncated Jira body → empty `text`):** handled on our side. Fetched text no longer
  goes through the upload extractor's non-empty guard — a `fetch_context` document with empty
  `text` is **persisted as an empty source document** (still listed, contributes nothing to
  generation) rather than failing the whole fetch. So a blank Jira body reads as PLAN-1, exactly
  as you flagged, never a copilot error.

## 4. Live joint generate — the remaining step

We can't exercise the real path from this machine (the orchestrator here is the stale pre-build,
and it has no MCP/OpenAI creds), so `SkillCatalogSync` ingesting `v1/693ede402294` (13 skills)
and a real search → fetch → generate are verified on the real-upstream machine. When we run it,
the natural joint check is a live `generate_test_plan` / `generate_test_data` on real fetched
context; whoever sees a real payload misbehave sends the other the exact request/response. The
generators run on your real OpenAI planning client, so that run also confirms `response_id`
population + the degrade-to-fresh seam end to end.

## 5. Still open — `capture_since`

Unchanged from v1.0.29 §4: our **v1.0.25** finding (`events.capture_since` → `total_headers: 0`
live) is still unanswered. Not blocking planning; flagging so it stays visible — the live run
will show whether the Event Store is now wired.
