# Contract ratification request → orchestrator session

**From:** `autwit-copilot-api` session
**Re:** `SKILL_CONTRACT.md` v0.1.0 (Draft — "must be ratified by both sessions before
either side builds against it")
**Date:** 2026-07-16
**Status of our build:** steps 1–2 complete. Steps 3–7 proceed against fixtures and
are not blocked. **Step 8 (the swap to `HttpOrchestratorClient`) is blocked on Q1.**

Three questions. Q1 is new and is the one that will silently break the integration.
Q2 and Q3 are `SKILL_CONTRACT` §11 items 1 and 5, restated with what now depends on
them.

---

## Q1 — What exactly is the "canonical body" that `content_hash` covers?

**New. Not currently in §11. We think it is the highest-risk open item in the
contract.**

### The gap

§6.1 says:

> `content_hash` is sha256 over the canonical body. copilot-api recomputes and
> rejects on mismatch — this catches truncation.

"Canonical" is never defined. sha256 is unambiguous; *what you feed it* is not.

### Why this one is nastier than it looks

- **The failure is total, not partial.** If our canonicalisation differs from yours
  by so much as key ordering or a trailing zero, *every* artifact fails its hash
  check and *every* run fails. There is no degraded mode.
- **It is invisible until step 8.** `SKILL_CONTRACT` §10 makes the fixtures the
  contract's executable form and the tiebreaker — but we generate our fixtures with
  our own hasher, so our fixtures agree with our bugs. No test either side can write
  in isolation will catch a divergence. It surfaces on first contact.
- **It is a decision, not a discovery.** Both of us will pick something reasonable.
  Reasonable choices disagree.

### What we implemented, and propose ratifying

`ContentHasher` in `autwit-copilot-api`. `content_hash` is `"sha256:"` + lowercase
hex of the sha256 over these bytes:

| `format` | Canonical bytes |
|---|---|
| `json`, `jsonb` | UTF-8 of the JSON text, **object keys sorted lexicographically by Unicode code point at every level**, no insignificant whitespace, **numbers keeping the exact scale they were written with** (`1200.00` stays `1200.00`, not `1200.0`). Array order preserved. |
| `xml`, `text`, `csv`, `html`, `md` | UTF-8 of the string, byte for byte. No trimming, no newline normalisation, no XML canonicalisation. |
| `binary` | The raw bytes, **after** base64-decoding the transport encoding. The hash never covers the base64 text. |

Two rules carry the weight:

1. **Lexicographic key sorting.** This is the only rule that lets a Python or Node
   implementation agree with a JVM one without sharing code. Note it is *not*
   Postgres `jsonb`'s ordering (which sorts by length, then bytes) — do not derive
   the hash from a `jsonb` round-trip.
2. **Scale is preserved, never normalised.** `1200.00` and `1200.0` are the same
   number and different evidence. Any language whose JSON parser lands numbers in a
   float will silently destroy this — we had this exact bug and had to set Jackson's
   `USE_BIG_DECIMAL_FOR_FLOATS` to fix it. If your stack parses to float/double by
   default, **this will bite you**, and it will look like a hash bug rather than a
   number bug.

Everything else follows from one idea: **hash exactly what was sent.** We do not
normalise, because an artifact is evidence, and normalising evidence hides the diffs
this product exists to find.

### Test vectors

Computed with Python's `hashlib` — deliberately *not* with our Java, so they assert
the definition rather than our implementation. If your hasher reproduces these, we
agree. These are asserted in `ContentHasherTest`.

| `format` | Body (as sent) | Expected `content_hash` |
|---|---|---|
| `json` | `{"b":2,"a":1}` | `sha256:43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777` |
| `json` | `{"total_amount":1200.00}` | `sha256:9265ceec3116b656a0e2c1711c4d4c61a923ad4f4b415e56c1be43f314bdd3f1` |
| `json` | `{"total_amount":1200.0}` | `sha256:42ccde2e5bb1f12b2cae0d0b06eea6dcd7dcffbf0be20ea7e9936fbffcf2b782` |
| `text` | `hello` | `sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824` |
| `xml` | `<order id="XXXX"/>` | `sha256:a8a15f1ddca30f0f91fce79c75592113165a4a43857bd94f14d26a7584f75443` |
| `binary` | bytes `00 01 02 03` (sent as `AAECAw==`) | `sha256:054edec1d0211f624fed0cbca9d4f9400b0e491c43742af2c5b0abebf0c990d8` |

Row 1 pins key sorting (input order differs from canonical order). Rows 2 and 3 pin
scale — **they must not collide**; if they do, something in the pipeline is
destroying scale. Row 6 pins that binary hashes the decoded bytes.

### What we need

Either "ratified, our hasher reproduces the vectors", or a counter-proposal. We do
not need our definition to win — we need *a* definition written down in §6.1. If you
counter-propose, we will conform; we only ask that scale preservation survives,
because the financial validation in `BUILD_BRIEF` §7 depends on it.

### Proposed §6.1 amendment

> `content_hash` is `"sha256:"` followed by the lowercase hex sha256 of the canonical
> body, where canonical is defined as: for `json`/`jsonb`, the UTF-8 encoding of the
> JSON text with object keys sorted lexicographically by Unicode code point at every
> nesting level, no insignificant whitespace, array order preserved, and numbers
> serialised with exactly the scale they were received with; for `xml`, `text`,
> `csv`, `html` and `md`, the UTF-8 encoding of the string with no normalisation of
> any kind; for `binary`, the raw bytes after base64-decoding. Implementations MUST
> reproduce the test vectors in `CONTRACT_RATIFICATION_REQUEST.md` Q1.

---

## Q2 — Does `POST /invoke` honour `run_id` idempotency replay? (§11 item 1)

§3 currently says **SHOULD**:

> If the same `run_id` is re-sent, the orchestrator SHOULD return the prior result
> rather than re-executing. Critical for `side_effects: mutating` skills.

We need this promoted to **MUST** or demoted to **does not**. "SHOULD" is not
something we can encode.

### What now depends on it

`docs/ADR-001-reclaim-vs-reap.md`. `BUILD_BRIEF` §6's dequeue and reaper select
overlapping rows, so a dead worker's run is matched by both and whichever fires first
decides whether it is retried (§9 requires this) or buried as `timed_out` (invariant
8 requires this). We resolved it by making the predicates disjoint, gated on
`attempts < max_attempts`, and by setting `max_attempts` at enqueue:

| `run_type` | `max_attempts` | Why |
|---|---|---|
| `invoke` | **1** | **The LLM picks the skill after we enqueue.** We cannot know whether it is mutating, so we assume it is. |
| `skill_execute` | 1 or 2 | We know the skill name; we read `side_effects` from the catalog. |
| `milestone`, `comparison`, `report` | 2 | `snapshot.capture` is `side_effects: none`; comparison and report never touch you. |

So today: **if a worker dies mid-`invoke`, we never retry it.** It lands `timed_out`
and a human decides. That is deliberately conservative and it is the correct default
under uncertainty — but it means a lost worker turns a read-only snapshot capture
into manual work.

### Impact of each answer

- **"MUST replay on `run_id`"** → we raise `invoke` to `max_attempts = 2` and reclaim
  becomes safe, because a replayed `run_id` cannot double-execute a mutating skill.
  One line in `RunEnqueuer`.
- **"Does not replay"** → we keep 1, and **§8's `retryable: true` flag becomes
  meaningless for `invoke`** — we can never act on it automatically. A human clicks
  retry. Worth saying so explicitly in §8, because the flag currently reads as though
  copilot-api might use it.

### What we need

MUST or does-not, and if MUST: is replay durable across an orchestrator restart, and
for how long is a `run_id` remembered? A replay window shorter than our 12m lease
would give us the worst of both.

---

## Q3 — Is `part_key` naming owned by the scope definition, and guaranteed stable? (§11 item 5)

`BUILD_BRIEF` §12 lists this as one of the two items that block, and asks for it "in
writing". §6.2 already states the requirement:

> **`part_key` must be stable across snapshots of the same scope.** [...] If
> `order_flow` yields `oms.orders` at step 2, it must yield `oms.orders` at step 5 —
> same string, always, even if the table is empty.

We are asking you to confirm you own and guarantee it, because **the diff engine
fails silently if it drifts.** Comparison is a key-wise join on `part_key`. If
`oms.orders` becomes `oms_pg.orders` between two snapshots, we do not throw — we
report "1 part removed, 1 part added" as two `high` findings, and a tester reads that
as a product bug. The tool is most confidently wrong exactly when this breaks.

### What we need

1. Confirmation that `part_key` is assigned by the scope definition, not by the skill
   at runtime, and is stable for the life of a scope.
2. Confirmation that a scope emits its full `part_key` set **even when a table is
   empty** — an empty part and a missing part mean very different things to us (the
   latter is a `high` finding).
3. Where scope definitions live, and what the change process is when a scope gains or
   loses a table (§11 item 3 assumes: your repo, exposed via the `scope` enum in
   `snapshot.capture`'s `input_schema`).

---

## Not blocking, for your information

- We have added `POST /sessions/{id}/artifacts` to `openapi.yaml` so a tester can
  attach evidence directly. This does not affect you and does not weaken invariant 1
  — it is a tester-facing surface, you hold no credential for our API, and there is
  still exactly one writer to the `autwit` schema.
- We treat `status: partial` as: snapshot marked `partial`, run `succeeded`, `warn`
  finding raised (§5). Please keep returning `partial` rather than a truncated body
  when a scope would exceed the 8MB/32MB inline limits.
- §11 items 2 and 4 (DB credentials, execution isolation) do not change anything on
  our side. Answer at your leisure.
