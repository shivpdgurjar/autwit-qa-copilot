# Known issues

Tracked, deliberately-deferred issues. Each has an owner decision behind it.

## PII-1 — raw order PII lands in the evidence store (financial analysis)

**Status:** open, deferred (decision 2026-07-21). Decide before the first **real** order capture.

**What.** `api.fetch_order` (orchestrator, now real — see `message-to-qa-copilot/v1.0.19`
§4.2) returns the OMS Order Universal API body **verbatim** as an `api_response` artifact:
`{data, meta}` with no reshaping. That body contains PII — `data.member`
(email, name, phone, zipCode), `data.addresses`, and `data.orderPayments.card`. Because
copilot-api persists artifacts as evidence and the financial-analysis projection copies
artifact bodies into `analysis_state.payload`, that PII lands in **two** durable places:
`autwit.artifact` and `autwit.analysis_state`.

**Why it's deferred, not fixed now.** Nothing is live yet, so there is no real PII in any
store. And the obvious fix — masking at ingest — is **wrong** for this system: artifact
bodies are evidence, and mutating them at ingest breaks the §6.1 `content_hash` (the whole
rule is "hash exactly what was sent") and mangles the evidence the product exists to show.
See [money-rendering-is-ui-only] reasoning — same principle. So this needs a real decision,
not a reflex.

**Options when we pick it up:**
1. **Keep evidence intact; control access + retention.** No body mutation. Bring
   PII-bearing artifacts under the retention/purge job (see the missing-retention-job gap),
   add access controls, document in an ADR. Consistent with the evidence-integrity model.
2. **Redact in the projected copy only.** Leave the source `api_response` artifact
   hash-intact; scrub known PII fields (`data.member`, `data.orderPayments.card`, …) when
   projecting into `analysis_state.payload`, so the durable analysis and the AI layer never
   see them. Changes `payload_hash`; needs a maintained known-PII-field map.

**Not our exposure to fix alone:** the orchestrator already does NOT log the body (their
request logging is status-only), and the §6.1 hash is over the projected payload — so the
leak surface is our durable store specifically.

**Owner:** qa-copilot side (we own the durable store). Revisit before first real capture.
