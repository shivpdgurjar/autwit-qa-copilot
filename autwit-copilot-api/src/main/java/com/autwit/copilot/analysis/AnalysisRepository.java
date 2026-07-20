package com.autwit.copilot.analysis;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.autwit.copilot.common.Columns;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The financial-analysis aggregate (V2): the {@code analysis_session} head and its
 * ordered {@code analysis_state} rows. copilot-api is the sole writer (§0 invariant 4).
 */
@Repository
public class AnalysisRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<AnalysisSession> sessionMapper;

    public AnalysisRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.sessionMapper = (rs, n) -> new AnalysisSession(
                rs.getString("analysis_id"),
                Columns.uuid(rs, "session_id"),
                rs.getString("order_number"),
                rs.getString("analysis_mode"),
                rs.getString("latest_response_id"),
                rs.getInt("last_sequence"),
                rs.getString("prompt_version"),
                rs.getString("rule_version"),
                rs.getInt("version"),
                Columns.instant(rs, "created_at"),
                Columns.instant(rs, "updated_at"));
    }

    // ---- session ---------------------------------------------------------------------

    public void createSession(AnalysisSession s) {
        jdbc.update(
                """
                insert into autwit.analysis_session
                  (analysis_id, session_id, order_number, analysis_mode, latest_response_id,
                   last_sequence, prompt_version, rule_version, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                s.analysisId(), s.sessionId(), s.orderNumber(), s.analysisMode(), s.latestResponseId(),
                s.lastSequence(), s.promptVersion(), s.ruleVersion(), s.version());
    }

    public Optional<AnalysisSession> findSession(String analysisId) {
        return jdbc.query("select * from autwit.analysis_session where analysis_id = ?", sessionMapper, analysisId)
                .stream().findFirst();
    }

    /**
     * Optimistic update of the session head after a run.
     *
     * <p>Updates only when {@code version} still matches what the caller read, bumping it
     * by one. Returns {@code false} when it did not match — a concurrent writer moved
     * first, which the caller surfaces as a 409 rather than silently clobbering. This is
     * the same discipline as the run lifecycle; the {@code version} column is the lock.
     *
     * @return true if this writer won, false on a lost race
     */
    public boolean bumpSession(String analysisId, int expectedVersion, int lastSequence, String latestResponseId) {
        int updated = jdbc.update(
                """
                update autwit.analysis_session
                   set last_sequence      = ?,
                       latest_response_id  = ?,
                       version             = version + 1,
                       updated_at          = now()
                 where analysis_id = ? and version = ?
                """,
                lastSequence, latestResponseId, analysisId, expectedVersion);
        return updated == 1;
    }

    // ---- states ----------------------------------------------------------------------

    /**
     * Appends one projected state.
     *
     * <p>{@code payload} is inserted verbatim as the canonical JSON the hash was computed
     * over, so the stored body and {@code payload_hash} can never disagree. The
     * {@code ON CONFLICT (analysis_id, payload_hash) DO NOTHING} makes a re-selection of
     * unchanged evidence idempotent — the same body hashes the same and is dropped. A
     * collision on {@code sequence} or {@code label} with a <em>different</em> payload is
     * NOT swallowed here; it surfaces as a constraint violation, because that is an
     * assembly bug, not a duplicate.
     *
     * @return true if the row was inserted, false if an identical payload_hash already existed
     */
    public boolean appendState(String analysisId, StateEnvelope state, String payloadHash,
            String payloadCanonicalJson) {
        int inserted = jdbc.update(
                """
                insert into autwit.analysis_state
                  (analysis_id, sequence, label, state_type, lifecycle_stage, source,
                   captured_at, payload_hash, payload)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                on conflict (analysis_id, payload_hash) do nothing
                """,
                analysisId, state.sequence(), state.label(), state.stateType().name(),
                state.lifecycleStage(), state.source().name(),
                state.capturedAt() == null ? null : java.sql.Timestamp.from(state.capturedAt()),
                payloadHash, payloadCanonicalJson);
        return inserted == 1;
    }

    /** States in analysis order — what a replay recomputes from (v1.0.16 §3). */
    public List<StoredState> listStates(String analysisId) {
        return jdbc.query(
                "select analysis_id, sequence, label, state_type, lifecycle_stage, source, "
                        + "captured_at, payload_hash from autwit.analysis_state "
                        + "where analysis_id = ? order by sequence",
                (rs, n) -> new StoredState(
                        rs.getString("analysis_id"),
                        rs.getInt("sequence"),
                        rs.getString("label"),
                        rs.getString("state_type"),
                        rs.getString("lifecycle_stage"),
                        rs.getString("source"),
                        Columns.instant(rs, "captured_at"),
                        rs.getString("payload_hash")),
                analysisId);
    }

    /** Metadata-only view of a persisted state (no payload — callers that need it join). */
    public record StoredState(
            String analysisId,
            int sequence,
            String label,
            String stateType,
            String lifecycleStage,
            String source,
            java.time.Instant capturedAt,
            String payloadHash) {
    }

    public int stateCount(String analysisId) {
        Integer c = jdbc.queryForObject(
                "select count(*) from autwit.analysis_state where analysis_id = ?", Integer.class, analysisId);
        return c == null ? 0 : c;
    }
}
