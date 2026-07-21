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
     * The exact table set the migrations create. An explicit set rather than a count:
     * a count tells you something changed, this tells you what. V1 creates the first
     * fourteen; V2 adds the two analysis-session tables (financial-analysis ownership,
     * SKILL_CONTRACT §0 / message-from-qa-copilot/v1.0.17).
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
            "step",
            // V2
            "analysis_session",
            "analysis_state");

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

        assertThat(applied).hasSize(3);
        assertThat(applied.get(0)).containsEntry("version", "1").containsEntry("success", true);
        assertThat(applied.get(1)).containsEntry("version", "2").containsEntry("success", true);
        assertThat(applied.get(2)).containsEntry("version", "3").containsEntry("success", true);
    }

    @Test
    void createsTheExpectedTables() {
        var tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'autwit'", String.class);

        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    void flywayHistoryLivesInPublicAndNotInTheSchemaItManages() {
        // Regression guard. The DB user is named autwit and V1 creates a schema named
        // autwit, so once the migration has run, search_path's "$user" entry resolves
        // and current_schema() flips from public to autwit. If Flyway is left to infer
        // its default schema from the connection it then hunts for
        // autwit.flyway_schema_history, does not find it, sees a non-empty schema and
        // refuses to start -- the app boots once and never again.
        // spring.flyway.default-schema pins this; the assertion keeps it pinned.
        assertThat(jdbc.queryForObject("select current_schema()", String.class))
                .as("precondition: the user/schema name collision that causes the flip")
                .isEqualTo("autwit");

        assertThat(jdbc.queryForList(
                "select schemaname from pg_tables where tablename = 'flyway_schema_history'", String.class))
                .containsExactly("public");
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
