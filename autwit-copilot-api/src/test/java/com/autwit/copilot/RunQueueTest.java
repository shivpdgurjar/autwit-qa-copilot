package com.autwit.copilot;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.autwit.copilot.config.AutwitProperties;
import com.autwit.copilot.run.Run;
import com.autwit.copilot.run.RunReaper;
import com.autwit.copilot.run.RunRepository;
import com.autwit.copilot.run.RunType;
import com.autwit.copilot.run.SessionLocks;
import com.autwit.copilot.support.AbstractPostgresIT;
import com.autwit.copilot.support.RunFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUILD_BRIEF §9 RunQueueTest, plus the ADR-001 assertions.
 *
 * <p>These encode invariants 5, 6 and 8. Without them the invariants are just
 * comments.
 *
 * <p>Runs without the worker profile on purpose: these drive dequeue directly, and a
 * background RunWorker would race every assertion.
 */
class RunQueueTest extends AbstractPostgresIT {

    private static final Duration LEASE = Duration.ofMinutes(12);

    @Autowired
    private RunRepository runs;
    @Autowired
    private RunFixtures fixtures;
    @Autowired
    private SessionLocks locks;
    @Autowired
    private RunReaper reaper;
    @Autowired
    private AutwitProperties props;

    /**
     * The queue is global: dequeue returns the oldest queued run in the table, not the
     * oldest in some session. Without this, a run left queued by an earlier test is
     * what the next dequeue hands back, and every assertion here lands on the wrong row.
     */
    @org.junit.jupiter.api.BeforeEach
    void emptyTheQueue() {
        fixtures.clearQueue();
    }

    // ---------------------------------------------------------------- invariant 5: exactly once

    @Test
    void twoWorkersOneQueuedRunExecutedExactlyOnce() throws Exception {
        var sessionId = fixtures.newSession();
        var run = fixtures.queueRun(sessionId, RunType.COMPARISON);

        // Both race the same row. SKIP LOCKED means the loser skips rather than blocks.
        var claims = inParallel(
                () -> runs.dequeue("w1", LEASE),
                () -> runs.dequeue("w2", LEASE));

        var winners = claims.stream().flatMap(java.util.Optional::stream).toList();
        assertThat(winners).as("exactly one worker may claim a run").hasSize(1);
        assertThat(winners.get(0).runId()).isEqualTo(run.runId());
        assertThat(fixtures.attemptsOf(run.runId())).isEqualTo(1);
    }

    @Test
    void sixRunsAcrossFourWorkersAreEachClaimedOnce() throws Exception {
        var sessions = List.of(fixtures.newSession(), fixtures.newSession(), fixtures.newSession(),
                fixtures.newSession(), fixtures.newSession(), fixtures.newSession());
        var queued = sessions.stream().map(s -> fixtures.queueRun(s, RunType.COMPARISON)).toList();

        // 4 workers, 3 attempts each, against 6 queued runs -- SCHEMA_VERIFICATION's shape.
        List<Callable<java.util.Optional<Run>>> attempts = new java.util.ArrayList<>();
        for (int w = 1; w <= 4; w++) {
            var workerId = "w" + w;
            for (int i = 0; i < 3; i++) {
                attempts.add(() -> runs.dequeue(workerId, LEASE));
            }
        }

        var claimed = inParallel(attempts).stream().flatMap(java.util.Optional::stream).toList();

        assertThat(claimed).hasSize(6);
        assertThat(claimed.stream().map(Run::runId).distinct()).as("no run claimed twice").hasSize(6);
        queued.forEach(r -> assertThat(fixtures.attemptsOf(r.runId())).isEqualTo(1));
    }

    @Test
    void anEmptyQueueYieldsNothing() {
        assertThat(runs.dequeue("w1", LEASE)).isEmpty();
    }

    // ---------------------------------------------------------------- ADR-001: reclaim vs reap

    @Test
    void aDeadWorkersReclaimableRunIsReclaimedWithAttemptsTwo() {
        // §9: "worker killed mid-run -> lease expires -> reclaimed -> attempts=2".
        // A comparison run: local, non-mutating, max_attempts=2.
        var sessionId = fixtures.newSession();
        var run = fixtures.queueRun(sessionId, RunType.COMPARISON);

        var first = runs.dequeue("w1", LEASE).orElseThrow();
        assertThat(fixtures.attemptsOf(first.runId())).isEqualTo(1);

        fixtures.expireLease(run.runId()); // w1 OOMs mid-run

        var reclaimed = runs.dequeue("w9", LEASE);
        assertThat(reclaimed).as("an expired lease below max_attempts is reclaimable").isPresent();
        assertThat(reclaimed.get().runId()).isEqualTo(run.runId());
        assertThat(fixtures.attemptsOf(run.runId())).isEqualTo(2);
        assertThat(reclaimed.get().workerId()).isEqualTo("w9");
    }

    @Test
    void aDeadWorkersInvokeRunIsNeverReclaimed() {
        // The heart of ADR-001. invoke has max_attempts=1 because the LLM picks the
        // skill after enqueue, so we cannot know it is safe to re-run. Reclaiming it
        // would re-execute work that may already have placed an order.
        var sessionId = fixtures.newSession();
        var run = fixtures.queueRun(sessionId, RunType.INVOKE);

        runs.dequeue("w1", LEASE).orElseThrow();
        fixtures.expireLease(run.runId());

        assertThat(runs.dequeue("w9", LEASE))
                .as("an invoke run that has been attempted once is invisible to the dequeue")
                .isEmpty();
        assertThat(fixtures.attemptsOf(run.runId())).isEqualTo(1);
        assertThat(fixtures.statusOf(run.runId())).isEqualTo("running");
    }

    @Test
    void theReaperBuriesWhatTheDequeueWillNotReclaim() {
        var sessionId = fixtures.newSession();
        var run = fixtures.queueRun(sessionId, RunType.INVOKE);
        runs.dequeue("w1", LEASE).orElseThrow();
        fixtures.expireLease(run.runId());

        assertThat(reaper.reapNow()).isPositive();

        // timed_out, NOT failed: the outcome is unknown (invariant 8).
        assertThat(fixtures.statusOf(run.runId())).isEqualTo("timed_out");
        assertThat(fixtures.errorCodeOf(run.runId())).isEqualTo("lease_expired");
    }

    @Test
    void theReaperLeavesReclaimableRunsAlone() {
        // The other half of the disjointness. If the reaper took this row, §9's reclaim
        // requirement would be at the mercy of which statement fired first.
        var sessionId = fixtures.newSession();
        var run = fixtures.queueRun(sessionId, RunType.COMPARISON);
        runs.dequeue("w1", LEASE).orElseThrow();
        fixtures.expireLease(run.runId());

        reaper.reapNow();

        assertThat(fixtures.statusOf(run.runId()))
                .as("attempts(1) < max_attempts(2), so this row belongs to the dequeue, not the reaper")
                .isEqualTo("running");
        assertThat(runs.dequeue("w9", LEASE)).isPresent();
    }

    @Test
    void aReclaimedRunIsReapedOnceItsAttemptsAreExhausted() {
        // The full lifecycle: reclaim once, then bury. Two workers dying in a row must
        // not loop forever.
        var sessionId = fixtures.newSession();
        var run = fixtures.queueRun(sessionId, RunType.COMPARISON);

        runs.dequeue("w1", LEASE).orElseThrow();
        fixtures.expireLease(run.runId());
        runs.dequeue("w2", LEASE).orElseThrow();           // attempts = 2 = max_attempts
        fixtures.expireLease(run.runId());

        assertThat(runs.dequeue("w3", LEASE)).as("attempts are exhausted").isEmpty();
        assertThat(reaper.reapNow()).isPositive();
        assertThat(fixtures.statusOf(run.runId())).isEqualTo("timed_out");
    }

    @Test
    void reapAndDequeuePredicatesNeverSelectTheSameRow() {
        // Asserts the disjointness directly rather than through behaviour: for every
        // expired-lease row, exactly one of the two predicates matches.
        var reclaimable = fixtures.queueRun(fixtures.newSession(), RunType.COMPARISON);
        var terminalOnly = fixtures.queueRun(fixtures.newSession(), RunType.INVOKE);
        runs.dequeue("w1", LEASE);
        runs.dequeue("w1", LEASE);
        fixtures.expireLease(reclaimable.runId());
        fixtures.expireLease(terminalOnly.runId());

        assertThat(matchesDequeue(reclaimable.runId())).isTrue();
        assertThat(matchesReaper(reclaimable.runId())).isFalse();

        assertThat(matchesDequeue(terminalOnly.runId())).isFalse();
        assertThat(matchesReaper(terminalOnly.runId())).isTrue();
    }

    // ---------------------------------------------------------------- invariant 6: serialization

    @Test
    void twoRunsInOneSessionAreStrictlySerialized() {
        var sessionId = fixtures.newSession();

        try (var held = locks.tryAcquire(sessionId).orElseThrow()) {
            // A second worker cannot take the same session's lock while the first holds it.
            assertThat(locks.tryAcquire(sessionId))
                    .as("a step-5 snapshot must not race a step-2 snapshot")
                    .isEmpty();
        }

        // Released on close -- the next run in that session can proceed.
        try (var afterRelease = locks.tryAcquire(sessionId).orElseThrow()) {
            assertThat(afterRelease.sessionId()).isEqualTo(sessionId);
        }
    }

    @Test
    void runsAcrossSessionsRunInParallel() {
        var a = fixtures.newSession();
        var b = fixtures.newSession();

        try (var lockA = locks.tryAcquire(a).orElseThrow();
             var lockB = locks.tryAcquire(b).orElseThrow()) {
            assertThat(lockA.sessionId()).isNotEqualTo(lockB.sessionId());
        }
    }

    @Test
    void aRunReleasedBecauseOfLockContentionDoesNotBurnAnAttempt() {
        // Lock contention is not a failed attempt. If it burned one, a mutating run
        // could exhaust its single allowed attempt without ever having been executed.
        var sessionId = fixtures.newSession();
        var run = fixtures.queueRun(sessionId, RunType.INVOKE);

        runs.dequeue("w1", LEASE).orElseThrow();
        assertThat(fixtures.attemptsOf(run.runId())).isEqualTo(1);

        runs.releaseToQueue(run.runId());

        assertThat(fixtures.statusOf(run.runId())).isEqualTo("queued");
        assertThat(fixtures.attemptsOf(run.runId())).isZero();
        assertThat(runs.dequeue("w2", LEASE)).as("still claimable by another worker").isPresent();
    }

    // ---------------------------------------------------------------- config

    @Test
    void leaseExceedsTheClientTimeout() {
        // Asserted here as well as in ConfigAssertionsTest because this is the file
        // someone reads when they change one of the two numbers.
        assertThat(props.run().lease())
                .as("a lease that does not outlive the client timeout gets a live run reclaimed mid-flight")
                .isGreaterThan(props.orchestrator().timeout());
    }

    // ---------------------------------------------------------------- helpers

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private boolean matchesDequeue(UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                select exists (select 1 from autwit.run where run_id = ?
                  and (status = 'queued'
                       or (status = 'running' and lease_until < now() and attempts < max_attempts)))
                """, Boolean.class, runId));
    }

    private boolean matchesReaper(UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                select exists (select 1 from autwit.run where run_id = ?
                  and status = 'running' and lease_until < now() and attempts >= max_attempts)
                """, Boolean.class, runId));
    }

    @SafeVarargs
    private static <T> List<T> inParallel(Callable<T>... tasks) throws Exception {
        return inParallel(List.of(tasks));
    }

    private static <T> List<T> inParallel(List<Callable<T>> tasks) throws Exception {
        try (var pool = Executors.newFixedThreadPool(tasks.size())) {
            var futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
            var results = new java.util.ArrayList<T>();
            for (var f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
