# Review notes for the v1.0.9 draft — read before sending

Not part of the message. Leave behind when copying to
`autwit-ai-orchestration/message-from-qa-copilot/v1.0.9/`.

## Nothing in the repo changed

This message is pure analysis — no code, docs, fixtures or contract edits. The only
artifacts are this draft and these notes. Suite not re-run; nothing to re-run it for.

## The claims and how solid each is

**Solid — reproduced this session:**

- Their §10 history claim. `git show 9174039^:transfer_from_qa_copilot/SKILL_CONTRACT.md`
  → line 420 `src/test/resources/...`, line 428 bare `invoke_partial.json` row, header
  says 0.1.2. My v1.0.7 "regression" diagnosis was wrong.
- All seven sections byte-identical, `e841cd9^` vs v0.1.4. Verified two ways (per-section
  extraction, then whole-file diff with lines tagged by enclosing section). Non-empty
  extractions, matching line counts. The two-way check was not paranoia — the first method
  reports "identical" for two empty extractions, which would have been a false all-clear.
- §10 against the code: `FakeOrchestratorClient.java:36,41`, 8 fixtures in
  `main/resources`, no `src/test/resources/fixtures/orchestrator/`. Table claims read out
  of the fixture JSON (9/1, 14, partial+7+medium, mutating+disabled).

**Reasoning, not measurement — the core argument of §3:**

- That doc-vs-doc comparison cannot detect shared inheritance. This is an argument about
  method, not a finding. It is well supported by the §10 case (wrong identically in both
  copies for four versions) but it is a generalisation from one instance.
- The §6.2 concern — that our §6.2 and theirs could agree while both misdescribe their
  `SkillRegistry` — is a hypothesis. Nobody has checked §6.2 against their code, which is
  precisely the point being made, but it means the draft raises an unconfirmed worry.
  That is deliberate and labelled as such in the text.

## Judgement calls

1. **Refusing to treat the clean diff as reassurance.** The easy reply was "all seven
   identical, we're good." The draft argues the opposite — that the result rules out
   divergence and says nothing about correctness. This reopens work rather than closing
   it, and it contradicts the natural reading of a clean result. If you'd rather bank the
   good news and move on, §3 is the section to cut.
2. **Pushing §6.2 back to them as highest priority.** Justified — only they can check the
   document against their `SkillRegistry` — but it does assign them the largest piece of
   remaining work.
3. **Conceding my §10 diagnosis plainly**, including that the "both were always wrong"
   explanation never occurred to me. Accurate, and the error is instructive rather than
   embarrassing, but it is on the permanent record in a shared repo.

## Relevant to the planned orchestrator-side wholesale review

The division-of-labour table in §3 is the actionable output. If that review runs
doc-vs-doc it will reproduce this same false all-clear across all seven sections. It needs
to be doc-vs-implementation, run by whichever side owns the implementation — that is the
one thing worth carrying into it.
