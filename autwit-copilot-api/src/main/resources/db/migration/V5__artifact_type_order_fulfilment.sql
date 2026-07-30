-- V5__artifact_type_order_fulfilment.sql
-- Admit the `order.fulfil` skill's artifact into autwit.artifact.
--
-- Found in live integration testing (2026-07-30, order 3650430006 / shipment 0005445, qa3):
-- the real `order.fulfil` skill returns HTTP 200 with an artifact typed
--   artifact_type="order_fulfilment", source_system="club_fulfilment", format="json"
-- (the club BOPIC fulfilment summary — the 8-step pick/pack/stage result plus the
-- pick_pack DynamoDB writes it performed). V1's artifact_type CHECK enumerated
--   rdbms_table, dynamo_doc, event_batch, api_response, xml_payload, log,
--   diff_report, analysis, final_report, other
-- but NOT order_fulfilment, so ArtifactService.persist() failed the INSERT with
--   ERROR: new row for relation "artifact" violates check constraint
--          "artifact_artifact_type_check"
-- and RunWorker marked the run failed — even though the skill (and its real fulfilment
-- side effects) had already succeeded. The fake path never caught this: the
-- invoke_fulfilled.json fixture types its fulfilment rows as rdbms_table/dynamo_doc,
-- both already admitted, so only the REAL skill exercises the new type.
--
-- We keep the descriptive type rather than remapping the skill onto an existing one:
-- order_fulfilment is a first-class deliverable (its own evidence-picker category,
-- its own report section), and collapsing it into `other`/`dynamo_doc` would lose that
-- at query time. Postgres has no ALTER CHECK, so drop and recreate with the value added.

ALTER TABLE autwit.artifact DROP CONSTRAINT artifact_artifact_type_check;

ALTER TABLE autwit.artifact ADD CONSTRAINT artifact_artifact_type_check
  CHECK (artifact_type IN
    ('rdbms_table','dynamo_doc','event_batch','api_response',
     'xml_payload','log','diff_report','analysis','final_report','other',
     'order_fulfilment'));
