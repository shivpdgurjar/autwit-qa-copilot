package com.autwit.copilot;

import java.util.List;
import java.util.Set;

import com.autwit.copilot.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build order step 1: "migration applies, container boots".
 *
 * <p>Beyond the bare fact that Flyway ran, this asserts the schema features the rest
 * of the build leans on. They are cheap to check here and expensive to discover
 * missing in step 3.
 */
class MigrationSmokeTest extends AbstractPostgresIT {

    /**
     * The exact table set V1__init.sql creates. An explicit set rather than a count:
     * a count tells you something changed, this tells you what.
     */
    private static final Set<String> EXPECTED_TABLES = Set.of(
            "agent_memory",
            "artifact",
            "comparison",
            "event_record",
            "finding",
            "milestone",
            "run",
            "session",
            "skill",
            "skill_catalog_sync",
            "skill_invocation",
            "snapshot",
            "snapshot_part",
            "step");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void containerBoots() {
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(jdbc.queryForObject("select version()", String.class))
                .as("must be the Postgres major version the schema was verified against")
                .contains("PostgreSQL 16");
    }

    @Test
    void migrationApplies() {
        var applied = jdbc.queryForList(
                "select version, description, success from flyway_schema_history order by installed_rank");

        assertThat(applied).hasSize(1);
        assertThat(applied.get(0)).containsEntry("version", "1").containsEntry("success", true);
    }

    @Test
    void createsTheExpectedTables() {
        var tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'autwit'", String.class);

        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    void pgcryptoIsInstalled() {
        // gen_random_uuid() is the default for every primary key in the schema.
        assertThat(jdbc.queryForObject("select gen_random_uuid()", String.class)).isNotBlank();
    }

    @Test
    void queueIndexesExistForTheDequeuePath() {
        // The dequeue plan depends on both partial indexes (SCHEMA_VERIFICATION.md).
        var indexes = jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = 'autwit' and tablename = 'run'",
                String.class);

        assertThat(indexes).contains("idx_run_queued", "idx_run_lease", "uq_run_idempotency");
    }

    @Test
    void nextStepSeqFunctionExists() {
        var exists = jdbc.queryForObject(
                """
                select exists (
                  select 1 from pg_proc p
                  join pg_namespace n on n.oid = p.pronamespace
                  where n.nspname = 'autwit' and p.proname = 'next_step_seq'
                )
                """,
                Boolean.class);

        assertThat(exists).isTrue();
    }

    @Test
    void advisoryLockIsAvailable() {
        // Invariant 6's per-session serialization primitive.
        var locked = jdbc.queryForObject(
                "select pg_try_advisory_lock(hashtext('autwit:session:smoke'))", Boolean.class);
        assertThat(locked).isTrue();
        jdbc.execute("select pg_advisory_unlock(hashtext('autwit:session:smoke'))");
    }

    @Test
    void skipLockedIsSupported() {
        // Invariant 5. Syntax-level check only; RunQueueTest proves the semantics.
        List<String> rows = jdbc.queryForList(
                "select run_id::text from autwit.run where status = 'queued' "
                        + "order by queued_at for update skip locked limit 1",
                String.class);
        assertThat(rows).isEmpty();
    }
}
