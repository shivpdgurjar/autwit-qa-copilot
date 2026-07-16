package com.autwit.copilot.artifact;

import java.nio.charset.StandardCharsets;
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
public class ArtifactRepository {

    private final JdbcTemplate jdbc;
    private final Json json;

    public ArtifactRepository(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Writes the body into exactly one of the three body columns, per the format's
     * family. Passing null for the other two is what satisfies the one_body check
     * constraint — the constraint is the enforcement, this is just the dispatch.
     */
    public Artifact insert(
            UUID sessionId, UUID stepId, UUID milestoneId, UUID runId,
            String artifactType, String sourceSystem, String logicalName, ArtifactFormat format,
            byte[] canonicalBody, String contentHash, Integer rowCount, Map<String, Object> meta) {

        String bodyJsonb = null;
        String bodyText = null;
        byte[] bodyBytes = null;

        switch (format.family()) {
            case JSON -> bodyJsonb = new String(canonicalBody, StandardCharsets.UTF_8);
            case TEXT -> bodyText = new String(canonicalBody, StandardCharsets.UTF_8);
            case BINARY -> bodyBytes = canonicalBody;
        }

        var id = jdbc.queryForObject(
                """
                insert into autwit.artifact
                  (session_id, step_id, milestone_id, run_id, artifact_type, source_system,
                   logical_name, format, body_jsonb, body_text, body_bytes,
                   content_hash, size_bytes, row_count, meta)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb)
                returning artifact_id
                """,
                UUID.class,
                sessionId, stepId, milestoneId, runId, artifactType, sourceSystem,
                logicalName, format.wire(), bodyJsonb, bodyText, bodyBytes,
                contentHash, canonicalBody.length, rowCount, json.writeOrEmptyObject(meta));

        return find(id).orElseThrow();
    }

    public Optional<Artifact> find(UUID artifactId) {
        return jdbc.query("select * from autwit.artifact where artifact_id = ?", mapper(true), artifactId)
                .stream().findFirst();
    }

    /** Metadata only. Deliberately does not select the body columns. */
    public Optional<Artifact> findRef(UUID artifactId) {
        return jdbc.query(SELECT_META + " where artifact_id = ?", mapper(false), artifactId)
                .stream().findFirst();
    }

    public List<Artifact> listBySession(UUID sessionId, String artifactType, UUID milestoneId, String logicalName) {
        return jdbc.query(
                SELECT_META + """
                 where session_id = ?
                   and (?::text is null or artifact_type = ?)
                   and (?::uuid is null or milestone_id = ?)
                   and (?::text is null or logical_name = ?)
                 order by captured_at
                """,
                mapper(false),
                sessionId, artifactType, artifactType, milestoneId, milestoneId, logicalName, logicalName);
    }

    /**
     * Retention purge: nulls the body, keeps the row. The trace and the metadata
     * survive; GET then returns 410 rather than 404.
     */
    public boolean purge(UUID artifactId) {
        return jdbc.update(
                """
                update autwit.artifact
                set body_jsonb = null, body_text = null, body_bytes = null, purged_at = now()
                where artifact_id = ? and purged_at is null
                """,
                artifactId) > 0;
    }

    public int countBySession(UUID sessionId) {
        return jdbc.queryForObject(
                "select count(*) from autwit.artifact where session_id = ?", Integer.class, sessionId);
    }

    /**
     * Every column except the three body columns. Spelled out rather than `select *`
     * so that listing 9 artifacts never drags 8MB of body per row off the disk.
     */
    private static final String SELECT_META = """
            select artifact_id, session_id, step_id, milestone_id, run_id, artifact_type, source_system,
                   logical_name, format, external_uri, content_hash, size_bytes, row_count,
                   captured_at, purged_at, meta
            from autwit.artifact
            """;

    private RowMapper<Artifact> mapper(boolean withBody) {
        return (rs, n) -> {
            var format = ArtifactFormat.of(rs.getString("format"));
            var purgedAt = Columns.instant(rs, "purged_at");

            Object body = null;
            if (withBody && purgedAt == null) {
                body = switch (format.family()) {
                    case JSON -> json.readTree(rs.getString("body_jsonb"));
                    case TEXT -> rs.getString("body_text");
                    case BINARY -> {
                        var bytes = rs.getBytes("body_bytes");
                        yield bytes == null ? null : java.util.Base64.getEncoder().encodeToString(bytes);
                    }
                };
            }

            return new Artifact(
                    Columns.uuid(rs, "artifact_id"),
                    Columns.uuid(rs, "session_id"),
                    Columns.uuid(rs, "step_id"),
                    Columns.uuid(rs, "milestone_id"),
                    Columns.uuid(rs, "run_id"),
                    rs.getString("artifact_type"),
                    rs.getString("source_system"),
                    rs.getString("logical_name"),
                    format,
                    rs.getLong("size_bytes"),
                    Columns.integer(rs, "row_count"),
                    rs.getString("content_hash"),
                    Columns.instant(rs, "captured_at"),
                    purgedAt,
                    purgedAt == null,
                    json.readObject(rs.getString("meta")),
                    body);
        };
    }

    /** Raw bytes for GET /artifacts/{id}/raw, served with the format's native content type. */
    public Optional<byte[]> findRawBody(UUID artifactId) {
        var rows = jdbc.query(
                "select format, body_jsonb, body_text, body_bytes, purged_at from autwit.artifact where artifact_id = ?",
                (rs, n) -> {
                    if (Columns.instant(rs, "purged_at") != null) {
                        return null;
                    }
                    var format = ArtifactFormat.of(rs.getString("format"));
                    return switch (format.family()) {
                        case JSON -> rs.getString("body_jsonb").getBytes(StandardCharsets.UTF_8);
                        case TEXT -> rs.getString("body_text").getBytes(StandardCharsets.UTF_8);
                        case BINARY -> rs.getBytes("body_bytes");
                    };
                },
                artifactId);

        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }
}
