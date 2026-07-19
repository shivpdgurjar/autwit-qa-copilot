package com.autwit.copilot.registry;

import com.autwit.copilot.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog re-sync must not trust {@code catalog_version} alone.
 *
 * <p>In `message-from-qa-copilot/v1.0.3` the orchestrator shipped a catalog whose
 * `events.capture_since` had moved 1.1.0 → 1.2.0 and `shell` → `http` while
 * `catalog_version` stayed at its previous value, because the artifact had been
 * hand-edited around an otherwise sound generator. The version-only check skipped the
 * re-sync in silence: the palette kept generating a form from the stale schema and
 * nothing anywhere reported a problem.
 *
 * <p>The orchestrator's own advice (v1.0.6 §2) was that we harden this regardless of
 * their pipeline being sound — the defect reached us precisely because a hand-edited
 * artifact bypassed a sound pipeline, and nothing stops that recurring in a different
 * file.
 */
@ActiveProfiles("all")
class SkillCatalogSyncTest extends AbstractPostgresIT {

    @Autowired
    private SkillCatalogSync sync;
    @Autowired
    private SkillRepository skills;

    /**
     * The load-bearing case for the content comparison, and the one that would bite
     * hardest if it regressed. Postgres {@code jsonb} does not preserve key order, so
     * the projected schemas come back ordered differently from the wire. Comparing a
     * serialised fingerprint rather than the records would report a spurious difference
     * here and re-sync on every poll, forever.
     */
    @Test
    void anUnchangedCatalogDoesNotResync() {
        // The app syncs on startup, so the projection may already be current. Sync once
        // to normalise rather than asserting on the first call's return.
        sync.syncNow();
        assertThat(skills.list()).as("projection is populated").isNotEmpty();

        assertThat(sync.syncNow())
                .as("same catalog_version and same content — nothing to do")
                .isFalse();
    }

    /**
     * The v1.0.3 defect. `catalog_version` is left exactly as the orchestrator sent it
     * while the projected content is made to differ; the sync must notice and repair
     * rather than short-circuit on the matching version.
     */
    @Test
    void aStaleCatalogVersionDoesNotHideAChangedSkill() {
        sync.syncNow();
        var version = skills.catalogVersion().orElseThrow();

        // Corrupt the projection the way a missed re-sync would leave it: the skill is
        // stale, but catalog_version still matches what the orchestrator reports.
        var before = skills.find("events.capture_since").orElseThrow();
        skills.upsert(new com.autwit.copilot.orchestrator.OrchestratorClient.Skill(
                before.skillName(), "1.1.0", before.title(), before.description(),
                "shell", before.sideEffects(), before.inputSchema(), before.outputSchema(),
                before.enabled()));

        assertThat(skills.catalogVersion()).as("version untouched — this is the trap").contains(version);

        assertThat(sync.syncNow())
                .as("content differs despite the matching version, so it must re-sync")
                .isTrue();

        assertThat(skills.find("events.capture_since").orElseThrow())
                .as("repaired back to what the orchestrator actually serves")
                .isEqualTo(before);
    }
}
