# SKILL_CONTRACT.md

**Between:** `autwit-copilot-api` (client) and `autwit-ai-orchestrator` (server)
**Version:** 0.1.8
**Status:** **Closed.** Both sides' closeout items are complete — B1–B7 (orchestrator) and
C1–C5 (copilot-api), with C6/C7/C8 recorded as deliberate holds. v0.1.8 adds the
null-bearing hash vectors V7/V8, the last outstanding request from either side.

> **Verification status — how each section is known to be true.** This document's body
> descends from the pre-ratification **v0.1.0** text, so age is not evidence. What
> matters is whether a section has been read **against the implementation**:
>
> | Sections | Status |
> |---|---|
> | §1, §2, §4, §5, §6.2, §8, §9 | **Audited against orchestrator code** (v0.1.5–v0.1.6) |
> | §0 | **Audited** — invariants 1–4 by the orchestrator, 5 by copilot-api (v0.1.6). One scoping caveat, stated in §0. |
> | §10 | **Audited against copilot-api code** (v0.1.4, confirmed by them in v1.0.9 §4) |
> | §3, §6.1, §6.3, §11 | Amended and ratified by both sides |
> | **§7** | **Parked, with one live rule.** The `body`/`external_uri` mutual exclusion is enforced and tested (v0.1.7). The rest is v0.2 design describing no behaviour on either side, so there is nothing to read it against. See the banner in §7. |
>
> **Every normative claim in this document that describes behaviour existing today has
> been read against the code that implements it.** What remains unverified in §7 is
> unverifiable rather than unchecked — it describes a v0.2 feature neither side has built.
>
> A document-to-document diff cannot establish any of this. copilot-api diffed all seven
> previously-unverified sections in `v1.0.9` and found them **byte-identical** — while
> §10 had been wrong in *both* copies simultaneously for four versions. Two descendants
> of the same v0.1.0 cannot detect what both inherited; identical-and-stale is
> indistinguishable from identical-and-correct. Only reading a section against the code
> that implements it finds shared staleness, and only the side owning that code can do it.

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
- **v0.1.4** — §10 corrected from `message-from-qa-copilot/v1.0.7` §2a. §10 describes
  copilot-api's implementation, so copilot-api is the authority on it:
  1. Fixture path is **`autwit-copilot-api/src/main/resources/fixtures/orchestrator/`**
     — `main`, not `test`. Load-bearing: the fake profile must be *runnable*, not just
     testable, because the UI is developed against it, and `test/resources` is not on
     the runtime classpath. A reader following the old path would build a fake that
     cannot start. All 8 fixtures are in `main/resources`; the `test` path does not exist.
  2. `invoke_partial.json` row restored to note the **`medium`** finding — its omission
     quietly undid part of the Q4 ratification at the place a reader is most likely to
     check what that fixture is for.
  3. `skills_catalog.json` row restored to note it includes a `mutating` skill and a
     disabled one.

  **This was not a v0.1.3 regression.** All three were wrong in the **v0.1.0** body and
  survived every version since, because §10 was never one of the amended sections. That
  is why the verification-status table above exists (a provenance warning in v0.1.4–v0.1.5,
  superseded once sections began to be audited): the defect class is "unamended sections
  may still carry v0.1.0 content", and §10 was the first confirmed instance of it — six
  more turned up in the v0.1.5 audit. `catalog_version` unchanged — no schema touched.
- **v0.1.5** — first **code audit** of the orchestrator-owned sections, per
  `message-from-qa-copilot/v1.0.9` §3: every claim in §1, §2, §4, §5, §6.2, §8 and §9
  read against the implementation rather than against copilot-api's copy. Six defects,
  none of which a document diff could have found, because all were identical in both
  copies:
  1. **§2 `catalog_version` example** was `2026-07-16T09:12:00Z/a3f9c1` — a format the
     generator has never emitted. Real format is `v1/<12-hex>`, a content hash. This is
     the same literal whose staleness caused the skipped re-sync in v1.0.3 §2.
  2. **§2 "versioned YAML"** — there is no YAML. Skills are versioned TypeScript
     (`src/skills/catalog.ts`).
  3. **§5 envelope example** omitted `cursors_advanced`, normative in §6.3 and emitted
     by `events.capture_since` since v0.1.2.
  4. **`deadline_ms` removed** (§3, §4) — the orchestrator never read it. It advertised
     a capability that does not exist. copilot-api's client timeout is unaffected and
     remains the only enforcement; extra JSON properties are ignored, so senders do not
     break.
  5. **`deadline_exceeded` removed** (§8) — never emitted, because nothing enforces a
     deadline here. §8 was telling copilot-api how to handle a code it can never
     receive. §9 now states the limitation outright.
  6. **§6.2 `part_key` stability is now pinned by a test**, verified to fail when the
     key is made to vary. Previously the guarantee held only because the demo scope is
     a hardcoded constant — true by accident, not by construction.

  7. **`snapshot.capture` no longer accepts a scope it does not implement.**
     `shipment_only` passed schema validation and returned an `order_flow` snapshot
     labelled `order_flow`; it now returns `400` `invalid_input`. See §6.2.

  Still open and **not** fixed here: an empty auth token disables authentication
  entirely while §1 states bearer auth unconditionally — it fails **open**, and the
  decision is deployment policy as much as contract. See `message-to-qa-copilot/v1.0.10`
  §4b. No schema changed, so `catalog_version` remains `v1/3efcaf08f394`.
- **v0.1.6** — closes the `message-from-qa-copilot/v1.0.11` closeout spec, items B1–B7:
  1. **B1** — `deadline_exceeded` documented as **retired here, retained by copilot-api**
     for its own client-side timeout (§8). They synthesise it into `run.error()` to
     distinguish a worker's deadline from the reaper's `lease_expired`; keeping their name
     was the right call, so the union simply notes which side emits it.
  2. **B2** — `shipment_only` dropped from `scope`'s enum. **`catalog_version` moves to
     `v1/279960341625`** — the correct outcome, not a cost: a schema changed, so the
     version should. *One deviation from the stated acceptance, see below.*
  3. **B3** — auth **fails closed** (§1), with an explicit `AGENTIC_SKILLS_ALLOW_UNAUTHENTICATED`
     dev opt-in. `/healthz` documented as deliberately unauthenticated.
  4. **B4** — §0 invariants 1–4 audited against orchestrator code; 5 confirmed by
     copilot-api. One scoping caveat recorded in §0.
  5. **B5** — the three demo fixtures regenerated. The two event fixtures are now
     *generated from the real executor* (§10), not hand-written.
  6. **B6** — §8 gains an `emitted by` column; `confirmation_required` and
     `deadline_exceeded` are copilot-emitted in whole or part.
  7. **B7** — §7 explicitly parked.

  **Deviation on B2's acceptance criterion.** It asked that `shipment_only` still return
  `400 invalid_input` if sent directly. With the value removed from the enum, **schema
  validation now rejects it first, so the code is `input_schema_violation`, not
  `invalid_input`.** Both are `400` and both are terminal-never-retry, but the code
  differs from the spec. This is the more accurate code — the input genuinely violates
  the schema — so it was not worked around. The executor's `invalid_input` guard is kept
  and tested directly as defence in depth, since it is what prevents a silent
  `order_flow` capture if the enum is ever widened ahead of the implementation again.
  **Flagged for copilot-api to accept or object.**
- **v0.1.7** — §7's `body` / `external_uri` mutual exclusion is **enforced** rather than
  merely stated. It was the only asked-for behaviour in §7 that had never been
  implemented: `ArtifactDescriptor` declared both fields as independently optional and
  nothing checked exactly-one-of, so the rule held only because `makeArtifact` is the
  single construction path and always sets `body`. `assertOneBody` now enforces it at
  construction — before hashing, so an absent body names the rule it broke instead of
  failing inside the hasher with an opaque `TypeError` — and is exported for v0.2's
  externalisation path to reuse. `null` counts as a present body. Pinned by five tests,
  verified to fail when the guard is neutered. Done now, while one construction path
  exists and the rule cannot be broken, rather than during v0.2 when it can.
  No schema changed; `catalog_version` remains `v1/279960341625`.
- **v0.1.8** — §6.1 gains **rule 3 (a null is content, absence is a different claim)** and
  **vectors V7/V8**, at copilot-api's request in `message-from-qa-copilot/v1.0.13` §3a.

  The prompt was a live defect on their side: their `ContentHasher` inherited
  `default-property-inclusion: non_null` from the application `ObjectMapper` it copies, so
  a body carrying `"correlationId": null` hashed as though the key were absent. It would
  have reached production — their real client uses `readValue`, which never serialises, so
  nulls arrive intact and would have been dropped at hash time, rejecting every artifact
  with a null field.

  **The part worth recording is why nothing caught it.** Three things had to hold at once,
  and did: each side only ever checked its own hasher against its own fixtures, so the
  fixtures agreed with whatever the hasher got wrong (§10 names this trap); their own unit
  test built a *cleaner* mapper than production, omitting the very setting that caused the
  bug; and **the six Q1 vectors — the designated tiebreaker — contained no null at all.**
  The tiebreaker exists so two implementations cannot agree with each other's bugs, and a
  gap in it is a gap in that guarantee.

  What broke the deadlock was generating fixtures from the real executor (v0.1.6, B5)
  rather than writing them: their declared hashes stopped agreeing with anyone's
  assumptions and started agreeing only with the definition. A generated fixture found a
  defect neither side's tests could.

  `catalog_version` unchanged.

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

**v0.1.6 — all five invariants are now audited against code**, 1–4 by the orchestrator
and 5 by copilot-api (`message-from-qa-copilot/v1.0.11` §A2).

| # | Verified by | Evidence |
|---|---|---|
| 1 | orchestrator | No copilot-api client, base URL or callback exists. `copilot` appears in the orchestrator source only in comments. There is no inbound-to-copilot surface to misuse. |
| 2 | orchestrator | The skills path holds no mutable state across calls: `SkillRegistry` is read-only, `SkillsService` keeps nothing per run, and there is no module-level mutable state under `src/skills/`. **Caveat below.** |
| 3 | orchestrator | `external_uri` is declared on the artifact descriptor and **never assigned** anywhere; every body is inline. §7 stays theory. |
| 4 | orchestrator | Every SQL statement in the service is a `SELECT` — no `INSERT`/`UPDATE`/`DELETE`/`DROP`/`TRUNCATE`, and no DynamoDB write call. The orchestrator cannot write the `autwit` schema because it cannot write anything. |
| 5 | copilot-api | 10m client timeout, 12m lease, ordering asserted at boot, 660s shutdown drain. See their §A2. |

**Caveat on invariant 2.** The claim holds for the skills path, which is what this
contract governs. The *service* is not globally stateless: it holds a `JobStore` for the
unrelated `/v1/orders:create` async-job API, which predates the skills orchestrator and
is outside this contract. If that endpoint is ever folded into a skill, invariant 2 needs
re-checking — it is true today by separation, not by construction.

**Invariant 5 became load-bearing in v0.1.5.** Since the orchestrator enforces no
deadline (§9), copilot-api's 10-minute timeout is the *only* deadline in the system —
the sole thing between a hung skill and an indefinitely open run.

---

## 1. Endpoints

```
GET  /skills                      catalog
POST /invoke                      LLM-driven; picks and runs skills
POST /skills/{name}/execute       direct; no LLM. CI uses this.
GET  /healthz                     liveness
```

Base URL is config on the copilot-api side (`autwit.orchestrator.base-url`).
Auth: bearer service token, `Authorization: Bearer <token>`, on `/skills`, `/invoke`
and `/skills/{name}/execute`.

**`GET /healthz` is deliberately unauthenticated.** It returns `{"status":"ok"}` and
nothing else — no catalog, no config, no version. Liveness probes must reach it before
credentials are wired. Stated explicitly because an undocumented unauthenticated
endpoint reads as an oversight to every future reviewer.

**v0.1.6 — auth fails closed.** Through v0.1.5 an empty configured token disabled
authentication entirely, so the most common misconfiguration there is — an environment
variable that never got set — silently served `/invoke`, which routes to `order.place`,
which is `mutating`. The failure was invisible because its signature is *requests
succeeding*. Now:

- **No token configured → every skills request is refused (`401`).** Absence of a value
  never grants access.
- **Disabling auth requires an explicit opt-in**, `AGENTIC_SKILLS_ALLOW_UNAUTHENTICATED=true`,
  which is greppable in a deployment config in a way an empty string is not. It is a
  local-development affordance and must never be set in a deployed environment.

Same reasoning as §3's `env` rule: the dangerous default is the one that looks like
success. Note this is a **breaking change for local setups** that relied on running with
no token — they must now set the opt-in flag.

---

## 2. `GET /skills`

Returns the catalog. copilot-api polls this (default 60s) and caches it into
`autwit.skill` as a **read-only projection**. Skills are defined in the
orchestrator's repo as **versioned TypeScript config** (`src/skills/catalog.ts`);
nobody edits them in the DB. *(v0.1.5: this said "versioned YAML" through v0.1.4.
There is no YAML — the source of truth is and has always been TypeScript.)*

**Response 200**

```json
{
  "catalog_version": "v1/3efcaf08f394",
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

**Format is `v1/<12-hex>`** — a content hash over the skill list, computed by
`SkillRegistry` at construction. It is not a timestamp and carries no date.
*(v0.1.5: the example above read `2026-07-16T09:12:00Z/a3f9c1` through v0.1.4 — a
hand-written value in a format the generator has never emitted. That literal is the
same one whose staleness caused the silently-skipped re-sync reported in
`message-from-qa-copilot/v1.0.3` §2; it survived in this example because §2 was never
audited. Do not parse a date out of this field.)*

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
  }
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
- **v0.1.5: there is no `deadline_ms` field.** It was removed because the orchestrator
  never read it — it advertised a capability that did not exist. The orchestrator
  enforces **no** deadline of its own; copilot-api's 10-minute client timeout (§0
  invariant 5, §9) is the only one, and it is unchanged. Senders are not broken:
  an extra JSON property is ignored. See §9 before assuming a long-running skill
  will be stopped server-side — it will not.

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
  "session_context": { "...": "same shape as §3" }
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
  "cursors_advanced": { "order.events": { "0": 1703000009000 } },
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
- `cursors_advanced` — **v0.1.5: added to the example above**, where it had been
  missing since v0.1.0 despite being normative in §6.3 and emitted by
  `events.capture_since`. A reader typing the envelope from §5 alone would have
  dropped the field that makes "events since the last milestone" work.

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

  3. **A null is content; absence is a different claim.** *(v0.1.8)* A key whose value
     is `null` MUST be retained in the canonical bytes. `{"a":null,"b":1}` and `{"b":1}`
     are different bodies and MUST hash differently. Any serialiser configured to omit
     null properties destroys this — the JVM trap is Jackson's
     `default-property-inclusion: non_null`, which is correct for API responses and
     catastrophic for canonical hashing. An evidence store that cannot distinguish "the
     source system returned null" from "the source system did not return this field" is
     misreporting its evidence, which is the failure this product exists to prevent.
     Same distinction §7's `assertOneBody` draws when it counts a `null` body as present.

  The governing idea is **hash exactly what was sent** — an artifact is evidence,
  and normalising evidence hides the diffs this product exists to find.

  **v0.1.8 — two null-bearing vectors, V7 and V8.** Added at copilot-api's request
  (`message-from-qa-copilot/v1.0.13` §3a) after a live defect that the original six
  vectors could not catch: their hasher dropped null-valued keys, and it passed all six
  because **the word `null` appears nowhere in them.** The vectors exist so two
  implementations cannot agree with each other's bugs; a gap in the vectors is a gap in
  that guarantee.

  | Vector | Body | Canonical bytes | `content_hash` |
  |---|---|---|---|
  | **V7** | `{"correlationId":null,"orderId":"XXXX","status":"CREATED"}` | `{"correlationId":null,"orderId":"XXXX","status":"CREATED"}` | `sha256:ff6ba507f4b8143f04b2a3696929e841a7aab69affd712633f460e68e90d40f4` |
  | **V8** | array of two objects, one with `"sourceSystem":null`, one with `"sourceSystem":"oms"` | `[{"eventId":"e1","producerTime":1703000000000,"sourceSystem":null},{"eventId":"e2","producerTime":1703000001000,"sourceSystem":"oms"}]` | `sha256:399d2ce214f0b6dbc3a2a816c0eb4514c53106656725de6635e72c74134f68ff` |

  V8 is the shape an `order_events` body actually takes — mixed presence across elements,
  which is where a null-stripping serialiser does its damage in practice.

  **V7 only bites in company with its negative case:** `{"correlationId":null,…}` and
  `{…}` without the key MUST produce different hashes. An implementation that drops nulls
  passes a naive V7 by hashing the stripped form consistently; it fails the inequality.
  Both are pinned in `contentHasher.test.ts`.

  Expected values above were computed by hashing the hand-written canonical strings
  directly, **not** by running either side's canonicaliser — a vector that reuses the
  implementation to derive its own expectation is not independent evidence, which is how
  this class of defect survived in the first place.

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

**v0.1.5 — `order_flow` is the only implemented scope.** The catalog's `input_schema`
still advertises `enum: ["order_flow", "shipment_only"]`, but `shipment_only` has no
implementation. Requesting it now returns **`400` `invalid_input`, `retryable: false`**.
Previously it passed schema validation and returned an `order_flow` snapshot *labelled*
`order_flow` — key-joinable against real order_flow snapshots, with nothing anywhere
recording that the caller had asked for something else. Same reasoning as §3's `env`
rule: a rejected request is recoverable, silently wrong evidence is not. The enum is
deliberately left intact so `catalog_version` and the ⌘K palette's form generation are
unaffected; it narrows when the scope is implemented or formally dropped.

**v0.1.5 — this guarantee is now pinned by a test**, not merely asserted here.
`snapshotCapture.test.ts` captures the same scope twice and requires an identical
`part_key` sequence, uniqueness, agreement with `scope_def.tables`, and that an empty
table still contributes its key. The test was verified to fail when `part_key` is made
to vary. Two honest caveats: it pins the **demo** scope, whose keys are a hardcoded
constant, so today it guards against regression rather than proving the property; and
when the real DB-backed scope lands, that implementation must be brought under the same
test before it is trusted — a scope that derives keys from live table introspection can
drift in ways a constant cannot.

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

> **Status: parked, and deliberately unverified.** This section describes **no current
> behaviour on either side**. `external_uri` is declared on the artifact descriptor and
> never assigned by the orchestrator (§0 invariant 3); copilot-api implements no reader
> for it. It is a design sketch for v0.2, retained so the field's meaning is settled
> before anyone needs it.
>
> It is therefore the one section the audit did **not** cover, and that is correct rather
> than an omission — there is no implementation to read it against. It moves to "audited"
> only when it describes something that exists. Do not treat anything below as a
> commitment about today's wire.
>
> **One exception (v0.1.7):** the `body` / `external_uri` mutual-exclusion rule below is
> **live and enforced today**, because it constrains descriptors that already exist. It
> was the only asked-for behaviour in §7 that had never been implemented. Everything else
> here remains v0.2 design.

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

**v0.1.7 — the mutual-exclusion rule is now enforced, and it is the one part of §7
that is not parked.** Through v0.1.6 it was stated here and checked nowhere on the
orchestrator side: `ArtifactDescriptor` declared `body` and `external_uri` as
independently optional, and nothing asserted exactly-one-of. The rule held only because
`makeArtifact` is the single construction path and always sets `body` — true by accident,
in the same way §6.2's `part_key` stability was.

`assertOneBody` (`src/skills/artifactBuilder.ts`) now enforces it at construction, before
hashing, and is exported so v0.2's externalisation path reuses it rather than reinventing
it. Both failure modes are rejected:

- **Both set** — copilot-api's `one_body` constraint would reject the row, failing the run
  late and far from the cause.
- **Neither set** — the worse case: a descriptor with a `content_hash` over content nobody
  can retrieve. It would validate structurally and is evidence pointing at nothing.

A `null` body counts as **present** — `null` is content; absence is not.

This is deliberately done *now*, while there is exactly one construction path and the rule
cannot be broken, rather than during v0.2 when a second path appears and it can be.

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

**Codes, who emits them, and how copilot-api handles them**

**v0.1.6 — the `emitted by` column is new and matters.** Through v0.1.5 this table read
as "codes the orchestrator sends", which was never true: at least two are raised by
copilot-api itself and one of those never crosses the wire at all. A reader without this
column goes looking for orchestrator emission of `confirmation_required` and does not
find it.

| code | emitted by | copilot-api behaviour |
|---|---|---|
| `input_schema_violation` | orchestrator | run → `failed`. Never retry. Bug in the palette or the LLM. |
| `invalid_input` | orchestrator | run → `failed`, `retryable: false`. Includes **missing `session_context.env`** (§3, v0.1.3). A config/enqueue bug — no retry will fix it. |
| `skill_not_found` | orchestrator | run → `failed`. Trigger a catalog re-sync. |
| `skill_disabled` | orchestrator | run → `failed`. Re-sync catalog. |
| `upstream_unavailable` | orchestrator | run → `failed`, `retryable: true`. UI offers retry. |
| `confirmation_required` | **both** | copilot-api raises it **pre-emptively at enqueue** for `mutating` skills, before any call is made; the orchestrator also returns it where a skill refuses to run unconfirmed. run → `failed`, `code` surfaced to UI as a confirm prompt. |
| `deadline_exceeded` | **copilot-api only** | **Retired as an orchestrator code in v0.1.5** — nothing here enforces a deadline, so it can never arrive over the wire. copilot-api still *synthesises* it for its own client-side timeout and stores it in `run.error()`, where it distinguishes a worker's own deadline from the reaper's `lease_expired`. Returns to the orchestrator's union if v0.2 adds server-side enforcement. |

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

**v0.1.5 — the orchestrator enforces no deadline at all.** Every timeout in this
section is copilot-api's. The orchestrator has no server-side time budget, does not
stop a long-running skill, and never returns a `deadline_exceeded` error — that code
was removed from §8 because nothing could emit it. A skill that hangs runs until
copilot-api's 10-minute client timeout fires and the run is marked `timed_out`
(**outcome unknown**, per above), while the orchestrator keeps working and its result
is discarded on arrival. This is a real limitation, not a simplification: it is why
`timed_out ≠ failed` matters, and why `max_attempts = 1` on `invoke` (§3) is load-
bearing rather than merely cautious. Server-side enforcement returns together with a
re-introduced `deadline_exceeded`, not before.

---

## 10. Fake implementation

copilot-api ships `FakeOrchestratorClient` (`@Profile("fake")`) replaying fixtures from
`autwit-copilot-api/src/main/resources/fixtures/orchestrator/`. **`main`, not `test`** —
the fake profile has to be *runnable*, not merely testable, because the UI is developed
against it, and fixtures under `test/resources` are not on the runtime classpath. A fake
built from a `test/resources` path cannot start. It must cover:

| Fixture | Exercises |
|---|---|
| `invoke_order_created.json` | 9 artifacts, 1 snapshot, subjects_discovered |
| `invoke_ready_for_member.json` | api_response + event_batch + 14 events + cursors_advanced |
| `invoke_fulfilled.json` | second snapshot with **identical part_keys** |
| `invoke_events_dedupe.json` | overlapping dedupe_hashes with the previous fixture |
| `invoke_partial.json` | `status: partial`, 7 of 9 parts, **`medium` finding** (§5) |
| `invoke_failed.json` | RFC 7807, `upstream_unavailable` |
| `invoke_slow.json` | sleeps past the deadline → `timed_out` path |
| `skills_catalog.json` | `GET /skills`, **incl. a `mutating` skill and a disabled one** |

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

**v0.1.6 — the event fixtures are generated too.** `invoke_ready_for_member.json` and
`invoke_events_dedupe.json` are produced by `orchestration-service/tools/genfixtures.ts`,
which drives the **real executor** against a scripted Event Store. Every `content_hash`
and `dedupe_hash` in them is computed by the live `ContentHasher`, not written by hand —
so they cannot claim a hash the code would not produce. Regenerate rather than edit.

Two properties the generator establishes deliberately, since they are what these two
fixtures exist to exercise:

- **The store's contents differ by capture time.** `ready_for_member` is taken when 14
  events exist; the dedupe re-read happens later, once four more have arrived. Both
  reading the same store would make the overlap meaningless.
- **The overlap is real, not simulated.** The dedupe capture re-reads from an *older*
  milestone cursor, so its first four events are byte-identical to four already in
  `ready_for_member` — same `dedupe_hash`, which is what makes copilot-api's
  `ON CONFLICT DO NOTHING` produce the delta for free.

This also fixed a latent inconsistency: in the pre-v0.1.6 fixtures the four overlapping
events carried *different* `occurred_at` values in each file despite being the same
events. `occurred_at` is now derived from `producerTime`, so identical events are
identical everywhere.

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
   *(v0.1.5: the `deadline_ms` field this item was asked about no longer exists — the
   orchestrator enforces no deadline. The budgets above still describe real durations.)*

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
