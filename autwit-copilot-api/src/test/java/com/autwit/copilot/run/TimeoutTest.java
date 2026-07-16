package com.autwit.copilot.run;

import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.orchestrator.FakeOrchestratorClient;
import com.autwit.copilot.support.AbstractPostgresIT;
import com.autwit.copilot.support.RunFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BUILD_BRIEF §9 TimeoutTest:
 * <ul>
 *   <li>invoke_slow fixture → run ends timed_out, NOT failed
 *   <li>timed_out is never auto-retried
 *   <li>late response after terminal → discarded, nothing persisted
 * </ul>
 *
 * <p>The timeout is dialled down to 1s. The fake sleeps min(fixture sleep_ms, timeout)
 * and then raises Timeout, so this exercises the worker's real timed_out path in a
 * second instead of ten minutes. The lease stays at its 12m default, preserving the
 * lease > timeout invariant that ConfigAssertions enforces.
 */
@ActiveProfiles("all")
@TestPropertySource(properties = {
        "autwit.run.worker-concurrency=0",
        "autwit.orchestrator.timeout=1s"
})
class TimeoutTest extends AbstractPostgresIT {

    @Autowired
    private RunWorker worker;
    @Autowired
    private RunEnqueuer enqueuer;
    @Autowired
    private RunRepository runs;
    @Autowired
    private RunReaper reaper;
    @Autowired
    private RunFixtures fixtures;
    @Autowired
    private EnvelopePersister persister;
    @Autowired
    private FakeOrchestratorClient fake;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID sessionId;

    @BeforeEach
    void setUp() {
        fixtures.clearQueue();
        sessionId = fixtures.newSession();
    }

    @Test
    void aSlowOrchestratorEndsTimedOutNotFailed() {
        var accepted = enqueuer.enqueueMessage(sessionId, "this one is slow", null, null, null);

        worker.pollOnce();

        var run = runs.find(accepted.run().runId()).orElseThrow();
        // The distinction the whole design rests on: failed means it did not happen,
        // timed_out means nobody knows.
        assertThat(run.status()).isEqualTo("timed_out").isNotEqualTo("failed");
        assertThat(run.error()).containsEntry("code", "deadline_exceeded");
        assertThat(run.error().get("detail").toString())
                .as("the UI tells the tester to verify before retrying")
                .contains("outcome is UNKNOWN")
                .contains("Verify before retrying");
    }

    @Test
    void aTimedOutRunIsNeverAutoRetried() {
        var accepted = enqueuer.enqueueMessage(sessionId, "this one is slow", null, null, null);
        worker.pollOnce();
        assertThat(fixtures.statusOf(accepted.run().runId())).isEqualTo("timed_out");

        // timed_out is terminal. Nothing may pick it back up -- it may have placed an order.
        assertThat(runs.dequeue("w2", java.time.Duration.ofMinutes(12))).isEmpty();
        assertThat(worker.pollOnce()).isFalse();
        assertThat(fixtures.attemptsOf(accepted.run().runId())).isEqualTo(1);
        assertThat(fixtures.statusOf(accepted.run().runId())).isEqualTo("timed_out");
    }

    @Test
    void aTimedOutRunIsNotResurrectedByTheReaper() {
        var accepted = enqueuer.enqueueMessage(sessionId, "this one is slow", null, null, null);
        worker.pollOnce();

        reaper.reapNow();

        assertThat(fixtures.statusOf(accepted.run().runId())).isEqualTo("timed_out");
        assertThat(fixtures.errorCodeOf(accepted.run().runId()))
                .as("still the worker's own deadline_exceeded, not the reaper's lease_expired")
                .isEqualTo("deadline_exceeded");
    }

    @Test
    void aTimedOutRunPersistsNothing() {
        enqueuer.enqueueMessage(sessionId, "this one is slow", null, null, null);
        worker.pollOnce();

        assertThat(count("artifact")).isZero();
        assertThat(count("snapshot")).isZero();
        assertThat(count("event_record")).isZero();
    }

    @Test
    void aLateResultForATerminalRunIsDiscarded() {
        // BUILD_BRIEF §6: "if a run went terminal (cancelled/timed_out) and the
        // orchestrator's response arrives afterwards, discard it. Do not persist."
        var accepted = enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, null);
        var claimed = runs.dequeue("w1", java.time.Duration.ofMinutes(12)).orElseThrow();

        // The run goes terminal while the orchestrator is still working.
        runs.timeOut(claimed.runId(), "w1", Map.of("code", "deadline_exceeded"));
        assertThat(fixtures.statusOf(accepted.run().runId())).isEqualTo("timed_out");

        // ...and its result turns up anyway.
        var envelope = fake.replayFixture("invoke_order_created.json", claimed.runId().toString());

        assertThatThrownBy(() -> persister.persist(claimed, envelope, null))
                .isInstanceOf(EnvelopePersister.LateResultException.class);

        // Nothing landed: the persist transaction unwound in full.
        assertThat(count("artifact")).as("9 artifacts must not appear under a timed_out run").isZero();
        assertThat(count("snapshot")).isZero();
        assertThat(fixtures.statusOf(accepted.run().runId())).isEqualTo("timed_out");
    }

    @Test
    void aLateResultForACancelledRunIsAlsoDiscarded() {
        var accepted = enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, null);
        var claimed = runs.dequeue("w1", java.time.Duration.ofMinutes(12)).orElseThrow();
        runs.cancel(claimed.runId(), "w1");

        var envelope = fake.replayFixture("invoke_order_created.json", claimed.runId().toString());

        assertThatThrownBy(() -> persister.persist(claimed, envelope, null))
                .isInstanceOf(EnvelopePersister.LateResultException.class);
        assertThat(count("artifact")).isZero();
        assertThat(fixtures.statusOf(accepted.run().runId())).isEqualTo("cancelled");
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "select count(*) from autwit.%s where session_id = ?".formatted(table), Integer.class, sessionId);
    }
}
