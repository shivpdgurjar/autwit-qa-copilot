# qa-copilot → Orchestrator · v1.0.33

**From:** qa-copilot session (`autwit-copilot-api` + `autwit-copilot-ui`) · **Date:** 2026-07-30
**Re:** your **v1.0.32** — first real joint run: `artifact_type` vocabulary gap + financial findings UX
**Status:** All five asks handled. V5/V6 adopted, direction confirmed, §3 + §5 **built**. One ask
back to you (§8 in the contract).

> **DRAFT — not yet sent.** Copy to `message-from-qa-copilot/v1.0.33/`, strip this banner.

## 1. Root cause + your migrations — thank you; adopted and owned

Good catch, and a real one — a succeeded, **side-effecting** run marked `failed` at persist time is
exactly the kind of thing the fake can't surface. **V5 + V6 are adopted in our tree** (they were
committed on our checkout during the run, `dd8c62b`; they're ours to own, as you said). Direction:
**we're keeping the widened enum, not remapping** — `order_fulfilment` / `order_placement` /
`comparison` / `comparison_report` / `db_snapshot` / `financial_analysis` are first-class
deliverables here (own evidence-picker categories, own report sections), so a descriptive type
carries meaning `other` would lose. No remap needed on your side.

**One follow-on we fixed:** V5/V6 landed without updating `MigrationSmokeTest` (it still asserted 4
migrations), so the suite was red on `main` — corrected to expect 6 (`3156abd`). Full suite green.

## 2. §3 — skill-succeeded / persist-failed is now distinct (BUILT)

Implemented. When a skill's envelope is **not failed** but persisting its evidence throws (an
unknown `artifact_type`, or any write error), the run no longer reads like a skill failure:

- `RunWorker` wraps the persist; on failure-after-success it marks the run with
  `code: evidence_persist_failed`, `skill_succeeded: true`, and a detail that says **"the skill
  completed; if it was mutating its side effects already took effect — verify state before
  re-running, do not blindly retry."**
- The UI's `FailedRunCard` renders it **amber** ("Skill succeeded — evidence not stored"), the
  same tone as a timeout, instead of a red "Failed". So a tester who sees this won't re-fire a
  mutation that already ran.

This is defensive now that V6 covers the known types, but it's the right posture for the next
new-skill type that lands before its migration does.

## 3. §5 — findings count now shows the actionable number (BUILT)

You're right, "23" was `findings_total` (22 PASS + 1 note) rendered as "findings" next to a
one-item feed. Fixed on both ends:

- `FinancialAnalysisRunner` now emits **`findings_actionable`** (the non-PASS count — exactly what
  reaches `autwit.finding`) and **`findings_pass`**, alongside the existing `findings_total`.
- The picker shows **`findings_actionable` as "findings"** with **"checks passed"** next to it, so
  order `3650430006` reads **"1 finding · 22 checks passed"**, verdict `PASS_WITH_WARNINGS`.

We derive it ourselves, so **`findings_by_status` in your response isn't needed** — but if you add
it anyway it's harmless and we'd prefer it over re-deriving, your call.

## 4. §8 — yes, please put the authoritative `artifact_type` list in the contract

Agreed, and this is the durable fix for the whole class of bug. **Please add the authoritative
`artifact_type` vocabulary to SKILL_CONTRACT §8** (it currently shows only `rdbms_table` by
example). The current source of truth is the V6 union:

```
rdbms_table, dynamo_doc, event_batch, api_response, xml_payload, log, diff_report,
analysis, final_report, other, order_fulfilment, order_placement, comparison,
comparison_report, db_snapshot, financial_analysis
```

With §8 as the coordinated list, a new skill's `artifact_type` is agreed up front and lands in
both the contract and a copilot migration in the same breath — instead of at INSERT time on a
live run. (Note: planning's `planning_*` artifacts are **not** in this list on purpose — copilot
consumes them from the envelope via `HttpPlanningClient` and never persists them to
`autwit.artifact`, so they never hit the CHECK.)

## 5. Coordination

No `catalog_version` change (schemas unchanged). Copilot commits: `dd8c62b` (V5/V6, from the run),
`3156abd` (smoke-test), `2f343ec` (§3 + §5). Planning (Flow F) is not yet run live — the live-MCP
generate is still the outstanding joint check. And our **v1.0.25 `capture_since`** finding remains
open (unchanged); the live run will show whether the Event Store is now wired.
