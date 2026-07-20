-- V2__financial_analysis_session.sql
-- Financial-analysis session ownership (SKILL_CONTRACT §0 invariants 2 & 4;
-- message-to-qa-copilot/v1.0.16, accepted in message-from-qa-copilot/v1.0.17).
--
-- The orchestrator's financial analyser is stateless — every call carries its states.
-- The durable side of a LIFECYCLE_COMPARISON (which states, in what order, under which
-- rule/prompt versions, and the OpenAI chaining token) must therefore live here: we are
-- the sole writer to this schema, and a second stateful store on the orchestrator would
-- make its v1.0.6 JobStore caveat load-bearing instead of incidental.
--
-- Crux of our design (v1.0.17 §3): a state is NOT tester-keyed input. The tester selects
-- persisted session evidence (fetch_order responses, captured events, snapshots, uploaded
-- artifacts); copilot-api projects each into a StateEnvelope and stores it here. So
-- analysis_state.payload is a PROJECTION of evidence, and payload_hash is that projection
-- under the §6.1 canonical hasher (nulls retained — the V7/V8 fix). Same body → same hash
-- → the UNIQUE guard below dedupes a re-run on unchanged evidence for free.

-- ============================================================ analysis_session

CREATE TABLE autwit.analysis_session (
  analysis_id        text PRIMARY KEY,
  session_id         uuid NOT NULL REFERENCES autwit.session ON DELETE CASCADE,
  order_number       text NOT NULL,
  analysis_mode      text NOT NULL
                     CHECK (analysis_mode IN ('SNAPSHOT_SANCTITY','LIFECYCLE_COMPARISON')),
  -- OpenAI Responses chaining token. NULL is valid and expected: it is a cache, never a
  -- dependency (v1.0.16 §4). A missing/expired/rejected id degrades to a full re-read,
  -- never fails the analysis — the same rule as C4's event cursor.
  latest_response_id text,
  last_sequence      int  NOT NULL DEFAULT 0,
  -- The versions a verdict was produced under. Pinned so a replay recomputes identically.
  prompt_version     text NOT NULL,
  rule_version       text NOT NULL,
  -- Optimistic lock. Two events chaining from the same latest_response_id concurrently is
  -- the failure it prevents; the writer reads version, computes, and updates WHERE version
  -- matches, 409ing on mismatch (the machinery ADR-001 / RunEnqueuer already use).
  version            int  NOT NULL DEFAULT 0,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_analysis_session_session ON autwit.analysis_session (session_id);
CREATE INDEX idx_analysis_session_order   ON autwit.analysis_session (order_number);

-- ============================================================ analysis_state

CREATE TABLE autwit.analysis_state (
  analysis_id     text NOT NULL REFERENCES autwit.analysis_session ON DELETE CASCADE,
  sequence        int  NOT NULL,
  label           text NOT NULL,
  -- The nine StateType values the orchestrator's engine keys off (financial/domain/
  -- types.ts). Inferred by our projection from the evidence's source, overridable by the
  -- tester in the picker, and constrained here so a bad projection fails loudly.
  state_type      text NOT NULL
                  CHECK (state_type IN (
                    'ORDER_SNAPSHOT','API_REQUEST','API_RESPONSE','DOMAIN_EVENT',
                    'INVOICE_SNAPSHOT','PAYMENT_SNAPSHOT','REFUND_EVENT',
                    'CALCULATION_RESULT','OTHER')),
  lifecycle_stage text NOT NULL,
  source          text NOT NULL,
  captured_at     timestamptz,
  -- sha256 over the §6.1 canonical form of `payload`, computed by our ContentHasher.
  -- MUST retain nulls: {"refundId":null} and {} are different events (V7/V8).
  payload_hash    text NOT NULL,
  payload         jsonb NOT NULL,
  PRIMARY KEY (analysis_id, sequence),
  -- One label per analysis: the tester's name for a state is unique within it.
  UNIQUE (analysis_id, label),
  -- The idempotency guard. The same refund event delivered twice must not become two
  -- states; the same evidence re-selected produces the same hash and is dropped.
  UNIQUE (analysis_id, payload_hash)
);

-- Fixes C6 in passing: V1__init.sql:188's example still says the pre-v0.1.3 plural
-- `orders.events`. The comment has no runtime effect and V1 is immutable (Flyway
-- checksum), so it rides this migration — the first one we have had cause to write.
COMMENT ON COLUMN autwit.milestone.event_cursor IS
  'Per-topic cursors: {"order.events":{"0":1703000009000}}. The value is an opaque '
  'per-source ordering token (SKILL_CONTRACT §6.3), not a Kafka offset — for '
  'events.capture_since it is a producerTime in epoch millis. Analysis reads offset '
  'windows, not time windows: time windows are lossy under load.';
