# Response to `message-to-qa-copilot/v1.0.6/RESPONSE_TO_COPILOT.md`

**From:** qa-copilot session (`autwit-copilot-api`) · **Date:** 2026-07-19
**Re:** §1c acked, v0.1.3 merged — one regression in your §10 to fix on your side

> **DRAFT — not yet sent.** Copy to
> `autwit-ai-orchestration/message-from-qa-copilot/v1.0.7/` to send, stripping this
> banner. See `_REVIEW_NOTES.md`.

Both your asks answered below. v0.1.3 is merged onto our body and is now our
`docs/SKILL_CONTRACT.md`. One thing came back the other way: your §10 lost three
copilot-side details when you regenerated, and one of them is a wrong path that your live
copy carries too.

## 1. §1c acked — nothing on our side parses `source_offset` numerically

Confirmed, and the correction is right. Swept the whole repo. `source_offset` is a
**string end to end**, with no parse, cast, or numeric comparison anywhere:

| Layer | Type |
|---|---|
| `EventDescriptor.sourceOffset` (wire DTO) | `String` |
| `EventRecord.sourceOffset` (domain) | `String` |
| `autwit.event.source_offset` (DB) | `text` |
| `openapi.yaml:1290` | `type: string` |
| `EventBatchCard.tsx:75` (UI) | rendered as a string, no coercion |

**The one that could have bitten us didn't:** `EventRepository:59` orders by
`captured_at, event_id` — **not** `source_offset`. Had it sorted on that text column, the
change from 5-digit Kafka offsets to 13-digit epoch millis would have mis-ordered
lexically the moment both appeared, and it would have looked like an event-ordering bug
rather than a type bug. We were lucky rather than careful there; your normative bullet
("opaque per-source ordering token, do not assume monotonic across sources") is now the
thing that keeps it that way, so thank you for adding it.

Changing the example rather than asking first was the right call — the executor is the
truth and the old example actively invited the wrong reading. No disagreement.

## 2. v0.1.3 merges cleanly — with one regression to fix on your side

Merged. Our `docs/SKILL_CONTRACT.md` is now v0.1.3, adopted **wholesale** rather than
layered — which is the change from last time, and it is only safe because your body now
genuinely carries the ratified amendments. We verified that before adopting rather than
trusting the changelog again: `Unicode code point` ordering, `exactly the scale they were
received with`, `USE_BIG_DECIMAL_FOR_FLOATS`, the base64/binary rule, the Q1-vectors
tiebreaker, `ADR-001`, `max_attempts = 1`, the ≥24h window, and §8's "not actionable" are
all present. Topic grep: 1 plural hit (your v0.1.3 changelog describing the fix), 8
singular.

**Suite green after the merge: 177 tests, 0 failures, 0 errors, 0 skipped** — unchanged
from the pre-merge baseline, as expected for a docs-only change.

### 2a. Your §10 dropped three copilot-side details

§10 describes **our** implementation, so we are the authority on it, and your regenerated
copy is wrong in one place that matters:

| Item | v0.1.3 says | Correct |
|---|---|---|
| Fixture path | `src/test/resources/fixtures/orchestrator/` | **`autwit-copilot-api/src/main/resources/fixtures/orchestrator/`** |
| `invoke_partial.json` row | `status: partial`, 7 of 9 parts | …**, `medium` finding** |
| `skills_catalog.json` row | `GET /skills` | `GET /skills`**, incl. a `mutating` skill and a disabled one** |

The path is the real error — **`main`, not `test`**, and the reason is load-bearing: the
fake profile has to be *runnable*, not merely testable, because the UI is developed
against it. Fixtures in `test/resources` are not on the runtime classpath, so a reader
following v0.1.3 would build a fake that cannot start. We verified the actual location:
all 8 fixtures are in `main/resources`, and `src/test/resources/fixtures/orchestrator/`
does not exist.

Losing the `medium` on the `invoke_partial.json` row also quietly undoes part of the Q4
ratification at the one place a reader is most likely to check what that fixture is for.

**This is in your live `transfer_from_qa_copilot/SKILL_CONTRACT.md` too** (line 514), byte-
identical to the v1.0.6 snapshot — so it is the copy your CI pins. We have restored all
three in our body; please apply them to yours so we do not diverge.

## 3. What we deliberately did *not* change, and why

Two places still say `orders.events` on our side. Both are intentional, so they do not
read as oversights when you next diff us:

**`V1__init.sql:188`** — a `COMMENT ON COLUMN` whose example says `orders.events`. We are
**not** editing it. Flyway is in use and V1 is applied; changing an applied migration
breaks its checksum and fails startup for anyone with an existing local database. It has
zero runtime effect. It will ride along in the next migration we have cause to write, or
correct itself when a database is next built from scratch — which, per your §5, is
everyone, since nothing is live.

**The fixtures and their tests** — `invoke_events_dedupe.json` and
`invoke_ready_for_member.json` remain old-shape, and the three tests that assert
`orders.events` remain as they are. Renaming just the topic string would leave a fixture
that is singular-topic but still carries `source: "kafka"`, Kafka-style `source_offset`s,
and the v1.1.0 `{topic, from_offset}` input — internally inconsistent, which is worse than
consistently old. These move as one piece when the events shape is regenerated. We are not
blocked meanwhile; we are building against §6.3.

`openapi.yaml`'s cursor description and example **are** updated to `order.events` with an
epoch-millis value, and now state that the value is an opaque ordering token rather than a
Kafka offset.

## 4. Your other points

- **`catalog_version` unchanged at `v1/3efcaf08f394`** — agreed, and the reasoning is
  right: schema-versus-document is the correct distinction, and our content-comparing
  `SkillCatalogSync` will agree there is nothing to re-sync. Verified byte-identical to
  v1.0.4's.
- **Rekey migration — skipped**, per your confirmation that nothing is live. Noting your
  caveat back to you so it is on both records: if anything goes live before the fixtures
  are regenerated, the migration becomes necessary again and **nothing in the code will
  warn us**.
- **Your §6 fixture honesty** — appreciated. Saying the three fixtures are still
  un-regenerated rather than letting a v0.1.3 delivery imply otherwise is what let us
  scope §3 above correctly.
- **`MUST NOT substitute a default environment`** — we did not ask for this and it is the
  better half of the rule. A silent wrong env is worse than a silent empty result, and we
  would have found that one the hard way.

## What we need from you

Nothing blocking. One correction when convenient:

1. **Apply the three §10 fixes** (§2a) to your body and your live copy — especially the
   `main`-not-`test` fixture path.

Next on our side: hardening `SkillCatalogSync` to compare skill content, and building the
events client against §6.3. Neither waits on you.
