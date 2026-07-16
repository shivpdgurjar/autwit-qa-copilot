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
public class MilestoneRepository {

    private final JdbcTemplate jdbc;
    private final Json json;
    private final RowMapper<Milestone> mapper;

    public MilestoneRepository(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new Milestone(
                Columns.uuid(rs, "milestone_id"),
                Columns.uuid(rs, "session_id"),
                Columns.uuid(rs, "step_id"),
                rs.getString("name"),
                rs.getInt("seq"),
                rs.getString("status"),
                Columns.instant(rs, "marked_at"),
                Columns.uuid(rs, "snapshot_id"),
                json.readObject(rs.getString("event_cursor")),
                rs.getString("note"));
    }

    /** Created in 'pending' — the worker fills in snapshot_id and flips status (openapi.yaml). */
    public Milestone insertPending(UUID sessionId, UUID stepId, String name, int seq, String note) {
        return jdbc.queryForObject(
                """
                insert into autwit.milestone (session_id, step_id, name, seq, status, note)
                values (?, ?, ?, ?, 'pending', ?)
                returning *
                """,
                mapper, sessionId, stepId, name, seq, note);
    }

    public Optional<Milestone> find(UUID milestoneId) {
        return jdbc.query("select * from autwit.milestone where milestone_id = ?", mapper, milestoneId)
                .stream().findFirst();
    }

    public List<Milestone> listBySession(UUID sessionId) {
        return jdbc.query(
                "select * from autwit.milestone where session_id = ? order by seq", mapper, sessionId);
    }

    public boolean existsByName(UUID sessionId, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from autwit.milestone where session_id = ? and name = ?)",
                Boolean.class, sessionId, name));
    }

    public void complete(UUID milestoneId, UUID snapshotId, Map<String, Object> eventCursor, String status) {
        jdbc.update(
                """
                update autwit.milestone
                set snapshot_id = ?, event_cursor = coalesce(?::jsonb, event_cursor), status = ?
                where milestone_id = ?
                """,
                snapshotId, json.write(eventCursor), status, milestoneId);
    }

    public int nextSeq(UUID sessionId) {
        return jdbc.queryForObject(
                "select coalesce(max(seq), 0) + 1 from autwit.milestone where session_id = ?",
                Integer.class, sessionId);
    }
}
