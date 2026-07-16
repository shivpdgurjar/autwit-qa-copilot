-- V1__init.sql
-- AutWit Copilot API — initial schema
--
-- Design notes:
--   * autwit-copilot-api is the ONLY writer to this schema.
--   * Everything cascades from session_id. Cleanup is a single knob.
--   * autwit.run doubles as the job queue (SKIP LOCKED). No Redis, no SQS.
--   * No stream_event table: the orchestrator returns one payload at the end,
--     so there is nothing mid-stream to replay. Timeline is queried from
--     step/artifact/finding directly.
--   * Artifact bodies are inline in v0.1. external_uri exists but is unused
--     until pre-signed URIs land (SKILL_CONTRACT §7).

CREATE SCHEMA IF NOT EXISTS autwit;
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================ session

CREATE TABLE autwit.session (
  session_id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  correlation_id  text NOT NULL UNIQUE,
  tester_id       text NOT NULL,
  env             text NOT NULL,
  title           text,
  status          text NOT NULL DEFAULT 'active'
                  CHECK (status IN ('active','ended','abandoned','archived')),
  retention_class text NOT NULL DEFAULT 'standard'
                  CHECK (retention_class IN ('ephemeral','standard','pinned')),
  started_at      timestamptz NOT NULL DEFAULT now(),
  ended_at        timestamptz,
  expires_at      timestamptz NOT NULL DEFAULT now() + interval '7 days',
  tags            jsonb NOT NULL DEFAULT '{}',
  subjects        jsonb NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_session_status_expiry ON autwit.session (status, expires_at);
CREATE INDEX idx_session_tester        ON autwit.session (tester_id, started_at DESC);
CREATE INDEX idx_session_subjects      ON autwit.session USING gin (subjects jsonb_path_ops);

COMMENT ON COLUMN autwit.session.correlation_id IS
  'Propagated downstream as X-Autwit-Correlation-Id. The join key for all event analysis.';
COMMENT ON COLUMN autwit.session.subjects IS
  'Business ids under test, e.g. {"order_id":"XXXX"}. GIN-indexed so bug repros are findable.';

-- ============================================================ step

CREATE TABLE autwit.step (
  step_id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id     uuid NOT NULL REFERENCES autwit.session ON DELETE CASCADE,
  seq            int  NOT NULL,
  kind           text NOT NULL CHECK (kind IN
                   ('user_utterance','skill_invocation','milestone','analysis','system')),
  label          text,
  actor          text NOT NULL DEFAULT 'agent' CHECK (actor IN ('user','agent','system')),
  status         text NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending','running','succeeded','failed','skipped')),
  started_at     timestamptz NOT NULL DEFAULT now(),
  ended_at       timestamptz,
  parent_step_id uuid REFERENCES autwit.step ON DELETE CASCADE,
  detail         jsonb NOT NULL DEFAULT '{}',
  UNIQUE (session_id, seq)
);

CREATE INDEX idx_step_session_seq ON autwit.step (session_id, seq);

-- seq allocation: one sequence per session, assigned under the session row lock.
-- Do NOT use a global sequence; gaps are fine but ordering must be per-session.
CREATE OR REPLACE FUNCTION autwit.next_step_seq(p_session uuid)
RETURNS int LANGUAGE sql AS $$
  SELECT COALESCE(MAX(seq), 0) + 1 FROM autwit.step WHERE session_id = p_session;
$$;

-- ============================================================ skill (read-only projection)

CREATE TABLE autwit.skill (
  skill_name    text PRIMARY KEY,
  version       text NOT NULL,
  title         text,
  description   text,
  impl_type     text NOT NULL CHECK (impl_type IN ('prompt','shell','http','composite')),
  side_effects  text NOT NULL DEFAULT 'none' CHECK (side_effects IN ('none','mutating')),
  input_schema  jsonb NOT NULL,
  output_schema jsonb,
  enabled       boolean NOT NULL DEFAULT true,
  synced_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE autwit.skill_catalog_sync (
  id              int PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  catalog_version text NOT NULL,
  synced_at       timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE autwit.skill IS
  'Read-only projection of the orchestrator catalog (GET /skills). Never edit here — '
  'skill definitions live in the orchestrator repo as versioned YAML.';

-- ============================================================ run (also the queue)

CREATE TABLE autwit.run (
  run_id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id       uuid NOT NULL REFERENCES autwit.session ON DELETE CASCADE,
  step_id          uuid NOT NULL REFERENCES autwit.step ON DELETE CASCADE,
  run_type         text NOT NULL CHECK (run_type IN
                     ('invoke','skill_execute','milestone','comparison','report')),
  status           text NOT NULL DEFAULT 'queued' CHECK (status IN
                     ('queued','running','succeeded','failed','cancelled','timed_out')),
  request          jsonb NOT NULL,
  progress         jsonb NOT NULL DEFAULT '{}',
  result_summary   jsonb,
  error            jsonb,
  attempts         int NOT NULL DEFAULT 0,
  max_attempts     int NOT NULL DEFAULT 1,
  cancel_requested boolean NOT NULL DEFAULT false,
  idempotency_key  text,
  lease_until      timestamptz,
  worker_id        text,
  queued_at        timestamptz NOT NULL DEFAULT now(),
  started_at       timestamptz,
  ended_at         timestamptz
);

-- dequeue path
CREATE INDEX idx_run_queued  ON autwit.run (queued_at) WHERE status = 'queued';
-- reaper path
CREATE INDEX idx_run_lease   ON autwit.run (lease_until) WHERE status = 'running';
CREATE INDEX idx_run_session ON autwit.run (session_id, queued_at DESC);

CREATE UNIQUE INDEX uq_run_idempotency
  ON autwit.run (session_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN autwit.run.max_attempts IS
  'Stays 1 for mutating skills. Never auto-retry a run that may have placed an order.';
COMMENT ON COLUMN autwit.run.lease_until IS
  'Set to now()+12m — MUST exceed the 10m HTTP client timeout, or a slow-but-alive '
  'run gets reclaimed and re-executed while still running.';

-- ============================================================ skill_invocation

CREATE TABLE autwit.skill_invocation (
  invocation_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id    uuid NOT NULL REFERENCES autwit.session ON DELETE CASCADE,
  step_id       uuid NOT NULL REFERENCES autwit.step ON DELETE CASCADE,
  run_id        uuid REFERENCES autwit.run ON DELETE CASCADE,
  skill_name    text NOT NULL,
  skill_version text NOT NULL,
  input         jsonb NOT NULL,
  output_ref    uuid,
  output_inline jsonb,
  status        text NOT NULL,
  error         jsonb,
  exit_code     int,
  duration_ms   int,
  started_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_invocation_session ON autwit.skill_invocation (session_id, started_at);
CREATE INDEX idx_invocation_step    ON autwit.skill_invocation (step_id);

-- No FK to autwit.skill: the catalog is a cache and may lag behind a skill
-- that was renamed or disabled mid-session. Historical invocations must survive.

-- ============================================================ milestone

CREATE TABLE autwit.milestone (
  milestone_id  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id    uuid NOT NULL REFERENCES autwit.session ON DELETE CASCADE,
  step_id       uuid NOT NULL REFERENCES autwit.step ON DELETE CASCADE,
  name          text NOT NULL,
  seq           int  NOT NULL,
  status        text NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending','complete','partial','failed')),
  marked_at     timestamptz NOT NULL DEFAULT now(),
  snapshot_id   uuid,
  event_cursor  jsonb NOT NULL DEFAULT '{}',
  note          text,
  UNIQUE (session_id, name)
);

CREATE INDEX idx_milestone_session ON autwit.milestone (session_id, seq);

COMMENT ON COLUMN autwit.milestone.event_cursor IS
  'Per-topic offsets: {"orders.events":{"0":10432}}. Analysis reads offset windows, '
  'not time windows — time windows are lossy under load.';

-- ============================================================ artifact

CREATE TABLE autwit.artifact (
  artifact_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id     uuid NOT NULL REFERENCES autwit.session ON DELETE CASCADE,
  step_id        uuid REFERENCES autwit.step ON DELETE CASCADE,
  milestone_id   uuid REFERENCES autwit.milestone ON DELETE CASCADE,
  run_id         uuid REFERENCES autwit.run ON DELETE SET NULL,

  artifact_type  text NOT NULL CHECK (artifact_type IN
                   ('rdbms_table','dynamo_doc','event_batch','api_response',
                    'xml_payload','log','diff_report','analysis','final_report','other')),
  source_system  text NOT NULL,
  logical_name   text NOT NULL,
  format         text NOT NULL CHECK (format IN
                   ('json','jsonb','xml','text','csv','html','md','binary')),

  body_jsonb     jsonb,
  body_text      text,
  body_bytes     bytea,
  external_uri   text,

  content_hash   text NOT NULL,
  size_bytes     bigint NOT NULL,
  row_count      int,
  captured_at    timestamptz NOT NULL DEFAULT now(),
  purged_at      timestamptz,
  meta           jsonb NOT NULL DEFAULT '{}',

  CONSTRAINT one_body CHECK (
    num_nonnulls(body_jsonb, body_text, body_bytes, external_uri) <= 1
  ),
  CONSTRAINT body_present_unless_purged CHECK (
    purged_at IS NOT NULL
    OR num_nonnulls(body_jsonb, body_text, body_bytes, external_uri) = 1
  )
);

ALTER TABLE autwit.artifact ALTER COLUMN body_jsonb SET STORAGE EXTENDED;
ALTER TABLE autwit.artifact ALTER COLUMN body_text  SET STORAGE EXTENDED;

CREATE INDEX idx_artifact_session_type ON autwit.artifact (session_id, artifact_type, logical_name);
CREATE INDEX idx_artifact_milestone    ON autwit.artifact (milestone_id);
CREATE INDEX idx_artifact_step         ON autwit.artifact (step_id);
CREATE INDEX idx_artifact_hash         ON autwit.artifact (content_hash);
CREATE INDEX idx_artifact_body_gin     ON autwit.artifact USING gin (body_jsonb jsonb_path_ops)
  WHERE body_jsonb IS NOT NULL;

COMMENT ON COLUMN autwit.artifact.meta IS
  'pk_columns (REQUIRED for rdbms_table — diff engine needs it), query, '
  'masked_columns, ignore_columns.';
COMMENT ON CONSTRAINT one_body ON autwit.artifact IS
  'Mirrors the ArtifactDescriptor contract: body XOR external_uri. <=1 rather than =1 '
  'so retention can null out bodies while keeping the row.';

-- ============================================================ snapshot

CREATE TABLE autwit.snapshot (
  snapshot_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id     uuid NOT NULL REFERENCES autwit.session ON DELETE CASCADE,
  milestone_id   uuid REFERENCES autwit.milestone ON DELETE CASCADE,
  step_id        uuid REFERENCES autwit.step ON DELETE CASCADE,
  label          text NOT NULL,
  scope          text NOT NULL,
  scope_def      jsonb NOT NULL DEFAULT '{}',
  status         text NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending','complete','partial','failed')),
  captured_at    timestamptz NOT NULL DEFAULT now(),
  composite_hash text
);

CREATE INDEX idx_snapshot_session   ON autwit.snapshot (session_id, captured_at);
CREATE INDEX idx_snapshot_milestone ON autwit.snapshot (milestone_id);

CREATE TABLE autwit.snapshot_part (
  snapshot_id  uuid NOT NULL REFERENCES autwit.snapshot ON DELETE CASCADE,
  part_key     text NOT NULL,
  artifact_id  uuid NOT NULL REFERENCES autwit.artifact ON DELETE CASCADE,
  PRIMARY KEY (snapshot_id, part_key)
);

CREATE INDEX idx_snapshot_part_artifact ON autwit.snapshot_part (artifact_id);

COMMENT ON COLUMN autwit.snapshot_part.part_key IS
  'MUST be stable across snapshots of the same scope. Comparison is a key-wise join '
  'on this column. If order_flow yields oms.orders at step 2 it must yield oms.orders '
  'at step 5 — same string, always, even when the table is empty.';

ALTER TABLE autwit.milestone
  ADD CONSTRAINT fk_milestone_snapshot
  FOREIGN KEY (snapshot_id) REFERENCES autwit.snapshot ON DELETE SET NULL;

-- ============================================================ event_record

CREATE TABLE autwit.event_record (
  event_id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id         uuid NOT NULL REFERENCES autwit.session ON DELETE CASCADE,
  artifact_id        uuid REFERENCES autwit.artifact ON DELETE SET NULL,
  source             text NOT NULL,
  topic              text,
  event_type         text,
  event_key          text,
  source_offset      text,
  occurred_at        timestamptz,
  captured_at        timestamptz NOT NULL DEFAULT now(),
  after_milestone_id uuid REFERENCES autwit.milestone ON DELETE SET NULL,
  payload            jsonb NOT NULL,
  dedupe_hash        text NOT NULL,
  UNIQUE (session_id, dedupe_hash)
);

CREATE INDEX idx_event_session_time ON autwit.event_record (session_id, occurred_at);
CREATE INDEX idx_event_milestone    ON autwit.event_record (session_id, after_milestone_id);
CREATE INDEX idx_event_type         ON autwit.event_record (session_id, event_type);
CREATE INDEX idx_event_payload_gin  ON autwit.event_record USING gin (payload jsonb_path_ops);

COMMENT ON CONSTRAINT event_record_session_id_dedupe_hash_key ON autwit.event_record IS
  'This is how "events since step 2" works. The orchestrator re-reads from the cursor '
  'and returns everything; ON CONFLICT DO NOTHING makes the delta emerge for free.';

-- ============================================================ comparison / finding

CREATE TABLE autwit.comparison (
  comparison_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id       uuid NOT NULL REFERENCES autwit.session ON DELETE CASCADE,
  step_id          uuid REFERENCES autwit.step ON DELETE CASCADE,
  run_id           uuid REFERENCES autwit.run ON DELETE SET NULL,
  from_snapshot_id uuid NOT NULL REFERENCES autwit.snapshot ON DELETE CASCADE,
  to_snapshot_id   uuid NOT NULL REFERENCES autwit.snapshot ON DELETE CASCADE,
  compare_type     text NOT NULL CHECK (compare_type IN
                     ('structural','financial_validation','consistency')),
  rules            jsonb NOT NULL DEFAULT '{}',
  verdict          text CHECK (verdict IN ('pass','fail','warn','inconclusive')),
  summary          text,
  report_ref       uuid REFERENCES autwit.artifact ON DELETE SET NULL,
  part_results     jsonb NOT NULL DEFAULT '[]',
  created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_comparison_session ON autwit.comparison (session_id, created_at);

COMMENT ON COLUMN autwit.comparison.part_results IS
  'Per-part counts plus ignored_columns. ignored_columns is surfaced in the UI, never '
  'applied silently — if updated_at diffs vanish without explanation, nobody trusts it.';

CREATE TABLE autwit.finding (
  finding_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id    uuid NOT NULL REFERENCES autwit.session ON DELETE CASCADE,
  comparison_id uuid REFERENCES autwit.comparison ON DELETE CASCADE,
  step_id       uuid REFERENCES autwit.step ON DELETE CASCADE,
  severity      text NOT NULL CHECK (severity IN ('info','low','medium','high','critical')),
  category      text,
  part_key      text,
  entity_key    text,
  field         text,
  before_value  jsonb,
  after_value   jsonb,
  message       text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_finding_session  ON autwit.finding (session_id, created_at DESC);
CREATE INDEX idx_finding_severity ON autwit.finding (session_id, severity);

-- ============================================================ agent_memory

CREATE TABLE autwit.agent_memory (
  session_id uuid NOT NULL REFERENCES autwit.session ON DELETE CASCADE,
  agent_id   text NOT NULL,
  key        text NOT NULL,
  value      jsonb NOT NULL,
  ttl_at     timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (session_id, agent_id, key)
);

CREATE INDEX idx_agent_memory_ttl ON autwit.agent_memory (ttl_at) WHERE ttl_at IS NOT NULL;

-- ============================================================ operational SQL
-- Reference implementations. The Java side issues these verbatim.

-- Dequeue. Reclaims runs whose worker died (lease expired).
--   UPDATE autwit.run SET
--     status='running', worker_id=:workerId, attempts=attempts+1,
--     started_at=COALESCE(started_at, now()),
--     lease_until=now() + interval '12 minutes'
--   WHERE run_id = (
--     SELECT run_id FROM autwit.run
--     WHERE status='queued'
--        OR (status='running' AND lease_until < now())
--     ORDER BY queued_at
--     FOR UPDATE SKIP LOCKED LIMIT 1
--   )
--   RETURNING *;

-- Per-session serialization. Held by the worker for the run's duration.
-- Runs in one session must not interleave: a snapshot at step 5 must not race
-- one at step 2. If not acquired, skip and take the next run.
--   SELECT pg_try_advisory_lock(hashtext('autwit:session:' || :sessionId));
--   SELECT pg_advisory_unlock(hashtext('autwit:session:' || :sessionId));

-- Reaper (@Scheduled every 60s). Catches workers that died outright; a worker
-- that merely times out marks itself.
--   UPDATE autwit.run
--   SET status='timed_out', ended_at=now(),
--       error=jsonb_build_object('code','lease_expired','worker_id',worker_id)
--   WHERE status='running' AND lease_until < now();
--
-- timed_out != failed: outcome is UNKNOWN. Never auto-retry.

-- Notify (thin hint; truth is always GET /sessions/{id}).
-- Payload cap is 8000 bytes and it's fire-and-forget — fine, because a dropped
-- notification just means the user refreshes.
--   SELECT pg_notify('autwit_run',
--     json_build_object('session_id', :s, 'run_id', :r, 'status', :st, 'type', :t)::text);

-- ============================================================ retention

-- 1. soft expire
--   UPDATE autwit.session SET status='archived'
--   WHERE status='ended' AND retention_class <> 'pinned' AND expires_at < now();

-- 2. purge bodies, keep the trace and the reports
--   UPDATE autwit.artifact a
--   SET body_jsonb=NULL, body_text=NULL, body_bytes=NULL, purged_at=now()
--   FROM autwit.session s
--   WHERE a.session_id=s.session_id AND s.status='archived' AND a.purged_at IS NULL
--     AND a.artifact_type NOT IN ('final_report','diff_report');

-- 3. hard delete
--   DELETE FROM autwit.session
--   WHERE status='archived' AND retention_class='ephemeral'
--     AND expires_at < now() - interval '30 days';

-- Partitioning: if volume demands it, partition artifact and event_record
-- BY RANGE (captured_at) monthly. Cleanup becomes DROP PARTITION instead of
-- DELETE — seconds instead of hours. Not needed on day one.
