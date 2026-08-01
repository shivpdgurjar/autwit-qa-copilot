-- V7__planning_session.sql
-- Session-based support for the Planning Copilot (docs/PLANNING_SESSIONS_DESIGN.md, Option B).
--
-- A planning_session is a resumable, SINGLE-TESTER planning context. Two jobs:
--   1. Resume — a tester's recent sessions are the landing list; reopening restores the work.
--   2. Reusable history — the session carries the running OpenAI conversation lineage
--      (latest_response_id), so each new generation seeds previous_response_id from the session
--      head and pins the result back: plan → data → re-plan become one growing conversation the
--      model builds on, not cold restarts. Same chaining seam as financial (v1.0.16 §4) — a
--      cache, never a dependency (missing/expired degrades to a fresh generation).
--
-- Parallel to autwit.session, deliberately NOT folded into it: planning has no order/evidence/run,
-- and the execution step/run model shouldn't carry planning actions. tester_id is attribution and
-- the resume-list filter, NOT access control (no sharing/permissions — that's a separate concern).

-- ============================================================ planning_session

CREATE TABLE autwit.planning_session (
  session_id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tester_id          text,
  env                text,
  title              text,
  status             text NOT NULL DEFAULT 'active' CHECK (status IN ('active','archived')),
  -- Running OpenAI lineage the next generation continues from. NULL is valid (a cache, never a
  -- dependency); a missing/expired id degrades to a fresh generation.
  latest_response_id text,
  -- Optimistic lock — a generation pins its result WHERE version matches (same as analysis_session).
  version            int  NOT NULL DEFAULT 0,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  -- Resume ordering: the landing list is a tester's sessions, most-recently-active first.
  last_active_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_planning_session_recent ON autwit.planning_session (tester_id, last_active_at DESC);

-- ============================================================ planning_activity

-- The history/audit timeline: what the session has done + produced. Drives "resume where you
-- left off" and shows the accumulating history the reusable-lineage generations build on.
CREATE TABLE autwit.planning_activity (
  id          bigserial PRIMARY KEY,
  session_id  uuid NOT NULL REFERENCES autwit.planning_session ON DELETE CASCADE,
  kind        text NOT NULL
              CHECK (kind IN ('session_created','project_added','document_added',
                              'context_fetched','plan_generated','data_generated')),
  ref         text,
  summary     text,
  at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_planning_activity_session ON autwit.planning_activity (session_id, id);

-- ============================================================ planning_project → session

ALTER TABLE autwit.planning_project
  ADD COLUMN session_id uuid REFERENCES autwit.planning_session ON DELETE CASCADE;

-- Back-fill: existing projects belong to one imported session per tester (single-tester model).
INSERT INTO autwit.planning_session (tester_id, env, title)
SELECT COALESCE(created_by, 'unknown'), MAX(env), 'Imported planning work'
FROM autwit.planning_project
GROUP BY COALESCE(created_by, 'unknown');

UPDATE autwit.planning_project p
SET session_id = s.session_id
FROM autwit.planning_session s
WHERE s.tester_id = COALESCE(p.created_by, 'unknown');

-- Now that every project has a session, make the link mandatory.
ALTER TABLE autwit.planning_project ALTER COLUMN session_id SET NOT NULL;
CREATE INDEX idx_planning_project_session ON autwit.planning_project (session_id);
