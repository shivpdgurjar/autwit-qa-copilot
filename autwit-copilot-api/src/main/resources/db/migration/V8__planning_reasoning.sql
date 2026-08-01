-- V8__planning_reasoning.sql
-- "Reasoning" — a pre-generation conflict/clarification loop for the Planning Copilot.
--
-- Before a test plan is generated, the tester can run an analysis pass over the selected
-- corpus. The orchestrator's new planning.analyze_documents skill (envelope-consumed, like
-- the other planning skills — never persisted to autwit.artifact) returns two kinds of
-- finding: CONFLICTS (contradictions between documents the tester must confirm) and
-- CLARIFICATIONS (information the plan needs that the material does not settle). The tester
-- resolves each; a re-analysis feeds the answers back and the loop repeats until the analysis
-- is clean. Generation is BLOCKED while a reasoning thread is open, unless the tester records
-- an explicit "proceed anyway" override. The step is opt-in — a project with no reasoning
-- thread generates exactly as before.
--
-- The analysis job reuses the existing generation queue (a new generation_type), so the
-- SKIP-LOCKED worker/lease/poll machinery carries it unchanged. The tables below are the
-- durable deliverable — the analogue of test_plan/test_scenario for the reasoning pass.

-- ============================================================ extend enums (CHECK)

-- A reasoning round is an async generation, dequeued by the same worker as the two generators.
ALTER TABLE autwit.generation DROP CONSTRAINT generation_generation_type_check;
ALTER TABLE autwit.generation ADD CONSTRAINT generation_generation_type_check
  CHECK (generation_type IN ('test_plan','test_data','document_analysis'));

-- A completed round appends a history entry, like plan_generated / data_generated.
ALTER TABLE autwit.planning_activity DROP CONSTRAINT planning_activity_kind_check;
ALTER TABLE autwit.planning_activity ADD CONSTRAINT planning_activity_kind_check
  CHECK (kind IN ('session_created','project_added','document_added',
                  'context_fetched','plan_generated','data_generated','documents_analyzed'));

-- ============================================================ planning_reasoning

-- The durable reasoning thread for a project (one per project). 'open' means at least one
-- round has run and there are unresolved findings → generation is gated; 'clean' means the
-- last round returned no findings; 'overridden' means the tester chose to proceed anyway.
CREATE TABLE autwit.planning_reasoning (
  reasoning_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id      uuid NOT NULL REFERENCES autwit.planning_project ON DELETE CASCADE,
  status          text NOT NULL DEFAULT 'open'
                  CHECK (status IN ('open','clean','overridden')),
  -- How many analysis rounds have been enqueued; the next round is round + 1.
  round           int  NOT NULL DEFAULT 0,
  -- The recorded "proceed anyway" escape hatch (block + explicit override).
  override_reason text,
  override_by     text,
  override_at     timestamptz,
  -- Optimistic lock, same discipline as the other planning heads.
  version         int  NOT NULL DEFAULT 0,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  -- One reasoning thread per project.
  UNIQUE (project_id)
);

-- ============================================================ planning_analysis (per round)

-- One row per completed analysis round — the deliverable a document_analysis generation
-- produces (the analogue of test_plan for a generate job). A re-analysis makes a new round;
-- history is kept.
CREATE TABLE autwit.planning_analysis (
  analysis_id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reasoning_id         uuid NOT NULL REFERENCES autwit.planning_reasoning ON DELETE CASCADE,
  generation_id        uuid NOT NULL REFERENCES autwit.generation ON DELETE CASCADE,
  round                int  NOT NULL,
  conflicts_total      int  NOT NULL DEFAULT 0,
  clarifications_total int  NOT NULL DEFAULT 0,
  created_at           timestamptz NOT NULL DEFAULT now(),
  -- One analysis per generation.
  UNIQUE (generation_id)
);

CREATE INDEX idx_planning_analysis_reasoning ON autwit.planning_analysis (reasoning_id, round DESC);

-- ============================================================ planning_analysis_finding

-- The findings of one round. sources = [{doc_title, quote}] grounding the finding; options is
-- the distinct candidate values for a conflict (the tester confirms one), empty for a
-- clarification. Child rows so the UI can render conflicts and clarifications as two lists.
CREATE TABLE autwit.planning_analysis_finding (
  finding_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  analysis_id  uuid NOT NULL REFERENCES autwit.planning_analysis ON DELETE CASCADE,
  kind         text NOT NULL CHECK (kind IN ('conflict','clarification')),
  seq          int  NOT NULL,
  title        text NOT NULL,
  detail       text,
  sources      jsonb NOT NULL DEFAULT '[]',
  options      jsonb NOT NULL DEFAULT '[]',
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_planning_finding_analysis ON autwit.planning_analysis_finding (analysis_id, seq);

-- ============================================================ planning_resolution

-- The tester's answers, accumulated across rounds. Every subsequent analysis receives ALL of
-- these so the model does not re-raise a settled point and takes the answer as authoritative.
-- finding_id is the round's finding this answers — nullable, because findings are regenerated
-- each round and the referenced one may be gone by the time we read history; prompt keeps the
-- question text so a resolution is self-describing regardless.
CREATE TABLE autwit.planning_resolution (
  resolution_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reasoning_id  uuid NOT NULL REFERENCES autwit.planning_reasoning ON DELETE CASCADE,
  round         int  NOT NULL,
  finding_id    uuid,
  kind          text NOT NULL CHECK (kind IN ('conflict','clarification')),
  prompt        text NOT NULL,
  answer        text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_planning_resolution_reasoning ON autwit.planning_resolution (reasoning_id, created_at);
