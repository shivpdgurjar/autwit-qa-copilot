package com.autwit.copilot.registry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.autwit.copilot.common.Columns;
import com.autwit.copilot.common.Json;
import com.autwit.copilot.orchestrator.OrchestratorClient.Skill;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * A read-only projection of the orchestrator's catalog (SKILL_CONTRACT §2). Skills
 * are defined in the orchestrator's repo as versioned YAML; nobody edits them here.
 *
 * <p>There is deliberately no FK from skill_invocation to this table: the catalog is
 * a cache and may lag behind a skill that was renamed or disabled mid-session, and
 * historical invocations must survive that.
 */
@Repository
public class SkillRepository {

    private final JdbcTemplate jdbc;
    private final Json json;
    private final RowMapper<Skill> mapper;

    public SkillRepository(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new Skill(
                rs.getString("skill_name"),
                rs.getString("version"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("impl_type"),
                rs.getString("side_effects"),
                json.readObject(rs.getString("input_schema")),
                json.readObject(rs.getString("output_schema")),
                rs.getBoolean("enabled"));
    }

    public Optional<Skill> find(String skillName) {
        return jdbc.query("select * from autwit.skill where skill_name = ?", mapper, skillName)
                .stream().findFirst();
    }

    public List<Skill> list() {
        return jdbc.query("select * from autwit.skill order by skill_name", mapper);
    }

    /** Upsert: the catalog is authoritative, so a re-sync overwrites whatever we cached. */
    public void upsert(Skill s) {
        jdbc.update(
                """
                insert into autwit.skill
                  (skill_name, version, title, description, impl_type, side_effects,
                   input_schema, output_schema, enabled, synced_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, now())
                on conflict (skill_name) do update set
                  version = excluded.version, title = excluded.title, description = excluded.description,
                  impl_type = excluded.impl_type, side_effects = excluded.side_effects,
                  input_schema = excluded.input_schema, output_schema = excluded.output_schema,
                  enabled = excluded.enabled, synced_at = now()
                """,
                s.skillName(), s.version(), s.title(), s.description(), s.implType(),
                s.sideEffects() != null ? s.sideEffects() : "none",
                json.writeOrEmptyObject(s.inputSchema()), json.write(s.outputSchema()),
                s.enabled() == null || s.enabled());
    }

    /** Skills that vanished from the catalog are disabled, never deleted — see the class note. */
    public int disableMissing(List<String> presentNames) {
        if (presentNames.isEmpty()) {
            return 0;
        }
        return jdbc.update(
                "update autwit.skill set enabled = false where skill_name <> all (?)",
                (Object) presentNames.toArray(String[]::new));
    }

    public Optional<String> catalogVersion() {
        return jdbc.query("select catalog_version from autwit.skill_catalog_sync where id = 1",
                (rs, n) -> rs.getString("catalog_version")).stream().findFirst();
    }

    public Optional<Instant> syncedAt() {
        return jdbc.query("select synced_at from autwit.skill_catalog_sync where id = 1",
                (rs, n) -> Columns.instant(rs, "synced_at")).stream().findFirst();
    }

    public void recordSync(String catalogVersion) {
        jdbc.update(
                """
                insert into autwit.skill_catalog_sync (id, catalog_version, synced_at)
                values (1, ?, now())
                on conflict (id) do update set catalog_version = excluded.catalog_version, synced_at = now()
                """,
                catalogVersion);
    }
}
