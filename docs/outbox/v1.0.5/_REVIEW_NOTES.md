# Review notes for the v1.0.5 draft — read before sending

Not part of the message. Delete or leave behind when copying to
`autwit-ai-orchestration/message-from-qa-copilot/v1.0.5/`.

## Three things the draft commits us to

Each is a real decision. Change or cut any of them before sending.

**1. §0 — hardening `SkillCatalogSync` to compare skill content, not `catalog_version`.**
Commits us to code work not currently on any list. The orchestrator explicitly recommended
it *against their own interest* (they'd just proved the bug was their fixture, not their
pipeline). Cheap, and the reasoning generalises past this one bug. Cut it if you'd rather
not take on the work now — nothing else in the message depends on it.

**2. §3 — asking them to turn missing `env` into a 400.** This is a behaviour change to
their API that we are requesting. They said they think empty-success is probably wrong but
that it's our call. The draft takes the position and argues it from the evidence-tool
principle. If you'd rather keep empty-success — e.g. because a 400 makes some enqueue path
noisier — this section needs rewriting, not just softening.

**3. §4 — offering to write a cursor rekey migration.** Conditional on them having live
session data, so it may cost nothing. But it is an offer, and they may take it.

## What is verified vs. asserted

Verified by reading files this session:

- All five v0.1.1 amendments present in the v1.0.4 body (lines 224, 240, 441, 132–143).
- `catalog_version` is now `v1/3efcaf08f394`.
- The §3/§6.2 vs §6.3 topic contradiction — lines 118, 121, 334 plural; 357, 361 singular.
- `mergeCursors` does `putAll` on the raw topic string
  (`SessionContextBuilder.java:78`), so plural/singular are non-colliding keys.
- Our `orders.events` exposure: 3 tests, `V1__init.sql:188`, `openapi.yaml:1142/1145`,
  `docs/SKILL_CONTRACT.md:113/116/319/340`. No production branching on the string.

Asserted but **not** tested:

- That a stale plural cursor would actually persist and ship in every later request. This
  follows from reading `mergeCursors` plus the milestone flow; it has not been reproduced
  against a running session. The draft states it as consequence, not as observed
  behaviour — accurate, but if they push back, we should reproduce before insisting.

## Note on the v1.0.3 copy

`docs/FEEDBACK_TO_ORCHESTRATOR.md` in this repo is the same content as
`message-from-qa-copilot/v1.0.3/FEEDBACK_TO_ORCHESTRATOR.md`, written after the fact.
Worth moving to `docs/outbox/v1.0.3/` so the outbox history is in one place.
