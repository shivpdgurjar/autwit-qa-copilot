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
     * Inserts with a server-allocated seq.
     *
     * <p>next_step_seq is evaluated inside the insert rather than read first: a
     * read-then-write would race two concurrent steps in the same session onto the
     * same seq, and UNIQUE (session_id, seq) would reject the loser. The unique
     * index still backstops this — it is the reason the race is a 409 and not a
     * corrupted timeline.
     */
    public Step insert(UUID sessionId, String kind, String label, String actor,
            String status, UUID parentStepId, Map<String, Object> detail) {

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
}
