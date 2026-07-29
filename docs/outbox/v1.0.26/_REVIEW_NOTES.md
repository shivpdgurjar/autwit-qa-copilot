# Review notes — v1.0.26 (NEVER SHIPS; delete the version banner + this file when sending)

**Context:** New second flavor of the copilot — "Test Plan & Data Studio" (Planning Copilot).
Wireframes at repo root: `wireframes_3444.html` (lo-fi, annotated) + `test-plan-studio_1392.html`
(hi-fi mock, working stepper). This proposal carves the orchestrator's scope.

**Decisions locked with the user before drafting:**
- Frontend: persistent flavor switcher + route namespaces (`/plan/*` vs `/execute/*`).
- Backend: new parallel domain, reuse run/worker + artifact + LLM-client infra. NOT extending `session`.
- MCP: build for REAL connection (user has connectivity). MCP client = orchestrator; MCP server
  in the orchestrator trust boundary (Atlassian remote OR self-hosted sidecar) — NOT copilot-api.
- Upload text extraction: copilot-api (Tika). PDF/DOCX deferred to pass 2; pass 1 = MD/text/paste.
- User asked for this proposal message explicitly.

**Version-number caveat:** shared monotonic stream. Our last sent = v1.0.25. Their reply to the
`capture_since` finding is still pending and would be v1.0.26 if it lands before we send. If so,
renumber this to v1.0.27 at send. The API/execution track is PAUSED (memory: API-PAUSE-POINT-1) —
sending this does NOT unpause it; it opens a separate track.

**NOT yet pushed to the orchestrator repo.** This is the draft step only. Await user's go before
copying to `message-from-qa-copilot/vX/` and pushing in the orchestration repo.

**Open questions we're deferring (not blocking the proposal):**
- Single `planning.search` parameterized by source vs the two split search skills (§4.1/§4.2).
  Went split for clarity; fine to collapse if they prefer.
- Whether `fetch_context` should stream true SSE progress or return a `log[]` — offered both.
- Datasets: return inline rows vs an artifact ref for large sets. Proposed inline; revisit if big.
