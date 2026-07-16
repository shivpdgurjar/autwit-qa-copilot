package com.autwit.copilot.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.autwit.copilot.common.Columns;
import com.autwit.copilot.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class StepRepository {

    /**
     * run_id is not a column on step; the FK runs the other way. The left join keeps
     * the API's Step shape honest without denormalising.
     */
    private static final String SELECT = """
            select s.*, r.run_id
            from autwit.step s
            left join autwit.run r on r.step_id = s.step_id
            """;

    private final JdbcTemplate jdbc;
    private final Json json;
    private final RowMapper<Step> mapper;

    public StepRepository(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new Step(
                Columns.uuid(rs, "step_id"),
                Columns.uuid(rs, "session_id"),
                rs.getInt("seq"),
                rs.getString("kind"),
                rs.getString("label"),
                rs.getString("actor"),
                rs.getString("status"),
                Columns.instant(rs, "started_at"),
                Columns.instant(rs, "ended_at"),
                Columns.uuid(rs, "parent_step_id"),
                json.readObject(rs.getString("detail")),
                Columns.uuid(rs, "run_id"));
    }

    /**
     * Inserts with a server-allocated seq, under the session row lock.
     *
     * <p>The lock is what V1__init.sql means by "seq allocation: one sequence per
     * session, assigned under the session row lock". Without it two concurrent inserts
     * in the same session both evaluate next_step_seq, both get the same number, and
     * UNIQUE (session_id, seq) rejects the loser with a 500. That is not hypothetical:
     * a tester double-clicking, or the worker writing an analysis step while the API
     * accepts the next message, is enough. The advisory lock does not help here — it
     * serializes runs, not API calls.
     *
     * <p>Contention is per session and lasts only as long as the enclosing transaction.
     * Steps in one session are inherently ordered anyway; that is what seq means.
     *
     * <p>@Transactional because the lock is worthless without one: outside a transaction
     * every statement autocommits and the row lock is dropped the instant it is taken.
     */
    @Transactional
    public Step insert(UUID sessionId, String kind, String label, String actor,
            String status, UUID parentStepId, Map<String, Object> detail) {

        lockSession(sessionId);

        var inserted = jdbc.queryForObject(
                """
                insert into autwit.step (session_id, seq, kind, label, actor, status, parent_step_id, detail)
                values (?, autwit.next_step_seq(?), ?, ?, ?, ?, ?, ?::jsonb)
                returning step_id
                """,
                UUID.class,
                sessionId, sessionId, kind, label, actor, status, parentStepId,
                json.writeOrEmptyObject(detail));

        return find(inserted).orElseThrow();
    }

    /**
     * Serializes seq allocation for a session. Re-taking it within the same transaction
     * costs nothing, so callers that already hold it lose nothing by asking again.
     */
    public void lockSession(UUID sessionId) {
        jdbc.query("select 1 from autwit.session where session_id = ? for update", rs -> null, sessionId);
    }

    public Optional<Step> find(UUID stepId) {
        return jdbc.query(SELECT + " where s.step_id = ?", mapper, stepId).stream().findFirst();
    }

    public List<Step> listBySession(UUID sessionId, Integer sinceSeq) {
        return jdbc.query(
                SELECT + " where s.session_id = ? and (?::int is null or s.seq > ?) order by s.seq",
                mapper, sessionId, sinceSeq, sinceSeq);
    }

    public int countBySession(UUID sessionId) {
        return jdbc.queryForObject(
                "select count(*) from autwit.step where session_id = ?", Integer.class, sessionId);
    }

    /** ended_at is set only on a terminal status, so a running step keeps a null end. */
    public void updateStatus(UUID stepId, String status) {
        jdbc.update(
                """
                update autwit.step
                set status = ?,
                    ended_at = case when ? in ('succeeded','failed','skipped') then now() else ended_at end
                where step_id = ?
                """,
                status, status, stepId);
    }
}
