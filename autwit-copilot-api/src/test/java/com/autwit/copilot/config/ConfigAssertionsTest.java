package com.autwit.copilot.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BUILD_BRIEF §9: "lease_until > client timeout (assert the config, it's a real bug
 * class)". Equal values are as broken as inverted ones, hence the strict comparison.
 */
class ConfigAssertionsTest {

    private static AutwitProperties props(Duration lease, Duration timeout) {
        return new AutwitProperties(
                new AutwitProperties.Orchestrator("http://localhost:9090", "t", timeout, Duration.ofSeconds(60)),
                new AutwitProperties.Run(lease, Duration.ofSeconds(60), 4, 1),
                new AutwitProperties.Artifact(8388608L, 33554432L),
                new AutwitProperties.Session(Duration.ofDays(7)));
    }

    @Test
    void shippedDefaultsAreValid() {
        assertThatCode(() -> new ConfigAssertions(props(Duration.ofMinutes(12), Duration.ofMinutes(10)))
                .assertLeaseExceedsOrchestratorTimeout())
                .doesNotThrowAnyException();
    }

    @Test
    void leaseEqualToTimeoutIsRejected() {
        // The dangerous case: a slow-but-alive run gets reclaimed while still running.
        assertThatThrownBy(() -> new ConfigAssertions(props(Duration.ofMinutes(10), Duration.ofMinutes(10)))
                .assertLeaseExceedsOrchestratorTimeout())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be strictly greater");
    }

    @Test
    void leaseShorterThanTimeoutIsRejected() {
        assertThatThrownBy(() -> new ConfigAssertions(props(Duration.ofMinutes(5), Duration.ofMinutes(10)))
                .assertLeaseExceedsOrchestratorTimeout())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theShippedDefaultsMatchTheBrief() {
        var p = props(Duration.ofMinutes(12), Duration.ofMinutes(10));
        assertThat(p.run().maxAttempts())
                .as("max_attempts must stay 1 — never auto-retry a run that may have placed an order")
                .isEqualTo(1);
        assertThat(p.run().workerConcurrency()).isEqualTo(4);
    }
}
