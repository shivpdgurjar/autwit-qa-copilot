package com.autwit.copilot;

import java.util.List;
import java.util.UUID;

import com.autwit.copilot.session.CreateSessionRequest;
import com.autwit.copilot.session.SessionRepository;
import com.autwit.copilot.session.SessionService;
import com.autwit.copilot.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUILD_BRIEF §9 CascadeTest: "DELETE session → every child table empty".
 *
 * <p>Cleanup is one knob (V1__init.sql: "Everything cascades from session_id"), and
 * that only stays true if something checks it. The graph is seeded with raw SQL
 * rather than through the repositories on purpose: most of these tables have no
 * repository until steps 3 and 7, and this test is about the FKs, not the services.
 */
class CascadeTest extends AbstractPostgresIT {

    /**
     * Tables with a direct session_id FK.
     *
     * <p>snapshot_part is absent because it has no session_id: it hangs off
     * snapshot_id, so it cascades two levels (session → snapshot → snapshot_part) and
     * is counted through a join instead. That indirection is the reason it gets its
     * own assertion below — a transitive cascade is the one most likely to be broken
     * by a later migration and the least likely to be noticed.
     */
    private static final List<String> CHILD_TABLES = List.of(
            "step", "run", "skill_invocation", "milestone", "artifact",
            "snapshot", "event_record", "comparison", "finding", "agent_memory");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionService sessions;
    @Autowired
    private SessionRepository repository;

    @Test
    void deletingASessionEmptiesEveryChildTable() {
        var sessionId = seedFullGraph();

        CHILD_TABLES.forEach(t -> assertThat(rowsIn(t, sessionId))
                .as("%s should be seeded before the delete", t)
                .isPositive());
        assertThat(snapshotPartRows(sessionId)).as("snapshot_part seeded").isPositive();

        assertThat(repository.delete(sessionId)).isTrue();

        CHILD_TABLES.forEach(t -> assertThat(rowsIn(t, sessionId))
                .as("%s must be empty after the session is deleted -- zero orphans", t)
                .isZero());
        assertThat(snapshotPartRows(sessionId))
                .as("snapshot_part cascades transitively via snapshot, not directly via session")
                .isZero();
    }

    @Test
    void noSnapshotPartsAreOrphanedAnywhere() {
        seedFullGraph();
        var sessionId = seedFullGraph();
        repository.delete(sessionId);

        // The failure mode a per-session count cannot see: a row whose snapshot is gone
        // but which survives because the FK stopped cascading.
        assertThat(jdbc.queryForObject("""
                select count(*) from autwit.snapshot_part sp
                where not exists (select 1 from autwit.snapshot s where s.snapshot_id = sp.snapshot_id)
                """, Integer.class))
                .isZero();
    }

    private int snapshotPartRows(UUID sessionId) {
        return jdbc.queryForObject("""
                select count(*) from autwit.snapshot_part sp
                join autwit.snapshot s on s.snapshot_id = sp.snapshot_id
                where s.session_id = ?
                """, Integer.class, sessionId);
    }

    @Test
    void theCircularMilestoneSnapshotFkDoesNotBlockDeletion() {
        // milestone.snapshot_id -> snapshot, and snapshot.milestone_id -> milestone.
        // V1 resolves the bootstrap with a deferred ALTER TABLE; this asserts the
        // cycle also does not deadlock the teardown.
        var sessionId = seedFullGraph();

        var milestoneId = jdbc.queryForObject(
                "select milestone_id from autwit.milestone where session_id = ?", UUID.class, sessionId);
        var snapshotId = jdbc.queryForObject(
                "select snapshot_id from autwit.snapshot where session_id = ?", UUID.class, sessionId);

        assertThat(jdbc.queryForObject(
                "select snapshot_id from autwit.milestone where milestone_id = ?", UUID.class, milestoneId))
                .isEqualTo(snapshotId);
        assertThat(jdbc.queryForObject(
                "select milestone_id from autwit.snapshot where snapshot_id = ?", UUID.class, snapshotId))
                .isEqualTo(milestoneId);

        assertThat(repository.delete(sessionId)).isTrue();
        assertThat(rowsIn("milestone", sessionId)).isZero();
        assertThat(rowsIn("snapshot", sessionId)).isZero();
    }

    @Test
    void deletingAnUnknownSessionReportsNothingDeleted() {
        assertThat(repository.delete(UUID.randomUUID())).isFalse();
    }

    private int rowsIn(String table, UUID sessionId) {
        return jdbc.queryForObject(
                "select count(*) from autwit.%s where session_id = ?".formatted(table), Integer.class, sessionId);
    }

    /** One row in every session-scoped table, wired together the way a real run leaves them. */
    private UUID seedFullGraph() {
        var sessionId = sessions.create(
                new CreateSessionRequest("priya", "qa2", "cascade", null, null, null, null)).sessionId();

        var stepId = jdbc.queryForObject("""
                insert into autwit.step (session_id, seq, kind, label, actor, status)
                values (?, autwit.next_step_seq(?), 'user_utterance', 'I created order XXXX', 'user', 'succeeded')
                returning step_id
                """, UUID.class, sessionId, sessionId);

        var runId = jdbc.queryForObject("""
                insert into autwit.run (session_id, step_id, run_type, status, request)
                values (?, ?, 'invoke', 'succeeded', '{}'::jsonb)
                returning run_id
                """, UUID.class, sessionId, stepId);

        jdbc.update("""
                insert into autwit.skill_invocation
                  (session_id, step_id, run_id, skill_name, skill_version, input, status)
                values (?, ?, ?, 'snapshot.capture', '1.4.0', '{}'::jsonb, 'succeeded')
                """, sessionId, stepId, runId);

        var milestoneId = jdbc.queryForObject("""
                insert into autwit.milestone (session_id, step_id, name, seq, status)
                values (?, ?, 'order_created', 1, 'complete')
                returning milestone_id
                """, UUID.class, sessionId, stepId);

        var artifactId = jdbc.queryForObject("""
                insert into autwit.artifact
                  (session_id, step_id, milestone_id, run_id, artifact_type, source_system,
                   logical_name, format, body_jsonb, content_hash, size_bytes)
                values (?, ?, ?, ?, 'rdbms_table', 'oms_pg', 'orders', 'json', '[]'::jsonb, 'sha256:x', 2)
                returning artifact_id
                """, UUID.class, sessionId, stepId, milestoneId, runId);

        var snapshotId = jdbc.queryForObject("""
                insert into autwit.snapshot (session_id, milestone_id, step_id, label, scope, status)
                values (?, ?, ?, 'after_order_created', 'order_flow', 'complete')
                returning snapshot_id
                """, UUID.class, sessionId, milestoneId, stepId);

        // Closes the milestone <-> snapshot cycle.
        jdbc.update("update autwit.milestone set snapshot_id = ? where milestone_id = ?", snapshotId, milestoneId);

        jdbc.update("""
                insert into autwit.snapshot_part (snapshot_id, part_key, artifact_id)
                values (?, 'oms.orders', ?)
                """, snapshotId, artifactId);

        jdbc.update("""
                insert into autwit.event_record
                  (session_id, artifact_id, source, topic, event_type, after_milestone_id, payload, dedupe_hash)
                values (?, ?, 'eventstore', 'order.events', 'OrderCreated', ?, '{}'::jsonb, 'sha256:e1')
                """, sessionId, artifactId, milestoneId);

        var comparisonId = jdbc.queryForObject("""
                insert into autwit.comparison
                  (session_id, step_id, run_id, from_snapshot_id, to_snapshot_id, compare_type, verdict)
                values (?, ?, ?, ?, ?, 'structural', 'pass')
                returning comparison_id
                """, UUID.class, sessionId, stepId, runId, snapshotId, snapshotId);

        jdbc.update("""
                insert into autwit.finding (session_id, comparison_id, step_id, severity, category, message)
                values (?, ?, ?, 'high', 'consistency', 'seeded')
                """, sessionId, comparisonId, stepId);

        jdbc.update("""
                insert into autwit.agent_memory (session_id, agent_id, key, value)
                values (?, 'copilot', 'last_scope', '"order_flow"'::jsonb)
                """, sessionId);

        return sessionId;
    }
}
