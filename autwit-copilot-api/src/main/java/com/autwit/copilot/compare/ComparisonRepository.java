package com.autwit.copilot.compare;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.autwit.copilot.common.Columns;
import com.autwit.copilot.common.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ComparisonRepository {

    private static final TypeReference<List<PartResult>> PART_RESULTS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final Json json;
    private final RowMapper<Comparison> mapper;

    public ComparisonRepository(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new Comparison(
                Columns.uuid(rs, "comparison_id"),
                Columns.uuid(rs, "session_id"),
                Columns.uuid(rs, "step_id"),
                Columns.uuid(rs, "run_id"),
                Columns.uuid(rs, "from_snapshot_id"),
                Columns.uuid(rs, "to_snapshot_id"),
                rs.getString("compare_type"),
                json.readObject(rs.getString("rules")),
                rs.getString("verdict"),
                rs.getString("summary"),
                Columns.uuid(rs, "report_ref"),
                json.read(rs.getString("part_results"), PART_RESULTS),
                Columns.instant(rs, "created_at"),
                List.of(),
                Map.of());
    }

    /** Created up-front so the 202's comparison_id is real before the diff runs. */
    public UUID insertPending(UUID sessionId, UUID stepId, UUID fromSnapshotId, UUID toSnapshotId,
            String compareType, Map<String, Object> rules) {
        return jdbc.queryForObject(
                """
                insert into autwit.comparison
                  (session_id, step_id, from_snapshot_id, to_snapshot_id, compare_type, rules)
                values (?, ?, ?, ?, ?, ?::jsonb)
                returning comparison_id
                """,
                UUID.class, sessionId, stepId, fromSnapshotId, toSnapshotId, compareType,
                json.writeOrEmptyObject(rules));
    }

    public void complete(UUID comparisonId, UUID runId, String verdict, String summary,
            List<PartResult> partResults) {
        jdbc.update(
                """
                update autwit.comparison
                set run_id = ?, verdict = ?, summary = ?, part_results = ?::jsonb
                where comparison_id = ?
                """,
                runId, verdict, summary, json.write(partResults), comparisonId);
    }

    public void attachReport(UUID comparisonId, UUID artifactId) {
        jdbc.update("update autwit.comparison set report_ref = ? where comparison_id = ?",
                artifactId, comparisonId);
    }

    public Optional<Comparison> find(UUID comparisonId) {
        return jdbc.query("select * from autwit.comparison where comparison_id = ?", mapper, comparisonId)
                .stream().findFirst();
    }

    public List<Comparison> listBySession(UUID sessionId) {
        return jdbc.query("select * from autwit.comparison where session_id = ? order by created_at",
                mapper, sessionId);
    }
}
