package com.autwit.copilot.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Catches workers that died outright. A worker that merely times out marks itself
 * (BUILD_BRIEF §6) — this is only for the ones that never got the chance.
 *
 * <p>Its predicate is the exact mirror of {@link RunRepository#dequeue}'s: dequeue
 * takes expired leases where {@code attempts < max_attempts}, this takes the rest.
 * The two sets are disjoint, so a dead worker's run is claimed by exactly one of them
 * and never races (ADR-001).
 *
 * <p>Everything it touches becomes timed_out, never failed: a run whose worker
 * vanished has an UNKNOWN outcome, because it may well have placed the order before
 * it died.
 */
@Component
public class RunReaper {

    private static final Logger log = LoggerFactory.getLogger(RunReaper.class);

    private final RunRepository runs;

    public RunReaper(RunRepository runs) {
        this.runs = runs;
    }

    /** Scheduled by SchedulingConfig from autwit.run.reaper-interval. */
    public void reap() {
        try {
            int reaped = reapNow();
            if (reaped > 0) {
                log.warn("Reaped {} run(s) whose lease expired; each is timed_out with an UNKNOWN outcome "
                        + "and will not be retried automatically", reaped);
            }
        } catch (Exception e) {
            log.error("Reaper sweep failed; will retry on the next tick", e);
        }
    }

    /** Exposed so tests can drive a sweep without waiting 60s. */
    public int reapNow() {
        return runs.reapExpired();
    }
}
