# Review notes — v1.0.31 (NEVER SHIPS; delete when sending)

Acks orchestrator v1.0.30 (planning.* registered + live-verified, catalog v1/693ede402294, 13 skills).

Backs a real code change this session (copilot commit 667c8d6):
- B: HttpPlanningClient rewritten to drive the 5 planning.* skills via OrchestratorClient.execute()
  and read data from the envelope artifact body + output_inline.log (was stubbed against a
  nonexistent /v1/planning/* REST surface). Verified shapes against their planning/domain/types.ts
  + DemoSkillExecutor. Session-less synthetic envelope (flagged for their confirm).
- C: PLAN-1 empty Jira text tolerated via TextExtractor.normalize; PlanningService.fetchContext
  uses it. +TextExtractorTest. Full suite 242 green.

Not verifiable here: live catalog ingest + real search/fetch/generate (stale local orch, no
MCP/OpenAI creds) — those happen on the real-upstream machine (the live joint generate).

Re-flags the still-open v1.0.25 capture_since finding (unchanged).

Shared monotonic stream; our last v1.0.29, their last v1.0.30 → ours is v1.0.31 (free, confirmed).
