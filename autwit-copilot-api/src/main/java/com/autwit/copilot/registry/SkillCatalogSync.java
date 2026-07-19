package com.autwit.copilot.registry;

import com.autwit.copilot.orchestrator.OrchestratorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls GET /skills into autwit.skill (SKILL_CONTRACT §2). Powers the ⌘K palette's
 * form generation, and tells RunEnqueuer whether a skill is mutating.
 */
@Component
public class SkillCatalogSync {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalogSync.class);

    private final OrchestratorClient orchestrator;
    private final SkillRepository skills;

    public SkillCatalogSync(OrchestratorClient orchestrator, SkillRepository skills) {
        this.orchestrator = orchestrator;
        this.skills = skills;
    }

    /** Scheduled by SchedulingConfig from autwit.orchestrator.catalog-sync-interval. */
    public void sync() {
        try {
            syncNow();
        } catch (Exception e) {
            // A stale catalog degrades the palette; it must not take the app down.
            log.warn("Skill catalog sync failed; keeping the previous projection", e);
        }
    }

    /** @return true if anything was synced, false if nothing had changed. */
    @Transactional
    public boolean syncNow() {
        var catalog = orchestrator.skills();

        // §2: "catalog_version changes whenever any skill changes. copilot-api compares
        // it before re-syncing."
        var known = skills.catalogVersion().orElse(null);
        if (catalog.catalogVersion() != null && catalog.catalogVersion().equals(known)) {
            // Trust, but verify. An unchanged version is only evidence that nothing
            // changed if the thing producing it is sound. In v1.0.3 the orchestrator
            // shipped a catalog whose events.capture_since had moved 1.1.0 -> 1.2.0
            // and shell -> http while catalog_version stayed put, because the artifact
            // was hand-edited around a sound pipeline. A version-only check skipped the
            // re-sync in silence, which is the worst shape: the palette keeps offering
            // a stale schema and nothing anywhere says so.
            if (contentMatches(catalog.skills())) {
                return false;
            }
            log.warn("catalog_version {} is unchanged but the skill content differs — re-syncing anyway. "
                    + "The orchestrator's version did not move for a real change; treat its catalog as suspect.",
                    known);
        }

        catalog.skills().forEach(skills::upsert);
        skills.disableMissing(catalog.skills().stream().map(OrchestratorClient.Skill::skillName).toList());
        skills.recordSync(catalog.catalogVersion());

        log.info("Synced {} skills at catalog_version {}", catalog.skills().size(), catalog.catalogVersion());
        return true;
    }

    /**
     * Does the incoming catalog match what we already project?
     *
     * <p>Compares the {@code Skill} records themselves rather than a serialised form.
     * {@code Skill} is a record, so equality is component-wise and its {@code Map}
     * schema fields compare order-independently — which matters, because Postgres
     * {@code jsonb} does not preserve key order, so the projection's schemas come back
     * ordered differently from the wire. A string fingerprint would report a difference
     * on every poll and re-sync forever.
     *
     * <p>Both sides are sorted by skill name: {@code SkillRepository.list()} orders by
     * {@code skill_name}, and the orchestrator's array order is not guaranteed.
     */
    private boolean contentMatches(java.util.List<OrchestratorClient.Skill> incoming) {
        var comparator = java.util.Comparator.comparing(OrchestratorClient.Skill::skillName);
        return incoming.stream().sorted(comparator).toList()
                .equals(skills.list().stream().sorted(comparator).toList());
    }
}
