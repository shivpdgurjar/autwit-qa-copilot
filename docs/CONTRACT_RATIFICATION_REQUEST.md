# Contract ratification request → orchestrator session

**From:** `autwit-copilot-api` session
**Re:** `SKILL_CONTRACT.md` v0.1.0 (Draft — "must be ratified by both sessions before
either side builds against it")
**Date:** 2026-07-16
**Status of our build:** steps 1–7 complete. copilot-api and its UI are built and
tested end to end against fixtures — 152 tests, a real Postgres, a real browser.
**Step 8 (the swap from `FakeOrchestratorClient` to `HttpOrchestratorClient`) is the
only thing left, and it is blocked on Q1.**

Five questions. Q1 is the one that will silently break the integration. Q2 and Q3 are
`SKILL_CONTRACT` §11 items 1 and 5, restated with what now depends on them. Q4 and Q5
were found while building, and both fail in ways that are hard to see coming — Q5 in
particular is the one place where your metadata is load-bearing in our safety logic.

---

## The handover set

What accompanies this document, and — as importantly — what does not.

### You should have

| File | Why |
|---|---|
| `SKILL_CONTRACT.md` | **The contract.** Its own §0 is the rule: "This is the only shared surface between the two services. Neither side may add a dependency on the other outside this document." |
| This document | The five open questions, with test vectors for Q1. |
| `fixtures/orchestrator/*.json` (8 files) | **The contract made executable.** See below. |
| `ADR-001-reclaim-vs-reap.md` | Optional, but it is the *why* behind Q2 and Q5. If either question seems pedantic, this explains what goes wrong. |

### The fixtures are the most useful thing here

§10 already nominates them as the tiebreaker: *"if the real orchestrator diverges from
them, one of the two sides is wrong and the fixtures are the tiebreaker."* They pin the
envelope far more precisely than prose can, and we generated them with a Python
implementation of the canonical form in Q1 — so their `content_hash` values are real,
and our Java verifies every one of them on the way in.

| Fixture | Pins |
|---|---|
| `invoke_order_created.json` | 9 artifacts, 1 snapshot, `client_ref` → `parts[].artifact_ref` wiring, `subjects_discovered` |
| `invoke_ready_for_member.json` | `api_response` + `event_batch` + 14 events + `cursors_advanced` |
| `invoke_fulfilled.json` | A second snapshot with **byte-identical `part_key`s** (Q3) |
| `invoke_events_dedupe.json` | Overlapping `dedupe_hash`es with the previous — the delta-for-free mechanism |
| `invoke_partial.json` | `status: partial`, 7 of 9 parts, and the `severity: "warn"` problem in Q4 |
| `invoke_failed.json` | RFC 7807, `upstream_unavailable` |
| `invoke_slow.json` | The `deadline_exceeded` path |
| `skills_catalog.json` | `GET /skills`, including a `mutating` skill and a disabled one |

**If your `/invoke` can produce `invoke_order_created.json`, the integration works.**
That is a more useful target than reading §5 and §6 carefully.

### You should NOT have

- **`openapi.yaml`** — deliberately withheld. That is copilot-api's public API, and you
  must never call it. Invariant 1: "The orchestrator **returns** results. It never calls
  back into copilot-api." Handing you our API would invite exactly the coupling the
  design forbids, and there is no inbound surface for you even if you wanted one.
- **`V1__init.sql`, `SCHEMA_VERIFICATION.md`** — our schema. You never write to the
  `autwit` schema (§0 invariant 4); we are the only writer.
- **`BUILD_BRIEF.md`** — our build plan. Its §2 invariants are worth knowing and are
  already reflected in `SKILL_CONTRACT` §0, but §4–§9 are copilot-api's internals and
  are not requirements on you.

### What we consume, in full

Four endpoints (§1). Everything behind them is yours:

| Endpoint | What we do with it |
|---|---|
| `GET /skills` | Polled every 60s into a read-only projection. Drives our ⌘K palette's generated forms **and**, via `side_effects`, our retry safety (Q5). |
| `POST /invoke` | The main path. One utterance in, one envelope out. We enforce a hard 10m deadline. |
| `POST /skills/{name}/execute` | Same envelope, no LLM. What the palette and CI call. |
| `GET /healthz` | Liveness. |

Auth is a bearer service token, ours to send.

### Things we depend on that may not be obvious

1. **`part_key` must be emitted even when a table is empty.** An empty part and a
   missing part mean entirely different things to us; the second is a `high` finding.
2. **`meta.pk_columns` is required on every `rdbms_table`.** We refuse to guess a row
   key — the part goes inconclusive instead (§6.1, and BUILD_BRIEF §7).
3. **Keep `input_schema` enums populated.** They generate the palette's form controls
   directly: a populated enum is a select, an empty one is a text box the tester has to
   guess into.
4. **`side_effects` decides whether we auto-retry.** See Q5.
5. **`status: partial` beats a truncated body**, always (§6.1). We handle partial; we
   cannot detect truncation except via `content_hash`, which is Q1.

### Explicitly yours, not ours

§11 items 2 and 4 already say this and we agree: how you isolate skill execution,
whether you hold DB credentials or go through autwit-core, which model picks the skill,
how you read Kafka. None of it changes this contract and we have no opinion.

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

## Q4 — `severity: "warn"` does not exist. What should a partial capture raise?

**New. Found while building step 3. Small, and it fails hard.**

§5 says:

> `partial` means some artifacts landed and some didn't — copilot-api marks the
> snapshot `partial` and the run `succeeded`, then raises a `warn` finding.

There is no `warn` severity. The scale is `info | low | medium | high | critical`, in
openapi.yaml and in the DB's `finding_severity_check`. `warn` belongs to **Verdict**
(`pass | fail | warn | inconclusive`) — the two scales are conflated. §6.4's own
finding example correctly uses `high`.

**Why it matters:** an implementation that follows §5's wording sends
`severity: "warn"`, our check constraint rejects the row, and the whole persist
transaction unwinds — so *every* partial run fails. A documented, expected condition
becomes a hard error. We hit this the moment our own fixture followed §5 literally.

**What we did.** copilot-api normalises any off-scale severity to `medium` and logs
it, rather than rejecting the finding. A finding is evidence; dropping one because its
label is off-scale hides the thing the tester needs to see. Our own partial finding is
raised at `medium`.

**What we need:** confirmation of the intended severity for a partial capture (we
suggest `medium`), and a §5 wording fix from "a `warn` finding" to "a `medium`
finding". If you would rather partial be `high`, say so — we will follow. The
normalisation stays either way as a compatibility shim.

---

## Q5 — Is `side_effects` guaranteed accurate? What happens when a skill's changes?

**New. Not currently in §11. It is the one place where your metadata is load-bearing
in our safety logic, and we do not think that is visible from your side.**

### What we do with it

`GET /skills` returns `side_effects: none | mutating` per skill. We cache it and read
it at enqueue to decide `max_attempts` — which decides whether a run whose worker died
mid-execution may be **re-executed** (`docs/ADR-001-reclaim-vs-reap.md`):

| `side_effects` | `max_attempts` | What happens when a worker dies mid-run |
|---|---|---|
| `mutating` | 1 | Never reclaimed. Reaped to `timed_out`, a human decides. |
| `none` | 2 | Reclaimed and **re-executed automatically**. |

### Why this matters more than it looks

**If a mutating skill is labelled `none`, we will re-run it after a worker dies — and
place the order twice.** That is the precise failure invariant 8 and the whole
12m-lease-over-10m-timeout design exist to prevent, and at that point none of it helps:
every guard we have is downstream of trusting this field.

The reverse is merely wasteful — a read-only skill labelled `mutating` just means a
lost snapshot capture becomes manual work.

We are deliberately conservative everywhere we can be. `invoke` is always
`max_attempts: 1` because the LLM picks the skill *after* we enqueue, so we cannot
consult this field at all and assume mutating. A skill missing from our cached catalog
is also treated as mutating. But for `POST /skills/{name}/execute` we know the skill
name, and this field is the only thing standing between "reclaim safely" and "order
placed twice".

### What we need

1. **Confirmation that `side_effects` is accurate and reviewed** — that it is a
   deliberate declaration rather than a default someone fills in as `none` to make a
   YAML validate. If it is generated or optional on your side, tell us and we will stop
   trusting it (every `skill_execute` becomes `max_attempts: 1`, at the cost of never
   auto-recovering a dead worker's snapshot capture).
2. **What happens when a skill changes from `none` to `mutating`?** Our catalog is a
   60s-stale cache. If `order.reprice` becomes mutating in your repo, there is a window
   where we would still reclaim it as safe. Does `catalog_version` change on that edit
   (§2 says it changes "whenever any skill changes" — we are reading that as including
   this field)? Is a `none → mutating` transition something you would treat as a
   breaking change and coordinate, or a normal edit?
3. **Is `side_effects` closed at two values**, or might a third appear (e.g.
   `idempotent`)? We would use that: an idempotent-mutating skill could safely be
   reclaimed, which is exactly the case we currently have to be pessimistic about.

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
