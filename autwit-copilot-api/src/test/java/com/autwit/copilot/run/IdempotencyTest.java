package com.autwit.copilot.run;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.registry.SkillCatalogSync;
import com.autwit.copilot.support.AbstractPostgresIT;
import com.autwit.copilot.support.RunFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BUILD_BRIEF §9 IdempotencyTest:
 * <ul>
 *   <li>same Idempotency-Key twice → one run, same run_id returned
 *   <li>mutating skill never auto-retried (max_attempts stays 1)
 * </ul>
 *
 * <p>openapi's own framing: "Double-clicking 'Fulfil order' must not place two orders."
 */
class IdempotencyTest extends AbstractPostgresIT {

    @Autowired
    private RunEnqueuer enqueuer;
    @Autowired
    private RunRepository runs;
    @Autowired
    private RunFixtures fixtures;
    @Autowired
    private SkillCatalogSync catalog;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID sessionId;

    @BeforeEach
    void setUp() {
        fixtures.clearQueue();
        sessionId = fixtures.newSession();
        catalog.syncNow(); // enqueueSkill reads side_effects from the catalog projection
    }

    @Test
    void theSameKeyTwiceYieldsOneRunAndTheSameRunId() {
        var first = enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, "key-1");
        var second = enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, "key-1");

        assertThat(second.run().runId()).isEqualTo(first.run().runId());
        assertThat(second.replayed()).isTrue();
        assertThat(runCount()).isEqualTo(1);
    }

    @Test
    void aReplayDoesNotCreateASecondStep() {
        var first = enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, "key-2");
        var stepsAfterFirst = stepCount();

        var second = enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, "key-2");

        assertThat(second.run().stepId()).isEqualTo(first.run().stepId());
        assertThat(stepCount()).as("a replayed key must not add another chat card").isEqualTo(stepsAfterFirst);
    }

    @Test
    void concurrentRequestsWithTheSameKeyStillYieldOneRun() throws Exception {
        // The replay check is a read-then-write and can be raced; uq_run_idempotency is
        // the actual guarantee. This asserts the loser recovers into a replay rather
        // than a 500.
        var results = inParallel(
                () -> enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, "race-key"),
                () -> enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, "race-key"));

        assertThat(results.get(0).run().runId()).isEqualTo(results.get(1).run().runId());
        assertThat(runCount()).as("a double-click must not place two orders").isEqualTo(1);
    }

    @Test
    void differentKeysYieldDifferentRuns() {
        var a = enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, "key-a");
        var b = enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, "key-b");

        assertThat(a.run().runId()).isNotEqualTo(b.run().runId());
        assertThat(runCount()).isEqualTo(2);
    }

    @Test
    void nullKeysDoNotCollide() {
        // uq_run_idempotency is a partial index (WHERE idempotency_key IS NOT NULL), so
        // unkeyed requests are independent rather than all colliding on one null.
        enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, null);
        enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, null);

        assertThat(runCount()).isEqualTo(2);
    }

    @Test
    void keysAreScopedToTheSession() {
        var other = fixtures.newSession();

        var a = enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, "shared");
        var b = enqueuer.enqueueMessage(other, "I created order XXXX", null, null, "shared");

        assertThat(a.run().runId()).isNotEqualTo(b.run().runId());
    }

    // ---------------------------------------------------------------- mutating skills

    @Test
    void aMutatingSkillGetsMaxAttemptsOne() {
        var accepted = enqueuer.enqueueSkill(sessionId, "order.place",
                Map.of("member_id", "M-1234", "sku", "SKU-1"), "Place order", true, null);

        assertThat(accepted.run().maxAttempts())
                .as("never auto-retry a run that may have placed an order")
                .isEqualTo(1);
    }

    @Test
    void aMutatingSkillIsNeverReclaimedAfterAWorkerDies() {
        var accepted = enqueuer.enqueueSkill(sessionId, "order.place",
                Map.of("member_id", "M-1234", "sku", "SKU-1"), null, true, null);

        runs.dequeue("w1", java.time.Duration.ofMinutes(12)).orElseThrow();
        fixtures.expireLease(accepted.run().runId());

        assertThat(runs.dequeue("w2", java.time.Duration.ofMinutes(12)))
                .as("the order may already be placed; a human decides")
                .isEmpty();
        assertThat(fixtures.attemptsOf(accepted.run().runId())).isEqualTo(1);
    }

    @Test
    void aNonMutatingSkillMayBeReclaimed() {
        var accepted = enqueuer.enqueueSkill(sessionId, "snapshot.capture",
                Map.of("scope", "order_flow"), null, false, null);

        assertThat(accepted.run().maxAttempts()).as("side_effects: none").isEqualTo(2);

        runs.dequeue("w1", java.time.Duration.ofMinutes(12)).orElseThrow();
        fixtures.expireLease(accepted.run().runId());

        assertThat(runs.dequeue("w2", java.time.Duration.ofMinutes(12))).isPresent();
        assertThat(fixtures.attemptsOf(accepted.run().runId())).isEqualTo(2);
    }

    @Test
    void aMutatingSkillWithoutConfirmationIsRejected() {
        assertThatThrownBy(() -> enqueuer.enqueueSkill(sessionId, "order.place",
                Map.of("member_id", "M-1234", "sku", "SKU-1"), null, false, null))
                .isInstanceOf(ApiException.Conflict.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("confirmation_required"));

        assertThat(runCount()).isZero();
    }

    @Test
    void aNonMutatingSkillNeedsNoConfirmation() {
        assertThatCode(() -> enqueuer.enqueueSkill(sessionId, "snapshot.capture",
                Map.of("scope", "order_flow"), null, false, null))
                .doesNotThrowAnyException();
    }

    @Test
    void aDisabledSkillIsRejected() {
        // order.fulfil is enabled: false in the catalog fixture.
        assertThatThrownBy(() -> enqueuer.enqueueSkill(sessionId, "order.fulfil",
                Map.of("order_id", "XXXX"), null, true, null))
                .isInstanceOf(ApiException.BadRequest.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("skill_disabled"));
    }

    @Test
    void anUnknownSkillIsRejected() {
        assertThatThrownBy(() -> enqueuer.enqueueSkill(sessionId, "no.such.skill", Map.of(), null, true, null))
                .isInstanceOf(ApiException.BadRequest.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("skill_not_found"));
    }

    // ---------------------------------------------------------------- session state

    @Test
    void anEndedSessionAcceptsNoFurtherRuns() {
        jdbc.update("update autwit.session set status = 'ended' where session_id = ?", sessionId);

        assertThatThrownBy(() -> enqueuer.enqueueMessage(sessionId, "I created order XXXX", null, null, null))
                .isInstanceOf(ApiException.Conflict.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("session_ended"));
    }

    @Test
    void aDuplicateMilestoneNameIsRejected() {
        enqueuer.enqueueMilestone(sessionId, "order_created", List.of("order_flow"), true, null, null);

        assertThatThrownBy(() -> enqueuer.enqueueMilestone(sessionId, "order_created",
                List.of("order_flow"), true, null, null))
                .isInstanceOf(ApiException.Conflict.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("milestone_exists"));
    }

    private int runCount() {
        return jdbc.queryForObject(
                "select count(*) from autwit.run where session_id = ?", Integer.class, sessionId);
    }

    private int stepCount() {
        return jdbc.queryForObject(
                "select count(*) from autwit.step where session_id = ?", Integer.class, sessionId);
    }

    @SafeVarargs
    private static <T> List<T> inParallel(Callable<T>... tasks) throws Exception {
        try (var pool = Executors.newFixedThreadPool(tasks.length)) {
            var futures = pool.invokeAll(List.of(tasks), 30, TimeUnit.SECONDS);
            var out = new java.util.ArrayList<T>();
            for (var f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }
}
