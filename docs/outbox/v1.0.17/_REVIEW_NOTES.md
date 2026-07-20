# Review notes for the v1.0.17 draft — read before sending

Not part of the message. Leave behind when copying to
`message-from-qa-copilot/v1.0.17/`.

## What this commits us to

Session ownership + building the feature now (V2 migration, projection layer, picker UI).
User explicitly authorized: "we are building everything now, tomorrow already arrived."
Also commits us publicly to `store: true` being signed off — the message says "someone
with authority over data retention has signed off." That authority is **the user's
decision in this session (2026-07-21)**; if that's not actually a final org position, soften
§2.2 before sending.

## Verified this session

- StateEnvelope / StateType(9) / SourceSystem(11) shapes — read from
  `financial/domain/types.ts`.
- Normalizer alias-tolerance + NOT_VERIFIABLE degradation — read from
  `stateNormalizer.ts` (pick() across field-name aliases, unresolved[]).
- SNAPSHOT_SANCTITY enforces exactly one state —
  `FinancialAnalysisService.ts:81-83`.
- Our session PK is `session_id` (their DDL FK bug) — `V1__init.sql:20`.
- Skills deliberately unregistered — v1.0.16 §5.

## Not verified / assumptions to flag

- **The wire request schema may differ from `types.ts`.** I'm asking them to confirm it
  (§4.1) rather than assuming the internal domain type IS the wire contract. Their HTTP
  endpoint may wrap/rename. This is why §4.1 is framed as "confirm, and if it differs tell
  us the wire shape" — do not build the projection's output against types.ts until they
  confirm.
- **The stateType→rules mapping is genuinely unknown to us** (§4.2). I did not read every
  rule file (tax.ts, ebt.ts, arithmetic.ts) to reverse-engineer it — asking them is
  cheaper and authoritative. If they're slow, reading those three files is the fallback.
- The design (assemble-from-evidence) is ours; they may push back on it or find a coupling
  we missed. It leaves their compute untouched by construction, so pushback should be
  minor, but it IS a reframe of their proposal.

## Judgement calls

1. **Reframing their supply-the-envelopes model into assemble-from-evidence.** This is the
   substantive design move. It's better and leaves their side untouched, but it's us
   changing the shape of the collaboration, stated as a fait accompli ("on our side it
   is"). Fair, since it's entirely our side of the boundary.
2. **Asking for the stateType→rules mapping.** New work for them, but small (a table) and
   genuinely blocks good projection. Worth it.
3. **Committing to keep skills unregistered until we prove e2e** — binds us to a
   "signal when ready" step; don't forget to actually send that signal later.
