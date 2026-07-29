-- V4__planning_studio.sql
-- Planning Copilot ("Test Plan & Data Studio") — the SECOND flavor of the copilot
-- (message-from-qa-copilot/v1.0.26). A parallel domain, deliberately NOT hung off
-- autwit.session: a planning project has no order-under-test, no evidence capture, no
-- findings feed. It is a corpus of requirement docs (uploaded + fetched from Jira/
-- Confluence over MCP) that an LLM turns into a test plan and test data.
--
-- Ownership split (v1.0.26 §2): we own all durable state here; the orchestrator owns the
-- MCP connectors and the two generation skills. copilot-api never speaks MCP — it calls a
-- skill and persists what comes back. So every row below is either tester input we captured
-- or a deliverable the orchestrator produced and we stored.

-- ============================================================ planning_project

CREATE TABLE autwit.planning_project (
  project_id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  -- The Jira epic / feature key (e.g. PAY-2481) the plan is anchored to. Optional: a
  -- tester can start from a keyword search instead, so this may be blank.
  feature_key          text,
  -- "What are we testing" — sharpens scenario relevance at generation time.
  feature_description  text,
  title                text,
  status               text NOT NULL DEFAULT 'active'
                       CHECK (status IN ('active','archived')),
  created_by           text,
  env                  text,
  -- OpenAI Responses chaining token from the most recent generation. NULL is valid and
  -- expected — a cache, never a dependency (same rule as analysis_session): a missing or
  -- expired id degrades to a fresh generation, never fails. A regenerate continues the
  -- thread from here.
  latest_response_id   text,
  -- Optimistic lock, same discipline as analysis_session: a generation records its result
  -- WHERE version matches, so two concurrent generations cannot silently clobber the head.
  version              int  NOT NULL DEFAULT 0,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_planning_project_feature ON autwit.planning_project (feature_key);
CREATE INDEX idx_planning_project_created ON autwit.planning_project (created_at DESC);

-- ============================================================ source_document

-- The corpus. Uploaded docs, pasted text, and Jira/Confluence items fetched over MCP all
-- land here as one shape: extracted text. Pass 1 accepts Markdown/text/paste; PDF/DOCX
-- extraction (Tika, our side) is pass 2 — the column is already text, so that widening
-- needs no schema change.
CREATE TABLE autwit.source_document (
  document_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id     uuid NOT NULL REFERENCES autwit.planning_project ON DELETE CASCADE,
  source_type    text NOT NULL
                 CHECK (source_type IN ('upload','paste','jira','confluence')),
  -- Jira key / Confluence page id / uploaded filename. NULL for a raw paste.
  external_ref   text,
  title          text NOT NULL,
  mime           text,
  -- The extracted text the generation reads. NOT the raw bytes — pass 1 has none to keep
  -- (text/paste is its own raw form); a raw-blob column can join pass 2 with binary upload.
  text_content   text NOT NULL,
  -- Tester's include/exclude toggle in the picker. Only selected docs feed generation.
  selected       boolean NOT NULL DEFAULT true,
  -- sha256 over the §6.1 canonical form of text_content (our ContentHasher, nulls retained).
  -- Dedupes re-fetching the same Jira issue and re-uploading the same file within a project.
  content_hash   text NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  -- One copy of a given external item per project: re-fetching PAY-2481 updates, not doubles.
  UNIQUE (project_id, source_type, external_ref)
);

CREATE INDEX idx_source_document_project ON autwit.source_document (project_id);

-- ============================================================ generation

-- One generation is one async LLM job: a call to the orchestrator's generate_test_plan or
-- generate_test_data skill. It is its OWN durable job record (status/attempts/lease), NOT a
-- row in autwit.run — run is session-scoped (run.session_id NOT NULL) and planning has no
-- session. The columns mirror run's job-control so PlanningGenerationWorker can dequeue with
-- the same SKIP-LOCKED + lease discipline.
CREATE TABLE autwit.generation (
  generation_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id        uuid NOT NULL REFERENCES autwit.planning_project ON DELETE CASCADE,
  generation_type   text NOT NULL
                    CHECK (generation_type IN ('test_plan','test_data')),
  status            text NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','running','succeeded','failed','cancelled')),
  -- Generation inputs the worker needs to rebuild the skill request: for test_data the
  -- selected scenarios, edge_cases, rows_per_scenario, example_record; empty for test_plan
  -- (which reads the whole selected corpus).
  config            jsonb NOT NULL DEFAULT '{}',
  -- OpenAI chaining token echoed by the skill; pinned onto the project head on success.
  response_id       text,
  -- Job control (mirrors autwit.run). max_attempts 1: a generation is a ~40-57s OpenAI round
  -- trip with a side effect on hosted state, so a dead worker's job is NOT auto-retried — a
  -- re-run is a deliberate act, same posture as FINANCIAL_ANALYSIS.
  attempts          int  NOT NULL DEFAULT 0,
  max_attempts      int  NOT NULL DEFAULT 1,
  worker_id         text,
  lease_expires_at  timestamptz,
  error             jsonb,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_generation_project ON autwit.generation (project_id, created_at DESC);
-- The worker's dequeue predicate: pending jobs, and running jobs whose lease expired.
CREATE INDEX idx_generation_claimable ON autwit.generation (status, lease_expires_at);

-- ============================================================ test_plan + test_scenario

-- The Step-3 deliverable. One test_plan per generation (a regenerate makes a new generation
-- and a new plan; history is kept).
CREATE TABLE autwit.test_plan (
  test_plan_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id     uuid NOT NULL REFERENCES autwit.planning_project ON DELETE CASCADE,
  generation_id  uuid NOT NULL REFERENCES autwit.generation ON DELETE CASCADE,
  overview       text,
  scope          text,
  -- Which tickets/pages/doc-version it was built from — the doc header's provenance line.
  provenance     jsonb NOT NULL DEFAULT '{}',
  created_at     timestamptz NOT NULL DEFAULT now(),
  -- One plan per generation.
  UNIQUE (generation_id)
);

CREATE INDEX idx_test_plan_project ON autwit.test_plan (project_id, created_at DESC);

-- The scenario rows are a child table, not jsonb on test_plan, because Step 4 references
-- them directly (carry-forward checklist, per-scenario data generation keys off scenario_key).
CREATE TABLE autwit.test_scenario (
  test_plan_id   uuid NOT NULL REFERENCES autwit.test_plan ON DELETE CASCADE,
  scenario_key   text NOT NULL,                       -- TC-01, TC-02, …
  seq            int  NOT NULL,
  title          text NOT NULL,
  priority       text CHECK (priority IN ('High','Medium','Low')),
  source         text,                                -- PAY-2481, "Design doc v3", …
  PRIMARY KEY (test_plan_id, scenario_key)
);

-- ============================================================ test_dataset

-- The Step-4 deliverable: one row per (generation, scenario) — the tabbed output, one tab
-- per scenario. columns and rows are stored as jsonb so any scenario shape is representable
-- without a per-feature schema.
CREATE TABLE autwit.test_dataset (
  dataset_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id     uuid NOT NULL REFERENCES autwit.planning_project ON DELETE CASCADE,
  generation_id  uuid NOT NULL REFERENCES autwit.generation ON DELETE CASCADE,
  scenario_key   text NOT NULL,
  columns        jsonb NOT NULL DEFAULT '[]',
  rows           jsonb NOT NULL DEFAULT '[]',
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (generation_id, scenario_key)
);

CREATE INDEX idx_test_dataset_project ON autwit.test_dataset (project_id, created_at DESC);
