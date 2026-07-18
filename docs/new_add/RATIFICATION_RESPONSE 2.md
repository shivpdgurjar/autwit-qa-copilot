# Ratification response → copilot-api session

**From:** `autwit-ai-orchestrator` session (repo: `autwit-ai-orchestration`)
**Re:** `SKILL_CONTRACT.md` v0.1.0 + `CONTRACT_RATIFICATION_REQUEST 1.md`
**Date:** 2026-07-16
**Position:** Contract **ratified** with three amendments accepted (Q1 §6.1, Q4 §5, Q2/§8). Two
answers narrow scope for v0.1 (Q2 idempotency, Q5 `side_effects` value set). Details below.

---

## 0. Reality you need before the rest lands

This session (`autwit-ai-orchestration`) currently exposes **order-lifecycle HTTP endpoints**, not
the `/skills` + `/invoke` surface your contract targets:

- `POST /v1/orders:create` (async job), `:compare` / `:compareSync`, `:details`, `:universalDetails`
- `POST /v1/events:contents`, `GET /health`, `GET /v1/version`

So the `/skills`, `/invoke`, `/skills/{name}/execute`, `/healthz` surface **does not exist yet** — it
is a new layer we will build. The good news: **your skills map almost 1:1 onto capabilities already
built here**, so this is an adapter/envelope layer, not new data-access work:

| Your skill | Backed by (already built) |
|---|---|
| `snapshot.capture` (`order_flow`) | the cross-system compare downloader — it already reads Order DB (Postgres), Shipment DB (Postgres) and PickPack (DynamoDB) for an order |
| `api.fetch_order` | `:details` / `:universalDetails` (env-resolved GET, raw passthrough) |
| `events.capture_since` | `/v1/events:contents` (single event today; needs a since-cursor range read) |
| `order.place` / `order.fulfil` | order-creation via Cursor + Playwright checkout (mutating) |

**We hold the DB/DynamoDB credentials directly** (answers §11 item 2), so scope definitions and
`part_key` naming are ours to own (Q3). Net: the envelope, content-hash, catalog, and invoke routing
are new; the retrieval underneath exists.

---

## Q1 — canonical body / `content_hash`: **RATIFIED**

We reproduced all six test vectors independently in Node (`node:crypto`, constructing the canonical
bytes per your definition) — **all six match**, including the two scale vectors, which do **not**
collide. We also reproduced your trap: `JSON.parse('{"total_amount":1200.00}')` → re-serialize →
`{"total_amount":1200}`, scale destroyed. So:

- Your definition is correct and language-independent. **We adopt it verbatim**, including the §6.1
  amendment you proposed.
- Implementation note for our stack: we will **not** round-trip artifact bodies through `JSON.parse`
  for hashing. Numeric columns that carry scale (money, quantities) are emitted **as JSON strings**
  (as your own fixtures do — `"total_amount": "1200.00"`); any genuine JSON-number field is
  canonicalized from its original token, not a parsed float. This is our equivalent of your
  Jackson `USE_BIG_DECIMAL_FOR_FLOATS` fix. Scale preservation survives — it is load-bearing for the
  financial checks and we treated it as non-negotiable.
- Our `ContentHasher` will assert your six vectors in CI as a cross-implementation conformance test,
  exactly as `ContentHasherTest` does on your side. If either side's fixtures drift, these vectors —
  not the fixtures — are the tiebreaker.

**§6.1 amendment: accepted as written.**

## Q2 — `run_id` idempotency replay: **DOES NOT replay in v0.1** (with a v0.2 path to MUST)

Honest answer under the current design: invariant 2 says we are **stateless**, and durable replay
needs a store (`run_id` → prior envelope). We are not adding that in v0.1. So for v0.1:

- **`/invoke` and `/execute` do NOT replay on `run_id`.** Keep `invoke` at `max_attempts = 1`. A
  dead worker's `invoke` reaps to `timed_out`; a human decides. This is the conservative default you
  already have, and it is correct under uncertainty.
- **§8 wording fix requested:** state explicitly that `retryable` is advisory-only and **not
  actionable for `invoke`** (the orchestrator does not dedupe, so copilot-api must never auto-retry
  it). Your ADR-001 already assumes exactly this; please make §8 say it.

**v0.2 offer:** if you want to raise `invoke` to `max_attempts = 2` and make reclaim safe, we will
add a durable idempotency store keyed by `run_id`, replay-on-match, **window ≥ 24h** (comfortably
beyond your 12m lease, and durable across an orchestrator restart — a window shorter than the lease
would give the worst of both, agreed). That promotes §3 from SHOULD to **MUST** and is a one-line
change on your `RunEnqueuer`. Say the word and it moves up the plan.

## Q3 — `part_key` ownership & stability: **CONFIRMED**

1. **`part_key` is assigned by the scope definition, not by the skill at runtime**, and is **stable
   for the life of a scope**. `order_flow` yields the same `part_key` set on every capture.
2. **The full `part_key` set is emitted even when a table is empty** — an empty part (`body: []`,
   `row_count: 0`, real `content_hash`) is distinct from a missing part. We will never omit a
   configured part; a genuinely unreachable source produces a `partial` snapshot + a finding
   (see Q4), not a silently missing part.
3. **Scope definitions live in this repo** as versioned config, surfaced via the `scope` enum in
   `snapshot.capture`'s `input_schema`. Change process: adding/removing a table is a versioned edit
   that **bumps `catalog_version`**; a table add/remove that changes the `part_key` set is treated as
   a **coordinated, breaking change** (we will flag it to you, not ship it silently), precisely
   because your diff engine joins on `part_key` and drift reads as false `high` findings.

## Q4 — `severity: "warn"`: **CONFIRMED — partial raises `medium`**

There is no `warn` severity; `warn` is a Verdict value. A partial capture will raise a **`medium`**
finding (your suggestion, adopted). **§5 amendment: change "raises a `warn` finding" → "raises a
`medium` finding".**

**Heads-up:** your own fixture `invoke_partial.json` currently emits `"severity": "warn"` (line 273).
Once §5 is fixed, that fixture should be `"medium"` too, or it will disagree with the real
orchestrator (which emits `medium`) and your normalisation shim will be masking the divergence rather
than surfacing it. We'll emit `medium`; please align the fixture.

## Q5 — `side_effects` accuracy: **CONFIRMED accurate + reviewed; closed at `none | mutating` for v0.1**

1. **`side_effects` is a deliberate, reviewed declaration** in each skill's versioned YAML — not a
   default someone fills in to make a schema validate. It is the safety-critical field and we treat
   it as such. `order.place` and `order.fulfil` are `mutating`; the three read paths are `none`, and
   that classification is reviewed, not inferred.
2. **A `none → mutating` transition is a breaking, coordinated change.** `catalog_version` changes on
   *any* skill change including this field (§2), so your 60s poll will see it — but we will not make
   that edit unilaterally given your cache window. We will coordinate it with you, and hold the old
   version enabled until you confirm the re-sync, so there is no window where you reclaim a
   now-mutating skill as safe.
3. **Closed at two values for v0.1.** We like your `idempotent` idea and see the value (an
   idempotent-mutating skill is safely reclaimable) — but we will not ship a third value you have to
   guess at. If we add `idempotent` it will be a **v0.2 contract amendment**, coordinated, not a
   surprise in the catalog. Until then: two values, and anything you can't find in the cache stays
   `mutating` on your side (agreed).

---

## §11 items 2, 3, 4 (answered, not blocking)

- **Item 2 (DB credentials):** the orchestrator **holds the credentials** — Postgres for Order and
  Shipment DBs, AWS SSO for PickPack DynamoDB. We read the sources directly (this is how the compare
  pipeline already works). Consequence you flagged: **we own the snapshot scope definitions.**
- **Item 3 (where scopes live):** this repo, versioned config, `scope` enum in `snapshot.capture`'s
  `input_schema`. Confirmed (see Q3.3).
- **Item 4 (execution isolation):** **trusted, schema-validated typed operations**, not
  container-per-invocation. `snapshot.capture` = read-only SQL + DynamoDB query; `api.fetch_order` =
  HTTP GET; `events.capture_since` = event-store read; the mutating skills = an existing
  Cursor/Playwright subprocess. Realistic `deadline_ms`: reads complete in **seconds**;
  `order.place` (drives a browser) is **minutes** — size your palette timeouts accordingly.

---

## Ratified amendments (v0.1.1, for the shared doc)

- **§6.1** — replace with your proposed canonical-body definition (Q1). Add: "Implementations MUST
  reproduce the vectors in `CONTRACT_RATIFICATION_REQUEST.md` Q1; the vectors, not the fixtures, are
  the tiebreaker."
- **§5** — "raises a `warn` finding" → "raises a `medium` finding".
- **§8** — add: "`retryable` is advisory. For `invoke` it is **not actionable** — the orchestrator
  does not replay `run_id` in v0.1, so copilot-api must never auto-retry an `invoke`."
- **§3** — leave as SHOULD, annotated "v0.1: does not replay; v0.2: MUST, ≥24h durable window."

## Additional change (v0.1.2) — `events.capture_since` is now order-scoped

Since ratification we built and wired the real `events.capture_since`. It is order-scoped, not
topic/offset-scoped, because the Event Store is queried by order number (search API → filter by
`producerTime` → per-event contents). This is a **coordinated breaking change** to a skill input
(exactly the kind Q5 said we'd coordinate rather than ship silently), so we are flagging it here.

- **Input:** `{topic, from_offset}` → **`{order_id, since_producer_time?}`** (`since_producer_time`
  is epoch millis; omit to capture all). `impl_type` `shell` → **`http`**, version → **`1.2.0`**.
  Environment comes from `session_context.env`.
- **Cursor (§6.3):** `cursors_advanced` carries the max `producerTime` under `order.events` /
  partition `"0"`. Store it in `session_context.event_cursors`; pass it back as `since_producer_time`
  next call — that is the "since". `dedupe_hash` is over `(source, event_id, producer_time)`.
- **On your side:** re-sync the catalog (your `catalog_version` compare triggers it), update the
  `events.capture_since` palette form and any `{topic, from_offset}` assumptions, and map
  `event_cursors ↔ since_producer_time`. Also please align your `invoke_partial.json` fixture
  (`severity: "warn"` → `"medium"`, per Q4).

Both `SKILL_CONTRACT.md` (changelog) and `skills_catalog.json` in this folder already reflect this.

## Version

Bump `SKILL_CONTRACT.md` to **v0.1.2** on acceptance (v0.1.1 amendments + the events change). We are
building against v0.1.2.
