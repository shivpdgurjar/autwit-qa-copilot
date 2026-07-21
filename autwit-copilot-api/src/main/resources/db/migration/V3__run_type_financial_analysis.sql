-- V3__run_type_financial_analysis.sql
-- Allow the new RunType.FINANCIAL_ANALYSIS on autwit.run.
--
-- V1's run_type CHECK predates the financial-analysis feature. That analysis is a run like
-- any other (it goes through the same queue, lock and lease — SKILL_CONTRACT §0 "everything
-- that touches the orchestrator OR takes >1s is a run"), but it calls the orchestrator's
-- financial API, a surface V1 did not know about. V1 is applied and immutable (Flyway
-- checksum), so the CHECK is widened here rather than edited in place.

ALTER TABLE autwit.run DROP CONSTRAINT run_run_type_check;

ALTER TABLE autwit.run ADD CONSTRAINT run_run_type_check
  CHECK (run_type IN ('invoke','skill_execute','milestone','comparison','report','financial_analysis'));
