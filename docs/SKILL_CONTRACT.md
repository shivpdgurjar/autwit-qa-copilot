# SKILL_CONTRACT.md

**Between:** `autwit-copilot-api` (client) and `autwit-ai-orchestrator` (server)
**Version:** 0.1.3
**Status:** Ratified by both sides. v0.1.2 accepted by copilot-api in
`message-from-qa-copilot/v1.0.5`; v0.1.3 amendments requested in the same document.

This is the only shared surface between the two services. Neither side may add a
dependency on the other outside this document.

## Changelog

- **v0.1.1** — orchestrator ratification (see `RATIFICATION_RESPONSE.md`):
  §6.1 canonical-body/content_hash **defined** (key-sorted, scale-preserving; test
  vectors reproduced in both Python and Node); §5 partial finding severity `warn` →
  **`medium`**; §8 `retryable` **not actionable for `/invoke`** (no `run_id` replay in
  v0.1); §3 replay stays SHOULD, "v0.1: does not; v0.2: MUST, ≥24h durable window".
- **v0.1.2** — `events.capture_since` is now **order-scoped**: input changed from
  `{topic, from_offset}` to `{order_id, since_producer_time?}`; it searches the Event
  Store for an order's event headers, filters by `producerTime`, and returns each
  event's contents. Environment comes from `session_context.env`. `cursors_advanced`
  carries the max `producerTime` under `order.events`/`"0"` (see §6.3). `impl_type` is
  now `http`, version `1.2.0`. `catalog_version` changes automatically.
- **v0.1.2 (re-issue)** — corrects a packaging defect in the first v0.1.2 handoff:
  the document was *labelled* 0.1.2 but its body was still the pre-ratification
  v0.1.0 text, so the ratified §3, §5, §6.1 and §8 amendments were present only in
  this changelog, never in the body. All four are now applied in place. No
  semantic change to v0.1.2 — this is the same contract, correctly rendered.
- **v0.1.3** — three fixes, all from `message-from-qa-copilot/v1.0.5`:
  1. **Topic string is `order.events`, singular, everywhere.** v0.1.2's §3 and §6.3
     gave contradictory cursor keys for the same field: §3's examples said
     `orders.events`, §6.3 said `order.events`. The executor emits the **singular**
     form, so the singular wins and §3's examples were the error. Fixed at former
     lines 118, 121 and 334. This is not cosmetic — copilot-api's
     `SessionContextBuilder.mergeCursors` keys on the raw topic string with no
     normalisation, so the two forms are independent keys that never collide and a
     stale plural cursor would be carried forever without erroring.
  2. **§6.3's event descriptor example also corrected** beyond the topic string: it
     showed `"source": "kafka"` and a Kafka-style `source_offset`, where the executor
     emits `"source": "eventstore"` and a stringified `producerTime`. Not reported by
     copilot-api — found while fixing (1). `source` is not constrained to one value;
     the example now shows what the only current emitter actually produces.
  3. **Missing `session_context.env` is now an error, not an empty success** (§3, §8).
     Previously `events.capture_since` returned a valid empty capture when `env` was
     absent, making "we could not look" indistinguishable from "we looked and found
     nothing". Only the second supports a passing verdict, so a config error that
     dropped `env` rendered as a clean green run that proved nothing. Now `400`
     `invalid_input`, `retryable: false`.
  No change to any `input_schema` or `output_schema`, so `catalog_version` is
  unchanged at `v1/3efcaf08f394` — (3) changes error behaviour, not the wire shape.

### Still open

- **v0.2 `run_id` replay.** Offered, not scheduled. copilot-api gains nothing until it
  ships, and loses nothing by waiting: `invoke` is conservative today and correct.
- **`idempotent` as a third `side_effects` value.** Same — worth doing, deferred
  deliberately rather than guessed at.

---

## 0. Invariants

| # | Invariant | Why |
|---|---|---|
| 1 | The orchestrator **returns** results. It never calls back into copilot-api. | copilot-api is the sole writer to Postgres. No inbound surface, no shared-token problem, testable with a fake. |
| 2 | The orchestrator is **stateless**. All context arrives on every call. | Lets it serve CI and the copilot from the same deployment. |
| 3 | All artifact bodies are **inline** in v0.1. | Pre-signed URI support is specced in §7 but not implemented yet. |
| 4 | The orchestrator never writes to the `autwit` schema. | One writer. |
| 5 | copilot-api enforces a **10-minute deadline** on every call. | Matches `run.lease_until = 12m` and the worker's context deadline. |

---

## 1. Endpoints

```
GET  /skills                      catalog
POST /invoke                      LLM-driven; picks and runs skills
POST /skills/{name}/execute       direct; no LLM. CI uses this.
GET  /healthz                     liveness
```

Base URL is config on the copilot-api side (`autwit.orchestrator.base-url`).
Auth: bearer service token, `Authorization: Bearer <token>`.

---

## 2. `GET /skills`

Returns the catalog. copilot-api polls this (default 60s) and caches it into
`autwit.skill` as a **read-only projection**. Skills are defined in the
orchestrator's repo as versioned YAML; nobody edits them in the DB.

**Response 200**

```json
{
  "catalog_version": "2026-07-16T09:12:00Z/a3f9c1",
  "skills": [
    {
      "skill_name": "snapshot.capture",
      "version": "1.4.0",
      "title": "Capture DB snapshot",
      "description": "Dumps all tables in a scope to artifacts.",
      "impl_type": "shell",
      "side_effects": "none",
      "input_schema": {
        "type": "object",
        "required": ["scope"],
        "properties": {
          "scope": { "type": "string", "enum": ["order_flow", "shipment_only"] },
          "label": { "type": "string" }
        }
      },
      "output_schema": { "type": "object" },
      "enabled": true
    }
  ]
}
```

`catalog_version` changes whenever any skill changes. copilot-api compares it
before re-syncing.

`input_schema` is JSON Schema and drives the ⌘K palette's form generation —
keep it accurate and keep `enum`s populated.

---

## 3. `POST /invoke`

The copilot's main path. One user utterance in, a result envelope out.

**Request**

```json
{
  "session_id": "1f0a...",
  "correlation_id": "autwit-qa2-20260716-8f3a",
  "run_id": "9c2e...",
  "message": "I created order XXXX",
  "skill_hint": null,
  "session_context": {
    "env": "qa2",
    "tester_id": "priya",
    "subjects": { "order_id": "XXXX", "member_id": "M-1234" },
    "milestones": [
      { "name": "order_created", "milestone_id": "aa..", "marked_at": "2026-07-16T09:00:00Z",
        "snapshot_id": "bb..", "event_cursor": { "order.events": { "0": 10432 } } }
    ],
    "latest_snapshot_id": "bb..",
    "event_cursors": { "order.events": { "0": 10432 } },
    "recent_steps": [
      { "seq": 4, "kind": "user_utterance", "label": "I created order XXXX" }
    ]
  },
  "deadline_ms": 600000
}
```

**Rules**

- `run_id` is generated by copilot-api and is the idempotency unit. If the same
  `run_id` is re-sent, the orchestrator SHOULD return the prior result rather
  than re-executing. Critical for `side_effects: mutating` skills.

  **v0.1: the orchestrator does NOT replay.** Invariant 2 makes the orchestrator
  stateless, and durable replay needs a `run_id` → prior-envelope store, which is
  not in v0.1. Consequently `invoke` MUST stay at `max_attempts = 1`: a dead
  worker's `invoke` reaps to `timed_out` and a human decides. This is the
  conservative default and it is correct under uncertainty.

  **v0.2: this becomes MUST.** The orchestrator will add a durable idempotency
  store keyed by `run_id` with replay-on-match over a window of **≥ 24 hours** —
  comfortably beyond the 12-minute lease, and durable across an orchestrator
  restart. (A window shorter than the lease would give the worst of both.) That
  promotes this clause from SHOULD to MUST and lets copilot-api raise `invoke` to
  `max_attempts = 2` and make reclaim safe.
- **v0.1.3: `session_context.env` is REQUIRED** for any skill that reaches an
  environment-specific system (today: `events.capture_since`, `snapshot.capture`).
  Absent or empty → `400` `invalid_input`, `retryable: false`. The orchestrator
  MUST NOT substitute a default environment and MUST NOT return an empty success.

  The rule exists because this is an evidence tool. "We looked and found no new
  events" and "we could not look" are different claims, and only the first supports
  a passing verdict. Prior to v0.1.3 a dropped `env` produced a clean empty capture
  — a green run that proved nothing, invisible precisely when it mattered. The same
  reasoning as §5's "don't silently drop this" for partial captures.
- `session_context` is the whole reason the orchestrator can be stateless. It
  carries everything needed for "capture events since the last milestone."
- `correlation_id` MUST be propagated to every downstream service the skills
  touch, as `X-Autwit-Correlation-Id` (or W3C baggage if OTel is in play).
- `deadline_ms` is advisory; copilot-api enforces its own hard timeout.

**Response 200** — see §5 for the envelope.

---

## 4. `POST /skills/{name}/execute`

No LLM. Same envelope out. This is what CI calls, and what copilot-api calls
for ⌘K palette invocations.

**Request**

```json
{
  "session_id": "1f0a...",
  "correlation_id": "autwit-qa2-20260716-8f3a",
  "run_id": "9c2e...",
  "input": { "scope": "order_flow", "label": "after_fulfilment" },
  "session_context": { "...": "same shape as §3" },
  "deadline_ms": 600000
}
```

`input` MUST validate against the skill's `input_schema`. copilot-api validates
before sending; the orchestrator validates again and returns `400` with
`code: input_schema_violation` on mismatch.

---

## 5. Result envelope

Both `/invoke` and `/execute` return this shape.

```json
{
  "run_id": "9c2e...",
  "status": "succeeded",
  "started_at": "2026-07-16T09:14:02Z",
  "ended_at": "2026-07-16T09:15:41Z",
  "duration_ms": 99000,

  "invocations": [
    {
      "skill_name": "snapshot.capture",
      "skill_version": "1.4.0",
      "input": { "scope": "order_flow" },
      "status": "succeeded",
      "exit_code": 0,
      "duration_ms": 97400,
      "output_inline": { "part_count": 9, "total_rows": 143 }
    }
  ],

  "artifacts": [ /* §6 */ ],
  "snapshots": [ /* §6.2 */ ],
  "events":    [ /* §6.3 */ ],
  "findings":  [ /* §6.4 */ ],
  "notes":     [ { "at": "2026-07-16T09:15:40Z",
                   "text": "Captured 9 tables. orders.total_amount changed from 1200.00 to 1450.00 since order_created." } ],

  "subjects_discovered": { "shipment_id": "SHP-99" },
  "milestone": null,
  "error": null
}
```

**Field notes**

- `status` ∈ `succeeded | failed | partial`. `partial` means some artifacts
  landed and some didn't — copilot-api marks the snapshot `partial` and the run
  `succeeded`, then raises a **`medium`** finding. Don't silently drop this.
  (There is no `warn` severity. `warn` is a **Verdict** value; the severity scale
  is `info | low | medium | high | critical` — see §6.4. The orchestrator emits
  `medium`.)
- `notes[]` is the "keeps telling about current analysis" channel. copilot-api
  persists each as a `step(kind=analysis)` and emits `analysis.note` over SSE.
  Renders in chat, not the timeline.
- `subjects_discovered` gets merged into `session.subjects`. This is how a
  shipment id found during snapshot capture becomes searchable later.
- `findings[]` — the orchestrator may raise findings directly (e.g. a skill
  detects a missing event). Comparison findings are generated by copilot-api's
  own diff engine, not here.

---

## 6. Payload shapes

### 6.1 Artifact descriptor

```json
{
  "client_ref": "a1",
  "artifact_type": "rdbms_table",
  "source_system": "oms_pg",
  "logical_name": "orders",
  "format": "json",
  "body": [ { "order_id": "XXXX", "status": "CREATED", "total_amount": "1200.00" } ],
  "row_count": 1,
  "content_hash": "sha256:9f86d0...",
  "meta": {
    "pk_columns": ["order_id"],
    "query": "select * from orders where correlation_id = $1",
    "masked_columns": ["customer_email"],
    "ignore_columns": ["created_at", "updated_at", "trace_id"]
  }
}
```

- `client_ref` is a within-response handle so `snapshots[].parts[]` can point at
  artifacts in the same payload before real UUIDs exist. copilot-api assigns
  the `artifact_id`.
- `body` shape follows `format`: parsed JSON for `json`, a string for
  `xml`/`text`/`csv`/`html`/`md`, base64 for `binary`.
- `content_hash` is `"sha256:"` followed by the lowercase hex sha256 of the
  **canonical body**, where canonical is defined as:

  | `format` | Canonical bytes |
  |---|---|
  | `json`, `jsonb` | The UTF-8 encoding of the JSON text with object keys sorted lexicographically **by Unicode code point at every nesting level**, no insignificant whitespace, **array order preserved**, and numbers serialised with **exactly the scale they were received with** (`1200.00` stays `1200.00`, not `1200.0`). |
  | `xml`, `text`, `csv`, `html`, `md` | The UTF-8 encoding of the string, byte for byte, with **no normalisation of any kind** — no trimming, no newline normalisation, no XML canonicalisation. |
  | `binary` | The raw bytes **after** base64-decoding the transport encoding. The hash never covers the base64 text. |

  Two rules carry the weight:

  1. **Lexicographic key sorting.** This is the only rule that lets a Python or
     Node implementation agree with a JVM one without sharing code. It is *not*
     Postgres `jsonb`'s ordering (which sorts by length, then bytes) — do not
     derive the hash from a `jsonb` round-trip.
  2. **Scale is preserved, never normalised.** `1200.00` and `1200.0` are the
     same number and different evidence. Any language whose JSON parser lands
     numbers in a float will silently destroy this (the JVM fix is Jackson's
     `USE_BIG_DECIMAL_FOR_FLOATS`; the Node fix is to canonicalise from the
     original number token rather than a parsed float). This looks like a hash
     bug but is a number bug.

  The governing idea is **hash exactly what was sent** — an artifact is evidence,
  and normalising evidence hides the diffs this product exists to find.

  Implementations MUST reproduce the six test vectors in
  `CONTRACT_RATIFICATION_REQUEST.md` Q1. **If either side's fixtures drift, the
  test vectors — not the fixtures — are the tiebreaker.**

  copilot-api recomputes and rejects on mismatch — this catches truncation.
- `meta.pk_columns` is **required** for `rdbms_table`. The diff engine cannot
  do a key-wise join without it.
- `meta.ignore_columns` is surfaced in the diff UI. It is never applied
  silently.

**Size:** v0.1 is inline-only. Keep any single artifact body under **8 MB** and
the whole response under **32 MB**. If a scope would exceed this, return
`status: partial` with a finding rather than a truncated body. §7 removes this
limit.

### 6.2 Snapshot descriptor

```json
{
  "client_ref": "s1",
  "label": "after_order_created",
  "scope": "order_flow",
  "scope_def": { "tables": ["orders", "order_items", "..."], "resolved_at": "2026-07-16T09:14:02Z" },
  "status": "complete",
  "parts": [
    { "part_key": "oms.orders",        "artifact_ref": "a1" },
    { "part_key": "oms.order_items",   "artifact_ref": "a2" },
    { "part_key": "shipment.legs",     "artifact_ref": "a7" },
    { "part_key": "dynamo.order_doc",  "artifact_ref": "a9" }
  ]
}
```

**`part_key` must be stable across snapshots of the same scope.** This is the
single most important field in the contract: comparison is a key-wise join on
`part_key`. If `order_flow` yields `oms.orders` at step 2, it must yield
`oms.orders` at step 5 — same string, always, even if the table is empty.

### 6.3 Event descriptor

```json
{
  "source": "eventstore",
  "topic": "order.events",
  "event_type": "OrderFulfilled",
  "event_key": "XXXX",
  "source_offset": "1703000009000",
  "occurred_at": "2026-07-16T09:15:03Z",
  "payload": { "orderId": "XXXX", "status": "FULFILLED" },
  "dedupe_hash": "sha256:c1d2..."
}
```

- **v0.1.3:** `source` and `source_offset` are per-emitter, not fixed. For
  `events.capture_since` — the only emitter today — `source` is `"eventstore"` and
  `source_offset` is the **stringified `producerTime` (epoch millis)**, not a Kafka
  offset. Do not parse `source_offset` as an integer offset or assume it is
  monotonic across sources; treat it as an opaque per-source ordering token.
- `dedupe_hash` MUST be deterministic over `(source, topic, source_offset)` when
  offsets exist, else over the canonical payload. copilot-api inserts with
  `ON CONFLICT (session_id, dedupe_hash) DO NOTHING`. (For `events.capture_since`
  the orchestrator hashes `(source, event_id, producer_time)` — the eventId is the
  stable unique offset.)
- **This is how "events since step 2" works:** the orchestrator re-reads from
  the cursor in `session_context.event_cursors` and returns everything it sees.
  copilot-api's unique constraint makes the delta emerge for free. The
  orchestrator does not need to compute the delta itself.
- Raw batches SHOULD also be returned as an `event_batch` artifact for audit.
- New cursors go in `cursors_advanced`:

```json
"cursors_advanced": { "order.events": { "0": 1703000009000 } }
```

**v0.1.2 — `events.capture_since` cursor semantics (order-scoped).** The cursor value
is the max `producerTime` (epoch millis) seen this run, under key `order.events`,
partition `"0"`. copilot-api stores it in `session_context.event_cursors` and passes
it back as `since_producer_time` on the next call — that is the "since". The
orchestrator returns every event with `producerTime > since_producer_time`; the
`dedupe_hash` still makes overlaps idempotent for free.

### 6.4 Finding descriptor

```json
{
  "severity": "high",
  "category": "missing_event",
  "part_key": "oms.orders",
  "entity_key": "XXXX",
  "field": null,
  "before_value": null,
  "after_value": null,
  "message": "No OrderFulfilled event observed within 60s of status flip to FULFILLED."
}
```

---

## 7. Deferred: pre-signed URIs (v0.2, NOT NOW)

When inline bodies stop scaling, an artifact descriptor may instead carry:

```json
{
  "client_ref": "a1",
  "artifact_type": "rdbms_table",
  "logical_name": "orders",
  "format": "json",
  "external_uri": "s3://autwit/tmp/9c2e/a1.json",
  "size_bytes": 14000000,
  "content_hash": "sha256:..."
}
```

`body` and `external_uri` are mutually exclusive (mirrors the DB's `one_body`
check constraint). copilot-api either copies to permanent storage or stores the
reference.

**Build the `ArtifactDescriptor` type with `external_uri` present but unused
today.** It costs nothing now and avoids a contract renegotiation later.

---

## 8. Errors

RFC 7807. Any non-2xx:

```json
{
  "type": "https://autwit/errors/skill-execution-failed",
  "title": "Skill execution failed",
  "status": 500,
  "code": "skill_execution_failed",
  "detail": "snapshot.capture exited 3: connection refused to shipment_pg",
  "instance": "/invoke",
  "run_id": "9c2e...",
  "skill_name": "snapshot.capture",
  "retryable": false
}
```

**Codes copilot-api handles specially**

| code | copilot-api behaviour |
|---|---|
| `input_schema_violation` | run → `failed`. Never retry. Bug in the palette or the LLM. |
| `invalid_input` | run → `failed`, `retryable: false`. Includes **missing `session_context.env`** (§3, v0.1.3). A config/enqueue bug — no retry will fix it. |
| `skill_not_found` | run → `failed`. Trigger a catalog re-sync. |
| `skill_disabled` | run → `failed`. Re-sync catalog. |
| `confirmation_required` | run → `failed`, `code` surfaced to UI as a confirm prompt. |
| `upstream_unavailable` | run → `failed`, `retryable: true`. UI offers retry. |
| `deadline_exceeded` | run → `timed_out`. **Never auto-retry.** |

`retryable` is advisory. copilot-api **never auto-retries a `mutating` skill**
regardless of this flag — `max_attempts` stays 1. A human clicks retry.

**`retryable` is not actionable for `/invoke`.** The orchestrator does not
replay `run_id` in v0.1 (§3), so it cannot dedupe a re-sent `invoke`. copilot-api
MUST therefore never auto-retry an `invoke` — on any code, `retryable: true`
included. The flag is a hint for humans and for `/execute` on `side_effects: none`
skills only. This constraint lifts when §3's v0.2 idempotency store lands.

---

## 9. Timeouts and cancellation

```
copilot-api HTTP client timeout   10m   (hard)
run.lease_until                   12m   (must exceed the client timeout)
reaper sweep                      60s
```

The lease outliving the client timeout is deliberate: if they were equal, a
slow-but-alive run would get reclaimed and re-executed while still running.
With mutating skills that is an order-placed-twice bug.

On client-side timeout, copilot-api cancels the request, marks the run
`timed_out`, releases the advisory lock, and NOTIFYs. `timed_out` ≠ `failed`:
it means **outcome unknown**, so the UI says "may have partially completed;
verify before retrying."

Cancellation is cooperative and best-effort. There is no `DELETE /invoke`. If a
run is cancelled client-side, the orchestrator may still be working; copilot-api
discards the result if it arrives after the run went terminal.

---

## 10. Fake implementation

copilot-api ships `FakeOrchestratorClient` (`@Profile("fake")`) replaying
fixtures from `autwit-copilot-api/src/main/resources/fixtures/orchestrator/`
(main rather than test: the fake profile has to be runnable, not just testable —
the UI is developed against it). It must cover:

| Fixture | Exercises |
|---|---|
| `invoke_order_created.json` | 9 artifacts, 1 snapshot, subjects_discovered |
| `invoke_ready_for_member.json` | api_response + event_batch + 14 events + cursors_advanced |
| `invoke_fulfilled.json` | second snapshot with **identical part_keys** |
| `invoke_events_dedupe.json` | overlapping dedupe_hashes with the previous fixture |
| `invoke_partial.json` | `status: partial`, 7 of 9 parts, `medium` finding |
| `invoke_failed.json` | RFC 7807, `upstream_unavailable` |
| `invoke_slow.json` | sleeps past the deadline → `timed_out` path |
| `skills_catalog.json` | `GET /skills`, incl. a `mutating` skill and a disabled one |

**Everything in the build plan except the final swap is testable against these.**
The fixtures are the contract's executable form — if the real orchestrator
diverges from them, one of the two sides is wrong and the fixtures are the
tiebreaker.

**One carve-out (v0.1.1, §6.1):** for `content_hash` the **Q1 test vectors** are the
tiebreaker, not the fixtures. Each side generates its fixtures with its own hasher,
so the fixtures agree with whatever that hasher got wrong; only the vectors are
independent of both implementations.

**Fixture currency:** `skills_catalog.json` is generated from the orchestrator's
`SkillRegistry`, never hand-edited, and is asserted equal to the live catalog in CI.
A hand-edited `catalog_version` is how a skill change reaches copilot-api with a
version that compares equal and gets silently skipped by `SkillCatalogSync`.

---

## 11. Resolved items

All eight questions copilot-api raised at ratification are answered. Each is kept in
**Asked / Resolved** form rather than deleted: the questions are the record of *why*
the contract says what it does, and that provenance has already earned its keep — it
is what let both sides diagnose the v0.1.2 body/changelog split. Nothing in this
section is open. The authoritative answers live in the body sections cited; this is
history, not a worklist.

1. **Idempotency replay on `/invoke`**
   **Asked:** Does `/invoke` support `run_id` idempotency replay? If not, copilot-api
   must never retry, and §8's `retryable` becomes meaningless.
   **Resolved (§3, §8):** v0.1 does **not** replay — invariant 2 makes the orchestrator
   stateless and durable replay needs a `run_id` → prior-envelope store that v0.1 does
   not have. So `invoke` stays at `max_attempts = 1` and `retryable` is explicitly not
   actionable for `/invoke`. v0.2 adds a durable store with a **≥24h** replay window,
   promoting the clause to MUST.

2. **DB credential ownership**
   **Asked:** Does the orchestrator hold DB credentials for OMS/shipment Postgres, or
   go through an autwit-core query service? Changes who owns snapshot scope definitions.
   **Resolved:** The orchestrator holds them directly. Scope definitions are therefore
   orchestrator-owned — see item 3.

3. **Where snapshot scopes live**
   **Asked:** Where do snapshot scopes (`order_flow` = which 9 tables) live? Assumed
   orchestrator repo, exposed via the `scope` enum in `snapshot.capture`'s `input_schema`.
   **Resolved:** Confirmed as assumed — orchestrator repo, surfaced through the `scope`
   enum.

4. **Skill execution isolation**
   **Asked:** Container per invocation, or trusted scripts with schema-validated args?
   copilot-api doesn't care, but it changes realistic `deadline_ms` values.
   **Resolved:** Trusted, schema-validated operations — not container-per-invocation.
   Realistic budgets: reads complete in **seconds**, `order.place` in **minutes**.

5. **`part_key` ownership and stability**
   **Asked:** Is `part_key` naming owned by the scope definition? It must be, and must
   be stable — the diff engine breaks silently if it drifts.
   **Resolved:** Confirmed explicitly. `part_key` is owned by the scope definition and
   is stable; see §6.2's guarantee that a scope yields the same `part_key` strings on
   every run, even for empty tables.

6. **Definition of "canonical body" for `content_hash`**
   **Asked:** What exactly does §6.1's `content_hash` cover? sha256 is unambiguous; what
   you feed it is not. Undetectable before step 8, because each side generates fixtures
   with its own hasher, so the fixtures agree with whatever each side got wrong — and a
   divergence as small as key ordering or a trailing zero fails *every* artifact.
   **Resolved (§6.1):** Ratified verbatim from `CONTRACT_RATIFICATION_REQUEST.md` Q1 and
   written into the body — canonical form defined per `format`, with lexicographic
   code-point key sorting and scale preservation as the two load-bearing rules. The **Q1
   test vectors, not either side's fixtures, are the tiebreaker** (§10 carve-out).

7. **`warn` is not a severity**
   **Asked:** §5 said a partial capture raises a `warn` finding, but the severity scale
   is info/low/medium/high/critical — `warn` is a *Verdict* value. An implementation
   following §5's wording sends `severity: "warn"`, the DB's `finding_severity_check`
   rejects it, and every partial run fails.
   **Resolved (§5):** §5 now specifies **`medium`**, with the Verdict-vs-Severity
   distinction stated inline so the confusion cannot recur. copilot-api additionally
   normalises off-scale severities to `medium` rather than dropping the finding.

8. **Accuracy and change semantics of `side_effects`**
   **Asked:** Is §2's `side_effects` guaranteed accurate, and what happens when it
   changes? copilot-api reads it at enqueue to decide whether a run may be re-executed
   after its worker dies (ADR-001) — a mutating skill mislabelled `none` gets
   auto-retried, the order-placed-twice bug invariant 8 exists to prevent, arriving
   through the one door none of the guards watch. The catalog is also a 60s-stale cache,
   so a `none → mutating` edit has a window.
   **Resolved (§2):** `side_effects` is a **reviewed declaration**, not inferred. A
   `none → mutating` transition is a coordinated breaking change — never shipped as a
   silent catalog edit — which closes the stale-cache window. The enum stays at two
   values for v0.1.
