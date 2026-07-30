-- V6__artifact_type_skill_deliverables.sql
-- Admit the remaining real-skill artifact types into autwit.artifact.
--
-- V5 added order_fulfilment after order.fulfil failed to persist in live testing
-- (2026-07-30). Enumerating what every orchestrator skill actually emits showed the same
-- gap for every skill added after the V1 vocabulary was frozen — V5 fixed one symptom of a
-- systemic omission. The artifact_type CHECK admitted only the original set
--   rdbms_table, dynamo_doc, event_batch, api_response, xml_payload,
--   log, diff_report, analysis, final_report, other  (+ order_fulfilment in V5)
-- but the live skill surface also returns:
--
--   comparison          compare.cross_system   the reconciliation verdict + findings (json)
--   db_snapshot         compare.cross_system   the downloaded Order/Shipment/PickPack rows (json)
--   comparison_report   compare.cross_system   the rendered HTML comparison report
--   financial_analysis  financial.analyze_*    the analyser result when invoked via the skill
--                                              surface (the evidence-picker path posts to
--                                              /v1/financial-analysis and stores findings, not
--                                              an artifact — but the skill path emits this type)
--   order_placement     order.place            the placed-order deliverable
--
-- Same failure mode as V5: each returns HTTP 200 (and, for the mutating skills, real side
-- effects) and then ArtifactService.persist() rejects the row on the CHECK, so RunWorker
-- marks a genuinely-succeeded run failed. The fake fixtures never exercised these literal
-- types (they reuse rdbms_table/dynamo_doc), so only the real skills surface the gap.
--
-- Postgres has no ALTER CHECK: drop and recreate with the full skill vocabulary. This list
-- is the union of the original set, V5, and every artifact_type the orchestrator source
-- emits as of catalog v1/693ede402294 (13 skills).

ALTER TABLE autwit.artifact DROP CONSTRAINT artifact_artifact_type_check;

ALTER TABLE autwit.artifact ADD CONSTRAINT artifact_artifact_type_check
  CHECK (artifact_type IN
    ('rdbms_table','dynamo_doc','event_batch','api_response',
     'xml_payload','log','diff_report','analysis','final_report','other',
     'order_fulfilment','order_placement',
     'comparison','comparison_report','db_snapshot',
     'financial_analysis'));
