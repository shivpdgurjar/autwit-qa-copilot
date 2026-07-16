package com.autwit.copilot.compare;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.common.Columns;
import com.autwit.copilot.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class FindingRepository {

    private final JdbcTemplate jdbc;
    private final Json json;
    private final RowMapper<Finding> mapper;

    public FindingRepository(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new Finding(
                Columns.uuid(rs, "finding_id"),
                Columns.uuid(rs, "session_id"),
                Columns.uuid(rs, "comparison_id"),
                Columns.uuid(rs, "step_id"),
                rs.getString("severity"),
                rs.getString("category"),
                rs.getString("part_key"),
                rs.getString("entity_key"),
                rs.getString("field"),
                json.readTree(rs.getString("before_value")),
                json.readTree(rs.getString("after_value")),
                rs.getString("message"),
                Columns.instant(rs, "created_at"));
    }

    /**
     * The single choke point for severity normalisation — an off-scale value from the
     * orchestrator must not take the run down. See {@link Severity}.
     */
    public UUID insert(UUID sessionId, UUID comparisonId, UUID stepId, String severity, String category,
            String partKey, String entityKey, String field, Object beforeValue, Object afterValue,
            String message) {
        severity = Severity.normalize(severity);
        return jdbc.queryForObject(
                """
                insert into autwit.finding
                  (session_id, comparison_id, step_id, severity, category, part_key, entity_key, field,
                   before_value, after_value, message)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                returning finding_id
                """,
                UUID.class, sessionId, comparisonId, stepId, severity, category, partKey, entityKey, field,
                json.write(beforeValue), json.write(afterValue), message);
    }

    public List<Finding> listBySession(UUID sessionId, String severity, String category) {
        return jdbc.query(
                """
                select * from autwit.finding
                where session_id = ?
                  and (?::text is null or severity = ?)
                  and (?::text is null or category = ?)
                order by created_at desc
                """,
                mapper, sessionId, severity, severity, category, category);
    }

    /** Feeds SessionDetail.counts.findings_by_severity. */
    public Map<String, Integer> countsBySeverity(UUID sessionId) {
        var rows = jdbc.queryForList(
                "select severity, count(*) as n from autwit.finding where session_id = ? group by severity",
                sessionId);
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                r -> (String) r.get("severity"), r -> ((Number) r.get("n")).intValue()));
    }
}
