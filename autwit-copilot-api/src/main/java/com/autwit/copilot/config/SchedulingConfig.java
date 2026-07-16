package com.autwit.copilot.config;

import java.util.Optional;

import com.autwit.copilot.registry.SkillCatalogSync;
import com.autwit.copilot.run.RunReaper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Schedules the reaper and the catalog sync from the bound {@link AutwitProperties}
 * durations.
 *
 * <p>Deliberately not {@code @Scheduled(fixedDelayString = "${autwit.run.reaper-interval}")}.
 * That attribute accepts only milliseconds or ISO-8601 ({@code PT60S}), so the
 * config's own {@code 60s} — the format §11 of BUILD_BRIEF specifies and the format
 * every other duration here uses — fails at startup with a NumberFormatException.
 * Doing it programmatically keeps one human-readable value in application.yml instead
 * of a second, duplicated, millisecond copy that can drift from it.
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

    private final AutwitProperties props;
    private final Optional<RunReaper> reaper;
    private final SkillCatalogSync catalogSync;

    public SchedulingConfig(AutwitProperties props, Optional<RunReaper> reaper, SkillCatalogSync catalogSync) {
        this.props = props;
        this.reaper = reaper;
        this.catalogSync = catalogSync;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        reaper.ifPresent(r -> {
            registrar.addFixedDelayTask(r::reap, props.run().reaperInterval());
            log.info("Reaper scheduled every {}", props.run().reaperInterval());
        });

        registrar.addFixedDelayTask(catalogSync::sync, props.orchestrator().catalogSyncInterval());
        log.info("Skill catalog sync scheduled every {}", props.orchestrator().catalogSyncInterval());
    }
}
