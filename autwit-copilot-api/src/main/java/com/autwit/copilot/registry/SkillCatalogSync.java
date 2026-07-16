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

    /** @return true if anything was synced, false if the catalog_version was unchanged. */
    @Transactional
    public boolean syncNow() {
        var catalog = orchestrator.skills();

        // §2: "catalog_version changes whenever any skill changes. copilot-api compares
        // it before re-syncing."
        var known = skills.catalogVersion().orElse(null);
        if (catalog.catalogVersion() != null && catalog.catalogVersion().equals(known)) {
            return false;
        }

        catalog.skills().forEach(skills::upsert);
        skills.disableMissing(catalog.skills().stream().map(OrchestratorClient.Skill::skillName).toList());
        skills.recordSync(catalog.catalogVersion());

        log.info("Synced {} skills at catalog_version {}", catalog.skills().size(), catalog.catalogVersion());
        return true;
    }
}
