package com.autwit.copilot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the {@code autwit.*} tree from BUILD_BRIEF §11.
 *
 * <p>The lease/timeout relationship is asserted at startup by
 * {@link ConfigAssertions} — see the note on {@link Run#lease()}.
 */
@ConfigurationProperties(prefix = "autwit")
public record AutwitProperties(
        Orchestrator orchestrator,
        Run run,
        Artifact artifact,
        Session session) {

    public record Orchestrator(
            String baseUrl,
            String token,
            @DefaultValue("10m") Duration timeout,
            @DefaultValue("60s") Duration catalogSyncInterval) {
    }

    public record Run(
            /*
             * MUST exceed orchestrator.timeout. If they were equal, a slow-but-alive run
             * gets reclaimed and re-executed while still running — with a mutating skill
             * that is an order-placed-twice bug.
             */
            @DefaultValue("12m") Duration lease,
            @DefaultValue("60s") Duration reaperInterval,
            @DefaultValue("4") int workerConcurrency,
            @DefaultValue("1") int maxAttempts) {
    }

    public record Artifact(
            @DefaultValue("8388608") long maxInlineBytes,
            @DefaultValue("33554432") long maxResponseBytes) {
    }

    public record Session(
            @DefaultValue("7d") Duration defaultTtl) {
    }
}
