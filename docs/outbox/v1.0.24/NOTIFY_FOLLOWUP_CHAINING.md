# Notify: copilot now exercises `previousResponseId` on follow-up financial calls

**From:** qa-copilot session (`autwit-copilot-api`) · **Date:** 2026-07-22
**Re:** the OpenAI conversation-chaining seam you defined in v1.0.16 §4
**Status:** Notification + one small confirm. **No schema change, no catalog change,
no action required** beyond the confirm below.

## What changed on our side

A tester can now run a **follow-up** financial analysis that *continues a prior
analysis's ChatGPT conversation*. Mechanically: a new analysis can be seeded from a
prior analysis of the same session, and on that follow-up the **first** call to your
financial API carries `previousResponseId` (camelCase wire) = the prior analysis's
`latest_response_id`.

This is not a new wire field — it has been in `FinancialAnalysisRequest.previousResponseId`
since we built the client against v1.0.16/§4.1. What is new is that we now **populate** it
from the UI instead of always sending `null`. So from your side the request shape is
identical; you will just start seeing a non-null `previousResponseId` on some calls.

The path is symmetric across both modes — a chained call can be `snapshot` **or**
`lifecycle`.

## What we rely on from you (all previously stated — just confirming)

1. **`previous_response_id` is a cache, never a dependency** (your v1.0.16 §4). A
   `null`, **expired, or unknown** token must **degrade to a full re-read**, not error.
   We only ever chain from an analysis that already produced a `responseId`, so the token
   was real when it was pinned — but it can still expire before the follow-up runs, so the
   graceful-degrade path is the one that matters in practice.
2. **The result echoes `responseId`.** We pin `result.responseId` onto the analysis
   session head; that pinned value is exactly what the next follow-up sends back. If a
   real-mode result ever comes back with a null `responseId`, chaining simply can't start
   from it (our UI marks such an analysis "not chainable") — which is correct, just worth
   knowing.

We enforce the safety on our side too: a follow-up can only be built from a prior analysis
that (a) belongs to the same session and (b) has already produced a response. Anything else
is rejected at our boundary (`unknown_previous_analysis` / `not_chainable`) before a call is
ever made.

## The one confirm

Please confirm that in **command mode with a real OpenAI key**, your financial API:
- populates `responseId` on the result, and
- honors a supplied `previousResponseId` (continuing the thread) while treating a
  stale/unknown one as a cache miss (full re-read), not a 4xx.

If that all holds, there is nothing for you to change — this is the behavior your v1.0.16
§4 already promised, and we'll validate it end to end on the staged live run
(`docs/LIVE_INTEGRATION_TEST.md`), where the first real chained follow-up is a joint check.
If any of it does **not** hold (e.g. you'd rather we omit the field until a response is
confirmed live), say so and we'll gate the UI accordingly.

## Coordination

No catalog regeneration, no contract version bump — this rides entirely on the existing
`/v1/financial-analysis/{snapshot,lifecycle}` surface. Copilot commit: `40d8c5a`
(suite 218 green; both financial modes chain against the fake).
