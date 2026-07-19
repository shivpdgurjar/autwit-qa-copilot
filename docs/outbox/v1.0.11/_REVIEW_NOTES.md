# Review notes for the v1.0.11 closeout spec — read before sending

Not part of the message. Leave behind when copying to
`autwit-ai-orchestration/message-from-qa-copilot/v1.0.11/`.

## Nothing in the repo changed

Analysis and decisions only. No code, contract, fixture or config edits. Suite not re-run —
nothing touched that could move it.

## What makes this different from v1.0.9

Framed as a closeout: every open item on both sides, each with an acceptance criterion, and
an explicit statement that we will not ask again. Two consequences worth being deliberate
about:

1. **Part C binds us publicly.** Seven commitments in a shared repo, one (C6) with no date
   and one (C7) deferred indefinitely. That is honest, but it is on the record.
2. **"We will not ask again" forecloses a reply.** If B1–B7 come back partially done, we
   have said we will not re-request. In practice we would still have to raise a genuine
   defect — the sentence is about not reopening *settled* questions, but it could be read
   more broadly. Soften if you would rather keep the option open.

## Claims and how solid each is

**Reproduced this session:**

- `catalog_version` opaque at five sites (A1).
- Invariant 5 — `application.yml:6,9,109`, `HttpOrchestratorClient:56,59,71`,
  `ConfigAssertions` asserts lease > timeout at boot (A2).
- `deadline_exceeded` used two ways: inbound `HttpOrchestratorClient:196`, synthesised
  outbound `OrchestratorException:29`, asserted `TimeoutTest:103–104,125` (B1).
- `confirmation_required` raised pre-emptively at `RunEnqueuer.java:102`, tested
  `IdempotencyTest:162`, handled `useSubmitRun.ts:80` (B6).
- `SchemaForm.tsx:99–109` renders any enum as a `<select>` (A4).
- Our contract is v0.1.3; `SkillCatalogSync:44` is still an `.equals()`;
  `InvokeRequest:20,30` still carries `deadlineMs` (Part C).
- v0.1.5 §8 union read directly from their table (B6).

**Not verified:**

- That a `run.error()` code outside §8's union confuses anyone in practice. Coherence
  argument, not an observed failure; the draft says nothing breaks at runtime.
- B5's required fixture shape is transcribed from their §6.3 prose, not from a working
  implementation — nobody has produced an order-scoped fixture yet on either side.

**Evidence regenerated 2026-07-19, current as of this draft:**

- Suite re-run with `target/surefire-reports` **deleted first**, so no stale file can
  inflate the count (that trap cost a bogus 178 earlier in this exchange). Result:
  **177 tests, 0 failures, 0 errors, 0 skipped**, 20 report files, all from this run.
- The two tests the draft cites as evidence both pass: `TimeoutTest` 6/6 (B1's
  `deadline_exceeded` vs `lease_expired` distinction), `IdempotencyTest` 15/15 (B6's
  pre-emptive `confirmation_required`).

## Decisions taken, and by whom

Both put to the user and answered — not my unilateral calls:

1. **Auth → fail closed with explicit dev opt-in** (A3), stronger than their own (a).
2. **`shipment_only` → keep the 400 *and* drop from the enum** (A4), accepting a
   `catalog_version` bump deliberately.

## Judgement calls worth your eye

1. **B1 asks them to accommodate our internal code rather than us renaming.** Somewhat
   self-serving — renaming ours is arguably cleaner hygiene. The draft offers the
   alternative and pre-commits to accepting it without argument, which is the honest way to
   ask.
2. **Pressing on auth while having none.** Disclosed in A3 rather than left to be noticed.
3. **B6 is new work we are creating for them**, found incidentally while checking B1. Real,
   but it did expand their list at the same moment we called this final.
4. **B5 is the only thing gating us (C5).** If they do nothing else, that is the one to
   push for.
