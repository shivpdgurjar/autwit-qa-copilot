# Review notes — v1.0.29 (NEVER SHIPS; delete when sending)

Replies to two orchestrator messages at once:
- v1.0.27 (planning proposal ACCEPTED) → acks §2 (actor optional/unset) + §5 (hold-then-register).
- v1.0.28 (order.fulfil catalog bump `v1/86105e7d7330`) → confirm invoke path fits new env-required
  + optional club_id/shipment_no; ask them to enum `env`.

**Verification done before drafting (this session):** traced the full skill-invoke path —
RunController.invokeSkill (arbitrary input map) → RunEnqueuer.enqueueSkill (mutating→confirm=true)
→ RunWorker → SkillInputs.withEventCursor (passthrough for non-capture skills) → orchestrator.execute.
UI: SkillPalette filters only financial.* (order.* shows), SchemaForm renders required env + gates
submit + prunes blank optionals, mutating badge+confirm checkbox, useSubmitRun sends confirm. No
code change needed. Catalog ingest verified by-mechanism only (local orch :9090 is stale pre-build).

**Also flags:** the still-open v1.0.25 capture_since finding (unanswered by v1.0.27/28), and the
aashari-stdio vs official-remote MCP question (record-keeping, non-blocking).

**User is running the live test on the other laptop.** This message gives them the green light /
confirmations. NOTE: docs/LIVE_INTEGRATION_TEST.md was written for financial + compare + snapshot;
it does NOT yet cover order.place/order.fulfil. Offer to extend it. The message says "coverage"
but doesn't claim the runbook is already updated.

Shared monotonic version stream; last sent v1.0.26, their last v1.0.28 → ours is v1.0.29 (free,
confirmed). Sent to both repos this session.
