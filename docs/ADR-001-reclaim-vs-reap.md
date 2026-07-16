# ADR-001 — Reclaim vs reap: disjoint predicates

**Status:** Accepted
**Date:** 2026-07-16
**Affects:** `RunWorker` (dequeue), `RunReaper`, `RunEnqueuer`, `autwit.run.max_attempts`
**Relates to:** BUILD_BRIEF §2 (invariants 5, 8), §6, §9; SKILL_CONTRACT §9, §11 item 1

## Context

`BUILD_BRIEF.md` was written before the dequeue and the reaper were implemented,
and the two SQL statements it specifies select **overlapping** rows:

```sql
-- dequeue (BUILD_BRIEF §6, V1__init.sql reference block)
WHERE status = 'queued' OR (status = 'running' AND lease_until < now())

-- reaper (BUILD_BRIEF §6)
WHERE status = 'running' AND lease_until < now()
```

A run whose worker died is matched by both. Behaviour therefore depends on which
statement happens to fire first:

- **Dequeue wins** → the run is reclaimed, `attempts` becomes 2, and it is
  re-executed. BUILD_BRIEF §9 explicitly requires this: *"worker killed mid-run →
  lease expires → reclaimed → attempts=2"*.
- **Reaper wins** → the run is marked `timed_out`. Invariant 8 requires this be
  final: *"`timed_out` ≠ `failed`. Timed out means outcome UNKNOWN. Never
  auto-retry."*

Both outcomes are mandated, for the same row, by the same document. That is a
genuine gap rather than a contradiction to relitigate: §9's reclaim requirement
and invariant 8 are each correct, but they are talking about different kinds of
run, and nothing in the schema distinguishes them at dequeue time.

The gap matters because of what invariant 8 is protecting. If the dequeue reclaims
a run that invoked a **mutating** skill, copilot-api re-executes work that may have
already placed an order. That is the precise failure the 12m-lease-over-10m-timeout
rule exists to prevent, re-introduced one layer up.

Note also that `max_attempts` already defaults to 1, so a literal reading of the
dequeue SQL — which never consults `attempts` — contradicts the column's own
documented purpose.

## Decision

### 1. Make the two predicates disjoint

Add the `attempts`/`max_attempts` guard to the dequeue, and its mirror to the
reaper:

```sql
-- dequeue: claim fresh work, or reclaim only what may be retried
WHERE status = 'queued'
   OR (status = 'running' AND lease_until < now() AND attempts < max_attempts)

-- reaper: bury only what must never be retried
WHERE status = 'running' AND lease_until < now() AND attempts >= max_attempts
```

Every row now falls to exactly one statement. The race is not won, it is removed.
A run with `max_attempts = 1` that has been attempted once is invisible to the
dequeue and can only ever terminate as `timed_out` — invariant 8 holds
structurally, not by timing luck.

This is safe under READ COMMITTED without extra locking. If a worker reclaims a
row while the reaper is mid-sweep, the reaper's `UPDATE` blocks on the row lock,
re-evaluates its `WHERE` against the committed row (Postgres EvalPlanQual), sees
the renewed `lease_until`, and skips it. The converse holds identically.

### 2. Set `max_attempts` at enqueue, from what is actually known then

| `run_type` | `max_attempts` | Rationale |
|---|---|---|
| `invoke` | **1** | The LLM selects the skill *after* enqueue. Side effects are unknowable at this point, so assume mutating. |
| `skill_execute` | catalog lookup | The skill name is known. `side_effects = mutating` → 1; `none` → 2. |
| `milestone` | 2 | `snapshot.capture` is `side_effects: none`. |
| `comparison` | 2 | Local diff. Never touches the orchestrator. |
| `report` | 2 | Local render. Never touches the orchestrator. |

`invoke` is the load-bearing row. Most invocations are in practice read-only
snapshot captures, which makes reclaiming them tempting — but that is not known at
enqueue, and "probably not mutating" is exactly the reasoning that places an order
twice. Unknown is treated as mutating.

`skill_execute` reads `side_effects` from the `autwit.skill` catalog projection. If
the skill is absent from the cache, treat it as mutating (1) — the catalog is a
cache and may lag, and a cache miss is not evidence of safety.

## Consequences

- **Invariant 8 becomes structural.** No mutating run can be auto-retried, because
  no statement in the system selects it for reclaim.
- **BUILD_BRIEF §9's reclaim test is now expressible.** *"Worker killed mid-run →
  reclaimed → attempts=2"* is a statement about **non-mutating** runs. It should be
  written against a `comparison` run (`max_attempts = 2`). Against an `invoke` run
  it must assert the opposite: not reclaimed, reaped to `timed_out`. Both
  assertions belong in `RunQueueTest`.
- **A dead worker's mutating run resolves in ≤ reaper-interval (60s)**, as
  `timed_out`, and a human decides. The UI already says "may have partially
  completed; verify before retrying" (SKILL_CONTRACT §9).
- **`invoke` is deliberately conservative and cheap to revisit.** SKILL_CONTRACT
  §11 item 1 asks whether `/invoke` honours `run_id` idempotency replay. If it
  does, `invoke` can move to 2 and reclaim becomes safe — a one-line change in
  `RunEnqueuer`, which is much of the reason to fix the shape now and tune the
  number later. Until answered, 1.
- **`max_attempts` stops being decorative.** It is now read on the hot dequeue
  path, so `RunEnqueuer` must set it deliberately per `run_type` rather than
  relying on the column default.
- **The dequeue plan is unaffected.** `attempts`/`max_attempts` are non-indexed
  filter columns evaluated after the `idx_run_queued` / `idx_run_lease` bitmap
  scans; the plan in SCHEMA_VERIFICATION.md still holds.

## Alternatives rejected

- **Let the reaper always win (never reclaim).** Simplest and safest, but strands
  every non-mutating run for up to 60s and turns a dead worker's snapshot capture
  into a `timed_out` a human must manually retry. Discards §9's requirement
  outright rather than reconciling it.
- **Let the dequeue always win (always reclaim).** Satisfies §9 as literally
  written and violates invariant 8 for mutating skills. This is the
  order-placed-twice bug.
- **Have the reaper skip rows a worker is about to claim, via `FOR UPDATE SKIP
  LOCKED`.** Narrows the window without closing it: the two statements still
  contend for the same rows, and correctness would rest on lock timing rather than
  on the data. Disjoint predicates need no coordination at all.
- **Decide mutability at dequeue instead of enqueue**, by inspecting the run's
  request. For `invoke` the skill is chosen by the LLM inside the orchestrator, so
  the information does not exist on either side of the call at that point.
