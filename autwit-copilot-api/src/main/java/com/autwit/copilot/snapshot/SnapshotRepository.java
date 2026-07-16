package com.autwit.copilot.snapshot;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.common.Columns;
import com.autwit.copilot.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SnapshotRepository {

    private final JdbcTemplate jdbc;
    private final Json json;
    private final RowMapper<Snapshot> mapper;

    public SnapshotRepository(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new Snapshot(
                Columns.uuid(rs, "snapshot_id"),
                Columns.uuid(rs, "session_id"),
                Columns.uuid(rs, "milestone_id"),
                Columns.uuid(rs, "step_id"),
                rs.getString("label"),
                rs.getString("scope"),
                json.readObject(rs.getString("scope_def")),
                rs.getString("status"),
                Columns.instant(rs, "captured_at"),
                rs.getString("composite_hash"),
                List.of());
    }

    public UUID insert(UUID sessionId, UUID milestoneId, UUID stepId, String label, String scope,
            Map<String, Object> scopeDef, String status, String compositeHash) {
        return jdbc.queryForObject(
                """
                insert into autwit.snapshot
                  (session_id, milestone_id, step_id, label, scope, scope_def, status, composite_hash)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                returning snapshot_id
                """,
                UUID.class, sessionId, milestoneId, stepId, label, scope,
                json.writeOrEmptyObject(scopeDef), status, compositeHash);
    }

    public void insertPart(UUID snapshotId, String partKey, UUID artifactId) {
        jdbc.update(
                "insert into autwit.snapshot_part (snapshot_id, part_key, artifact_id) values (?, ?, ?)",
                snapshotId, partKey, artifactId);
    }

    public List<Snapshot> listBySession(UUID sessionId) {
        var snapshots = jdbc.query(
                "select * from autwit.snapshot where session_id = ? order by captured_at", mapper, sessionId);
        return snapshots.stream().map(s -> s.withParts(parts(s.snapshotId()))).toList();
    }

    public Snapshot find(UUID snapshotId) {
        var found = jdbc.query("select * from autwit.snapshot where snapshot_id = ?", mapper, snapshotId);
        return found.isEmpty() ? null : found.get(0).withParts(parts(snapshotId));
    }

    /** Joined to artifact so the UI gets row_count and content_hash without a second call. */
    public List<Snapshot.Part> parts(UUID snapshotId) {
        return jdbc.query(
                """
                select sp.part_key, sp.artifact_id, a.row_count, a.content_hash
                from autwit.snapshot_part sp
                join autwit.artifact a on a.artifact_id = sp.artifact_id
                where sp.snapshot_id = ?
                order by sp.part_key
                """,
                (rs, n) -> new Snapshot.Part(
                        rs.getString("part_key"),
                        Columns.uuid(rs, "artifact_id"),
                        Columns.integer(rs, "row_count"),
                        rs.getString("content_hash")),
                snapshotId);
    }
}
