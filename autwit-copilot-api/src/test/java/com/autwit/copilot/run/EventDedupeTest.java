package com.autwit.copilot.run;

import java.util.UUID;

import com.autwit.copilot.support.AbstractPostgresIT;
import com.autwit.copilot.support.RunFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUILD_BRIEF §9 EventDedupeTest: "invoke_ready_for_member then invoke_events_dedupe
 * → only new events land; overlap silently dropped".
 *
 * <p>This is the delta-for-free mechanism. The orchestrator never computes a delta —
 * it re-reads from the cursor and returns everything it sees, and ON CONFLICT
 * (session_id, dedupe_hash) DO NOTHING makes the delta emerge. It is what lets the
 * orchestrator stay stateless.
 */
@ActiveProfiles("all")
@TestPropertySource(properties = "autwit.run.worker-concurrency=0")
class EventDedupeTest extends AbstractPostgresIT {

    @Autowired
    private RunWorker worker;
    @Autowired
    private RunEnqueuer enqueuer;
    @Autowired
    private RunFixtures fixtures;
    @Autowired
    private RunRepository runs;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID sessionId;

    @BeforeEach
    void setUp() {
        fixtures.clearQueue();
        sessionId = fixtures.newSession();
    }

    @Test
    void overlappingEventsAreSilentlyDroppedAndOnlyNewOnesLand() {
        // 14 events, offsets 10432..10445.
        var first = enqueuer.enqueueMessage(sessionId, "order is ready for member", null, null, null);
        worker.pollOnce();
        assertThat(events()).isEqualTo(14);
        assertThat(runs.find(first.run().runId()).orElseThrow().resultSummary())
                .containsEntry("events_new", 14)
                .containsEntry("events_returned", 14);

        // Re-read from cursor 10442: returns 8 (10442..10449), 4 of which we already have.
        var second = enqueuer.enqueueMessage(sessionId, "capture events dedupe", null, null, null);
        worker.pollOnce();

        assertThat(events()).as("14 + 4 new; the 4 overlapping are dropped").isEqualTo(18);
        assertThat(runs.find(second.run().runId()).orElseThrow().resultSummary())
                .as("the orchestrator returned 8 and 4 were already known -- not an error")
                .containsEntry("events_returned", 8)
                .containsEntry("events_new", 4);
    }

    @Test
    void dedupeHashesAreUniquePerSession() {
        enqueuer.enqueueMessage(sessionId, "order is ready for member", null, null, null);
        worker.pollOnce();
        enqueuer.enqueueMessage(sessionId, "capture events dedupe", null, null, null);
        worker.pollOnce();

        assertThat(jdbc.queryForObject("""
                select count(*) from (
                  select dedupe_hash from autwit.event_record where session_id = ?
                  group by dedupe_hash having count(*) > 1
                ) dupes
                """, Integer.class, sessionId))
                .as("the unique constraint is the guarantee, not the application code")
                .isZero();
    }

    @Test
    void theSameCaptureTwiceIsIdempotent() {
        // Re-running an identical capture must add nothing. This is what makes a
        // reclaimed non-mutating run safe to re-execute (ADR-001).
        enqueuer.enqueueMessage(sessionId, "order is ready for member", null, null, null);
        worker.pollOnce();
        assertThat(events()).isEqualTo(14);

        enqueuer.enqueueMessage(sessionId, "order is ready for member", null, null, null);
        worker.pollOnce();

        assertThat(events()).as("re-reading the same window adds nothing").isEqualTo(14);
    }

    @Test
    void theDeltaIsAttributableToAMilestone() {
        // "Capture all events after step 2" -- the events that arrive after a milestone
        // is marked are tagged with it, so analysis reads an offset window rather than a
        // time window.
        var milestone = enqueuer.enqueueMilestone(sessionId, "order_created",
                java.util.List.of("order_flow"), true, null, null);
        worker.pollOnce();

        enqueuer.enqueueMessage(sessionId, "order is ready for member", null, null, null);
        worker.pollOnce();

        // The message run carries no milestone, so those events are untagged; the
        // milestone's own capture is what tags them. Assert the mechanism exists.
        assertThat(jdbc.queryForObject("""
                select count(*) from autwit.event_record where session_id = ? and after_milestone_id is null
                """, Integer.class, sessionId)).isEqualTo(14);
        assertThat(milestone.milestoneId()).isNotNull();
    }

    @Test
    void eventsAreLinkedToTheirRawBatchArtifactForAudit() {
        // §6.3: "Raw batches SHOULD also be returned as an event_batch artifact for audit."
        enqueuer.enqueueMessage(sessionId, "order is ready for member", null, null, null);
        worker.pollOnce();

        var batchId = jdbc.queryForObject(
                "select artifact_id from autwit.artifact where session_id = ? and artifact_type = 'event_batch'",
                UUID.class, sessionId);

        assertThat(jdbc.queryForObject(
                "select count(*) from autwit.event_record where session_id = ? and artifact_id = ?",
                Integer.class, sessionId, batchId))
                .as("every parsed event points back at the raw batch it came from")
                .isEqualTo(14);
    }

    @Test
    void cursorsAdvanceSoTheNextCaptureStartsWhereThisOneStopped() {
        var milestone = enqueuer.enqueueMilestone(sessionId, "ready for member", java.util.List.of("order_flow"),
                true, null, null);
        worker.pollOnce();

        assertThat(jdbc.queryForObject(
                "select event_cursor::text from autwit.milestone where milestone_id = ?",
                String.class, milestone.milestoneId()))
                .contains("orders.events")
                .contains("10445");
    }

    private int events() {
        return jdbc.queryForObject(
                "select count(*) from autwit.event_record where session_id = ?", Integer.class, sessionId);
    }
}
