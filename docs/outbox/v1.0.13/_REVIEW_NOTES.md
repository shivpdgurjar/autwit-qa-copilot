# Review notes for the v1.0.13 draft — read before sending

Not part of the message. Leave behind when copying to
`autwit-ai-orchestration/message-from-qa-copilot/v1.0.13/`.

## Repo state this reports on

Commit `7db260f` (local; our repo has no remote). C1–C5 implemented, contract merged to
v0.1.7, three fixtures + catalog adopted, two null-dropping defects fixed.

## Claims and how solid each is

**Reproduced this session:**

- B2: neither `invalid_input` nor `input_schema_violation` appears in any conditional in
  `main/`; only a comment in `SchemaForm.tsx`. Both reach
  `OrchestratorException.Failed` with the code preserved.
- `invoke_partial.json` byte-identical to ours, normalised for line endings.
- The hash diagnosis: their declared hash reproduces under Python's
  `json.dumps(sort_keys=True, separators=(',',':'))`; ours reproduced only with nulls
  stripped. Computed in Python, so it asserts §6.1's definition rather than either
  implementation.
- Both fixes verified by reverting them: 3 `ContentHasherTest` tests fail without the
  inclusion pin; the suite fails without the `FakeOrchestratorClient` fix.
- **The Q1-vector claim (§3a): `grep -i null` over `CONTRACT_RATIFICATION_REQUEST.md`
  returns nothing.** This is the strongest claim in the document and it is a clean
  negative — worth re-checking if they push back, since it accuses the ratified
  tiebreaker of a gap.
- Suite 188/0/0/0 with `surefire-reports` deleted first. UI builds.
- C8: `application.yml:5` empty token default, `HttpOrchestratorClient:107` sends
  `"Bearer " + token`.
- B5's `occurred_at` fix: zero disagreement across files for the 4 overlapping events.

**Not verified:**

- That the real orchestrator would in fact have rejected our artifacts (bug 1). It follows
  from `HttpOrchestratorClient` using `readValue` plus the hasher dropping nulls, but no
  call against a real orchestrator has ever been made — the integration remains untested
  end-to-end. The draft states this as consequence; if they challenge it, we should
  demonstrate rather than restate.

## Judgement calls

1. **§3a accuses the Q1 vectors — the ratified tiebreaker — of an omission.** That is the
   right call and the evidence is clean, but it reopens something both sides signed off.
   It is also the single most valuable thing in this message: without it, the next
   canonical-form disagreement hides the same way.
2. **Leading with our own bug rather than their two open items.** Deliberate. They found
   it, by a decision (generate, don't write) they made for unrelated reasons.
3. **C8 disclosed in full**, including that we asked them to close a hole our own default
   depended on and did not notice until after. Honest, and on the permanent record.
4. **"That is the only one"** at the end — we said v1.0.11 was our last request and this
   adds one. It is a real defect in a shared mechanism, so it is justified, but it is a
   second "last request". Cut §3a's ask down to a report if you would rather not.
