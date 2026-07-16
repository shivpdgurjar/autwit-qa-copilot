package com.autwit.copilot.session;

import java.time.Instant;
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
public class SessionRepository {

    private final JdbcTemplate jdbc;
    private final Json json;
    private final RowMapper<Session> mapper;

    public SessionRepository(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new Session(
                Columns.uuid(rs, "session_id"),
                rs.getString("correlation_id"),
                rs.getString("tester_id"),
                rs.getString("env"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("retention_class"),
                Columns.instant(rs, "started_at"),
                Columns.instant(rs, "ended_at"),
                Columns.instant(rs, "expires_at"),
                json.readObject(rs.getString("tags")),
                json.readStringMap(rs.getString("subjects")));
    }

    public Session insert(
            String correlationId,
            String testerId,
            String env,
            String title,
            String retentionClass,
            int ttlDays,
            Map<String, Object> tags,
            Map<String, String> subjects) {

        return jdbc.queryForObject(
                """
                insert into autwit.session
                  (correlation_id, tester_id, env, title, retention_class, expires_at, tags, subjects)
                values (?, ?, ?, ?, ?, now() + make_interval(days => ?), ?::jsonb, ?::jsonb)
                returning *
                """,
                mapper,
                correlationId, testerId, env, title, retentionClass, ttlDays,
                json.writeOrEmptyObject(tags), json.writeOrEmptyObject(subjects));
    }

    public Optional<Session> find(UUID sessionId) {
        return jdbc.query("select * from autwit.session where session_id = ?", mapper, sessionId)
                .stream().findFirst();
    }

    public List<Session> list(String status, String testerId, String env, int limit) {
        // A null filter matches everything: `? is null or col = ?` keeps this one
        // statement instead of four, and Postgres folds the constant at plan time.
        return jdbc.query(
                """
                select * from autwit.session
                where (?::text is null or status = ?)
                  and (?::text is null or tester_id = ?)
                  and (?::text is null or env = ?)
                order by started_at desc
                limit ?
                """,
                mapper,
                status, status, testerId, testerId, env, env, limit);
    }

    /**
     * Partial update. Every field is optional, so each column keeps its current
     * value when the argument is null — coalesce does that in one statement without
     * building SQL by hand.
     *
     * <p>subjects is merged rather than replaced ({@code ||}): a PATCH that carries
     * one new subject must not drop the ones already discovered.
     */
    public Optional<Session> update(
            UUID sessionId, String title, Map<String, Object> tags,
            Map<String, String> subjects, String retentionClass) {

        return jdbc.query(
                """
                update autwit.session set
                  title           = coalesce(?, title),
                  tags            = coalesce(?::jsonb, tags),
                  subjects        = subjects || coalesce(?::jsonb, '{}'::jsonb),
                  retention_class = coalesce(?, retention_class)
                where session_id = ?
                returning *
                """,
                mapper,
                title, json.write(tags), json.write(subjects), retentionClass, sessionId)
                .stream().findFirst();
    }

    /** Merges discovered subjects into session.subjects (SKILL_CONTRACT §5). */
    public void mergeSubjects(UUID sessionId, Map<String, String> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            return;
        }
        jdbc.update(
                "update autwit.session set subjects = subjects || ?::jsonb where session_id = ?",
                json.write(subjects), sessionId);
    }

    public Optional<Session> end(UUID sessionId, Instant endedAt) {
        return jdbc.query(
                """
                update autwit.session
                set status = 'ended', ended_at = ?
                where session_id = ? and status = 'active'
                returning *
                """,
                mapper, java.sql.Timestamp.from(endedAt), sessionId)
                .stream().findFirst();
    }

    public boolean delete(UUID sessionId) {
        return jdbc.update("delete from autwit.session where session_id = ?", sessionId) > 0;
    }

    public boolean existsByCorrelationId(String correlationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from autwit.session where correlation_id = ?)",
                Boolean.class, correlationId));
    }

    /** For SessionDetail.counts. Session-scoped aggregate, so it lives with the session read. */
    public int countEvents(UUID sessionId) {
        return jdbc.queryForObject(
                "select count(*) from autwit.event_record where session_id = ?", Integer.class, sessionId);
    }
}
