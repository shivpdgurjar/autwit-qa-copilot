package com.autwit.copilot.config;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fail-fast configuration guards (BUILD_BRIEF §11).
 *
 * <p>These exist because the misconfiguration they catch is invisible in review and
 * expensive in production.
 */
@Component
public class ConfigAssertions {

    private static final Logger log = LoggerFactory.getLogger(ConfigAssertions.class);

    private final AutwitProperties props;

    public ConfigAssertions(AutwitProperties props) {
        this.props = props;
    }

    @PostConstruct
    void assertLeaseExceedsOrchestratorTimeout() {
        var lease = props.run().lease();
        var timeout = props.orchestrator().timeout();

        if (lease.compareTo(timeout) <= 0) {
            throw new IllegalStateException(
                    "autwit.run.lease (%s) must be strictly greater than autwit.orchestrator.timeout (%s). "
                            .formatted(lease, timeout)
                            + "If the lease does not outlive the client timeout, a slow-but-alive run gets "
                            + "reclaimed by the reaper and re-executed while the original is still running. "
                            + "With a mutating skill that is an order-placed-twice bug.");
        }

        log.info("Config assertion passed: run.lease={} > orchestrator.timeout={}", lease, timeout);
    }
}
