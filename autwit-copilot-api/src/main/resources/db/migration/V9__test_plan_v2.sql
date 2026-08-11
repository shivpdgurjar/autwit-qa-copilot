-- Test Plan v2: the plan becomes a synthesizer output rather than a scenario summary.
--
-- The v1 shape (overview + scope text + {id,title,priority,source} rows) could not express
-- what a tester needs to execute a case. v2 groups cases under business capabilities and
-- gives each one an objective, lifecycle phase, preconditions, steps, expected results,
-- data requirements and traceability.
--
-- Existing rows are NOT migrated. They keep plan_version = 1 and read back through a
-- compatibility adapter (PlanningRepository.buildPlan), so old plans stay openable.

-- 1. Document role -----------------------------------------------------------------------
-- source_type says where a document CAME FROM (upload/jira/confluence). doc_role says what
-- it IS, which is what changes how the model must read it: requirements are the source of
-- truth, architecture explains assembly, and existing tests are evidence of intended
-- coverage that must NOT be copied out as new cases.
ALTER TABLE autwit.source_document
  ADD COLUMN doc_role text NOT NULL DEFAULT 'requirement'
    CHECK (doc_role IN ('requirement', 'architecture', 'existing_tests', 'domain_rules'));

-- 2. Project domain -----------------------------------------------------------------------
-- Selects the orchestrator's domain-context block. Only the KEY is stored and sent; the rules
-- themselves live orchestrator-side, so trusted instruction text never crosses the wire.
-- Null leaves a project domain-neutral, which is the correct default for a non-OES feature.
ALTER TABLE autwit.planning_project
  ADD COLUMN domain text;

-- 3. Plan ---------------------------------------------------------------------------------
ALTER TABLE autwit.test_plan
  -- The generator's full artifact body, kept verbatim. v1 decomposed the plan into columns
  -- and discarded the rest, so anything the mapper did not know about was unrecoverable.
  -- With this, adding a plan field later needs no migration.
  ADD COLUMN payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  -- 1 = legacy shallow plan, 2 = capability-grouped plan. Read path branches on this.
  ADD COLUMN plan_version int NOT NULL DEFAULT 1;

-- 4. Scenario rows ------------------------------------------------------------------------
-- Kept as a child table (not folded into payload) because Step 5 test-data generation keys
-- off scenario_key and test_dataset references it.
ALTER TABLE autwit.test_scenario
  ADD COLUMN capability              text,
  ADD COLUMN objective               text,
  ADD COLUMN lifecycle_phase         text,
  ADD COLUMN sources                 jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN requirement_ids         jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN preconditions           jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN steps                   jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN expected_results        jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN test_data_requirements  jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- Null when the material named no endpoint/page/table/topic to automate against.
  ADD COLUMN automation_mapping      jsonb;

-- Drop the priority CHECK. It turned any unexpected model value into a raw SQL violation
-- that failed the WHOLE generation after it had already been paid for; normalisation now
-- happens in the orchestrator's mapper (asPriority), which is tolerant by design.
ALTER TABLE autwit.test_scenario
  DROP CONSTRAINT IF EXISTS test_scenario_priority_check;

-- Grouping is how the plan is read, so index it.
CREATE INDEX idx_test_scenario_capability ON autwit.test_scenario (test_plan_id, capability);
