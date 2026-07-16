# Schema Verification

`V1__init.sql` was executed against **PostgreSQL 16.14** and the design
invariants were tested directly, not assumed. Results below.

Reproduce with Testcontainers in `RunQueueTest` etc. — see `BUILD_BRIEF.md` §9.

---

## Migration

| Check | Result |
|---|---|
| `psql -v ON_ERROR_STOP=1 -f V1__init.sql` | clean, zero errors |
| Tables created | 13 |
| Circular FK `milestone.snapshot_id ↔ snapshot.milestone_id` | resolved via deferred `ALTER TABLE ADD CONSTRAINT` — no bootstrap deadlock |

---

## Invariant #5 — `autwit.run` is the queue, exactly-once

4 concurrent workers, 3 dequeue attempts each, against 6 queued runs:

```
total | claimed | still_queued | max_attempts | distinct_workers
    6 |       6 |            0 |            1 |                4
```

`max_attempts = 1` across all rows, zero runs claimed twice. **`SKIP LOCKED`
delivers exactly once under contention.**

Dequeue plan uses both partial indexes — no seq scan:

```
Limit → LockRows → Sort → Bitmap Heap Scan on run
   BitmapOr
     → Bitmap Index Scan on idx_run_queued
     → Bitmap Index Scan on idx_run_lease  (Index Cond: lease_until < now())
```

## Lease reclaim (dead worker)

Expired `w1`'s lease → next dequeue reclaimed the run as `w9` with
`attempts = 2`. **A worker OOMing mid-9-table-dump does not strand the run.**

## Reaper

```sql
UPDATE ... SET status='timed_out', error={'code':'lease_expired'}
WHERE status='running' AND lease_until < now();
```
→ `timed_out | lease_expired | 1`. Correctly distinct from `failed`.

## Invariant #6 — per-session serialization

| Attempt | Result |
|---|---|
| Worker B, **same** session as holder | `f` → refused, skips run, takes next |
| Worker C, **different** session | `t` → cross-session parallelism preserved |
| After holder disconnects | `t` → lock auto-released |

**A step-5 snapshot cannot race a step-2 snapshot.**

## Invariant #8 — idempotency

| Case | Result |
|---|---|
| Same `(session_id, idempotency_key)` twice | `unique_violation` — **double-click cannot place two orders** |
| Two runs with `idempotency_key IS NULL` | both inserted — partial index correctly ignores NULLs |

## Artifact constraints

| Case | Result |
|---|---|
| `body_jsonb` only | accepted |
| `body_jsonb` + `external_uri` | rejected by `one_body` |
| No body, not purged | rejected by `body_present_unless_purged` |
| Body + `external_uri` on a **purged** row | rejected by `one_body` |

The two constraints layer correctly. `one_body` is not merely shadowed by the
purge check — it independently rejects the both-present case even when
`purged_at` is set. The v0.2 `external_uri` path is already protected.

Purge nulls the body and **keeps the row** (`purged_at` set, metadata intact) —
the trace survives retention.

## Event dedupe — the delta-for-free mechanism

```
capture #1: orchestrator returns 3 events        → 3 stored
capture #2: orchestrator re-reads from cursor,
            returns 2 overlapping + 2 new        → 5 total

tagged after_milestone_id = order_created:
  ReadyForMember  10435
  OrderFulfilled  10436
```

Only genuinely new events landed. **The orchestrator never computes the delta** —
it re-reads from the cursor and returns everything; `ON CONFLICT (session_id,
dedupe_hash) DO NOTHING` makes the delta emerge. This is exactly the step-4
requirement ("capture all events after step 2").

## Cascade — one knob

Seeded a full graph, then `DELETE FROM autwit.session WHERE session_id = ...`:

| table | before | after |
|---|---|---|
| step | 1 | 0 |
| run | 6 | 0 |
| artifact | 2 | 0 |
| snapshot | 1 | 0 |
| snapshot_part | 1 | 0 |
| milestone | 1 | 0 |
| event_record | 5 | 0 |
| comparison | 1 | 0 |
| finding | 1 | 0 |
| agent_memory | 1 | 0 |
| skill_invocation | 1 | 0 |

Zero orphans. The circular milestone↔snapshot FK does not block deletion.

---

## Fixed during verification

**`openapi.yaml`** referenced `#/components/schemas/Milestone` from
`SessionDetail`, but the schema was never defined — 1 of 108 `$ref`s was broken.
Added. All 108 now resolve.

(`StreamEvent` is defined but unreferenced by design: it documents the SSE
payload, which is not a JSON endpoint.)

---

## Not verified here (needs application code)

- 10m timeout / 12m lease interaction under a real slow orchestrator
- `content_hash` recomputation and truncation rejection
- Diff engine semantics
- LISTEN/NOTIFY → SSE fan-out
- Graceful shutdown draining in-flight runs

These are the Testcontainers tests in `BUILD_BRIEF.md` §9.
