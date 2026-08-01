# Design: session-based support for the Planning Copilot (Option B)

**Status:** Design, pre-implementation. **Scope (confirmed 2026-08-01):** resumability +
**reusable generation history that later requests build on** — *single tester*. NOT sharing /
multi-tester / permissions (`tester_id` is attribution only, like the execution flavor).

## 0. Why

Today the Plan flavor is project-based and stateless-across-visits: a `planning_project` holds a
feature's corpus + generations, wizard position lives only in the URL, and chaining
(`previous_response_id`) exists but only per *regenerate*. A tester who returns tomorrow, or
issues a *new* request in the same effort, starts cold.

A **planning session** makes the effort a durable, resumable unit whose **history is reused by
the next request**:

1. **Resume** — land on your recent sessions, reopen exactly where you left off.
2. **Reusable history** — the session carries the OpenAI conversation lineage plus the
   accumulated corpus and prior generated plans/data, so **every new generation in the session
   continues the previous one** (chained via `previous_response_id`, degrade-to-fresh) instead
   of restarting. That is the "next requests reuse history" requirement.

This is Option B from the plan: a **dedicated `planning_session`**, parallel to `autwit.session`,
NOT folded into it — planning has no order/evidence/run, and the execution `step`/`run` model
shouldn't be stretched to carry planning actions.

## 1. Model

```
planning_session 1───N planning_project 1───N { source_document, generation }
```

A session owns 1..N projects (usually one). The session is the resumable, history-bearing head.

### V7 migration

- **`planning_session`**: `session_id` (uuid pk), `tester_id`, `env`, `title`,
  `status` (active|archived), **`latest_response_id`** (the running OpenAI lineage — nullable;
  a cache, never a dependency: missing/expired ⇒ fresh generation, same rule as everywhere else),
  `version` (optimistic lock), `created_at`, `updated_at`, **`last_active_at`** (resume ordering).
- **`planning_project.session_id`** — new FK → `planning_session` (ON DELETE CASCADE).
- **`planning_activity`**: `session_id` FK, `seq`, `kind`
  (session_created | project_added | document_added | context_fetched | plan_generated |
  data_generated), `ref`, `summary`, `at` — the history/audit timeline the tester resumes against
  and the record of what the session has produced.

Back-fill: existing projects → one default session per `tester_id` (or a single "legacy" session)
so nothing orphans.

## 2. Backend

- **`PlanningSessionService`** — `create(tester_id, env, title)`, `listRecent(tester_id, limit)`
  (by `last_active_at` desc — the resume list), `get/touch`, `archive`. `bumpHead` pins the
  lineage `latest_response_id` optimistically (mirror of `AnalysisRepository.recordResult`).
- **History reuse (the crux):** `PlanningGenerationRunner` seeds each generation with the
  **session's** `latest_response_id` as `previous_response_id`, and pins the returned
  `response_id` back onto the session head. So plan→data→re-plan in one session are one growing
  conversation the model builds on. Degrade-to-fresh if the token is null/expired (unchanged
  contract). Prior generated plans/data + the corpus remain available as context.
- Every mutation (`createProject`, `addUploadedFile`/`addTextDocument`, `fetchContext`,
  `generateTestPlan`, `generateTestData`) appends a `planning_activity` row and touches
  `last_active_at`.
- Endpoints: `POST /planning/sessions`, `GET /planning/sessions?tester_id=&limit=`,
  `GET /planning/sessions/{id}` (+ its projects + activity timeline). Projects are created
  under a session (`session_id` on create).

## 3. UI

- **Planning landing = recent sessions** (resume list, `last_active_at`) + **New session**
  (tester_id, env, title — reuse the execute flavor's `NewSessionForm` pattern).
- The 4-step wizard nests under a session; the session id joins the URL state we already persist,
  so resume restores session + project + stage + in-flight generation.
- An **activity/history panel** (from `planning_activity`), and a "continuing from your last
  generation" affordance when the session has a live lineage.

## 4. Phasing

1. **V7 + `PlanningSessionService` + endpoints** (create/list/resume), project gets `session_id`.
2. **History reuse** — thread the session lineage through the generators; pin it back.
3. **`planning_activity`** timeline + `last_active_at` touch.
4. **UI** — session list + resume + history panel.
5. **Back-fill migration** for existing projects.

## 5. Decisions / notes

- **Single-tester:** `tester_id` is attribution + the resume-list filter, not access control. No
  sharing, no permissions. Real auth stays the separate standing gap.
- **Session ↔ project cardinality:** modelled 1..N; v1 UX defaults to one project per session,
  but the schema doesn't preclude grouping several features later.
- **Entirely copilot-side** — no orchestrator/contract change (chaining uses the existing
  `previous_response_id` seam confirmed in v1.0.27 §4 / v1.0.30). No `catalog_version` impact.
- **Not Option A:** we are not folding planning into `autwit.session`; a future cross-link
  (`planning_session.execution_session_id`) can add plan→execute traceability without coupling
  the run machinery, if that's ever wanted.
