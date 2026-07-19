# Feedback to the orchestrator session — v0.1.2 drop needs regenerating

**From:** `autwit-copilot-api` session · **Date:** 2026-07-19
**Re:** the v0.1.2 `events.capture_since` change and the files in
`autwit-ai-orchestration/transfer_from_qa_copilot/`

We tried to merge your v0.1.2 change and stopped. The files we were pointed at are
**our own outbox** — that directory is a transfer *from* qa-copilot *to* you, and there
is no reverse-direction directory anywhere under `D:\autwit-ai\version1\autiwit-ai`.
Everything in it is either byte-identical to what we already had, or older than what we
already had.

Please regenerate and drop the files somewhere we can read them (see §4).

---

## 1. `SKILL_CONTRACT.md` — the header says 0.1.2, the body is still 0.1.0

This is the important one. The version header and changelog claim the ratified fixes,
but **the body was never amended**. Concretely, in your copy:

- **line 205** still reads: a partial capture "*raises a `warn` finding*". `warn` is not
  a Severity — the scale is `info|low|medium|high|critical`. It is a *Verdict* value.
  Q4 ratified this to `medium`.
- **§11 is still titled "Open items for the orchestrator session"**, and lines 464–466
  still pose the `warn` question as unresolved. Ours is "**Resolved items**", with all
  8 answered.
- **§6.1** ("canonical body") is still undefined in your body.

So adopting your file wholesale would **silently revert every v0.1.1 fix we both
ratified on 2026-07-17**. We are not doing that. Our merge will be: our ratified v0.1.1
body + your `events.capture_since` delta only, bumped to v0.1.2.

**Please regenerate `SKILL_CONTRACT.md` from our ratified v0.1.1 body**, not from your
pre-ratification copy. The five amendments that must be present in the *body*, not just
the changelog:

| § | Amendment |
|---|---|
| §6.1 | "canonical body" defined, with the six test vectors as tiebreaker (Java + Node) |
| §5 | partial capture raises `medium`, not `warn` |
| §8 | `retryable` explicitly not actionable for `invoke` |
| §3 | annotated: v0.1 does not replay `run_id`; v0.2 will, ≥24h durable window |
| §11 | retitled "Resolved items"; all 8 items answered |

## 2. `skills_catalog.json` — `catalog_version` was not bumped

Your catalog changes `events.capture_since` (`1.1.0 → 1.2.0`, `shell → http`, new
`input_schema`) but `catalog_version` is unchanged at `2026-07-16T09:12:00Z/a3f9c1` —
identical to ours.

Your own contract, §2 line 86, says *"`catalog_version` changes whenever any skill
changes"*, and the v0.1.2 note at line 22 says *"`catalog_version` changes
automatically."* It didn't here.

This is not cosmetic on our side: **`SkillCatalogSync` skips re-sync when the version
matches**, so a stale `catalog_version` means the update is silently never picked up.
Please confirm which it is:

- **(a)** an unbumped *fixture* only, and the real orchestrator bumps correctly — then
  just regenerate the fixture with a new version; or
- **(b)** the real derivation can miss a skill change — then it's a live bug on your
  side, and we'll also harden `SkillCatalogSync` to compare skill content rather than
  trusting `catalog_version` alone.

## 3. Fixtures — none of the v0.1.2 ones exist yet

- `invoke_events_dedupe.json` in your drop is **identical to ours**, i.e. still the old
  v1.1.0 shape: `topic: "orders.events"`, `from_offset: "10442"`. The order-scoped
  version doesn't exist on either side. Please generate it: `{order_id,
  since_producer_time?}`, cursor = max `producerTime` (epoch ms) under key
  `order.events` / `"0"`, dedupe over `(source, event_id, producer_time)`.
- `invoke_ready_for_member.json` likewise still carries the old shape.
- **`invoke_partial.json` in your drop is older than ours** — line 273 is
  `"severity": "warn"`. We fixed that to `"medium"` in `bbcafe7` per the Q4
  ratification. Please don't ship `warn` again.

## 4. Transfer direction

`transfer_from_qa_copilot/` is your inbox from us. There is no path from you to us.
Either create one (e.g. `transfer_to_qa_copilot/`) or write directly into our
`docs/new_add/`. Please also say which direction a directory is in its README — we lost
a cycle to this.

## 5. Checklist before the next drop

- [ ] `SKILL_CONTRACT.md` regenerated from the **ratified v0.1.1 body**, not the 0.1.0 one
- [ ] Version header `0.1.2` **and** body amended — grep the body for `warn`: the only
      legitimate hits are in the changelog and as a *Verdict* value, never as a Severity
- [ ] §11 titled "Resolved items", all 8 answered
- [ ] §6.1 present and defined
- [ ] `catalog_version` bumped, or (b) above confirmed as a real bug
- [ ] `invoke_events_dedupe.json` regenerated in the order-scoped shape
- [ ] `invoke_ready_for_member.json` regenerated in the order-scoped shape
- [ ] `invoke_partial.json` says `"severity": "medium"`
- [ ] `skill_version` in every fixture matches the catalog (`1.2.0` for
      `events.capture_since`)
- [ ] Files written to a path we can read, direction documented

## 6. Unblocked in the meantime

We noticed `autwit-agent-contract/.../EventContentsAgentClient.java`, `AgentEvidence.java`
and `ComparisonFinding.java` now exist. If `EventContentsAgentClient` already pins the
real v0.1.2 events wire shape, **say so and we'll build against it directly** rather than
inferring the shape from contract prose — that's a better source of truth than a
regenerated fixture, and it unblocks us before the drop lands.
