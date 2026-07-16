package com.autwit.copilot.run;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.events.EventRepository;
import com.autwit.copilot.session.SessionService;
import com.autwit.copilot.support.AbstractPostgresIT;
import com.autwit.copilot.support.RunFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reads openapi.yaml declares that had no implementation: GET /snapshots and
 * GET /events, plus SessionDetail.comparisons, which was returning an empty list
 * regardless of what the session actually held.
 */
@ActiveProfiles("all")
@TestPropertySource(properties = "autwit.run.worker-concurrency=0")
class TimelineReadsTest extends AbstractPostgresIT {

    @Autowired
    private RunWorker worker;
    @Autowired
    private RunEnqueuer enqueuer;
    @Autowired
    private RunFixtures fixtures;
    @Autowired
    private SessionService sessions;
    @Autowired
    private EventRepository events;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private UUID sessionId;

    @BeforeEach
    void setUp() {
        fixtures.clearQueue();
        sessionId = fixtures.newSession();
    }

    private UUID milestone(String name) {
        var accepted = enqueuer.enqueueMilestone(sessionId, name, List.of("order_flow"), true, null, null);
        worker.pollOnce();
        return jdbc.queryForObject("select snapshot_id from autwit.milestone where milestone_id = ?",
                UUID.class, accepted.milestoneId());
    }

    @Test
    void snapshotsAreListedWithTheirParts() {
        milestone("order_created");

        var detail = sessions.detail(sessionId);

        assertThat(detail.snapshots()).singleElement().satisfies(s -> {
            assertThat(s.label()).isEqualTo("after_order_created");
            assertThat(s.parts()).hasSize(9);
            // step_id is what the UI anchors a snapshot to -- milestone_id is null for a
            // palette capture.
            assertThat(s.stepId()).isNotNull();
        });
    }

    @Test
    void comparisonsAppearInTheSessionDetail() {
        var from = milestone("order_created");
        var to = milestone("fulfilled");

        enqueuer.enqueueComparison(sessionId, from, to, "financial_validation", Map.of(), null);
        worker.pollOnce();

        // Was hard-coded to List.of(), so a comparison the tester had just run was
        // invisible to the UI no matter what the diff engine found.
        assertThat(sessions.detail(sessionId).comparisons()).singleElement().satisfies(c -> {
            assertThat(c.verdict()).isEqualTo("fail");
            assertThat(c.partResults()).hasSize(9);
            assertThat(c.stepId()).isNotNull();
            // The whole reason the UI needs this: ignore rules must be visible.
            assertThat(c.partResults()).anySatisfy(
                    p -> assertThat(p.ignoredColumns()).contains("updated_at"));
        });
    }

    // ---------------------------------------------------------------- events

    @Test
    void eventsArePaginatedByKeyset() {
        enqueuer.enqueueMessage(sessionId, "order is ready for member", null, null, null);
        worker.pollOnce();

        var first = events.list(sessionId, null, null, 5, null);
        assertThat(first.events()).hasSize(5);
        assertThat(first.nextCursor()).isNotNull();

        var second = events.list(sessionId, null, null, 5, first.nextCursor());
        assertThat(second.events()).hasSize(5);

        // No overlap: the whole point of a keyset over an offset.
        assertThat(second.events()).extracting(e -> e.eventId())
                .doesNotContainAnyElementsOf(first.events().stream().map(e -> e.eventId()).toList());
    }

    @Test
    void theLastPageHasNoCursor() {
        enqueuer.enqueueMessage(sessionId, "order is ready for member", null, null, null);
        worker.pollOnce();

        var page = events.list(sessionId, null, null, 50, null);

        assertThat(page.events()).hasSize(14);
        assertThat(page.nextCursor()).as("nothing more to fetch").isNull();
    }

    @Test
    void pagingWalksEveryEventExactlyOnce() {
        enqueuer.enqueueMessage(sessionId, "order is ready for member", null, null, null);
        worker.pollOnce();

        var seen = new java.util.ArrayList<UUID>();
        String cursor = null;
        do {
            var page = events.list(sessionId, null, null, 3, cursor);
            page.events().forEach(e -> seen.add(e.eventId()));
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(seen).hasSize(14).doesNotHaveDuplicates();
    }

    @Test
    void eventsFilterByType() {
        enqueuer.enqueueMessage(sessionId, "order is ready for member", null, null, null);
        worker.pollOnce();

        var page = events.list(sessionId, null, "ReadyForMember", 50, null);

        assertThat(page.events()).singleElement()
                .satisfies(e -> assertThat(e.eventType()).isEqualTo("ReadyForMember"));
    }

    @Test
    void eventsCarryTheirPayloadAndOffset() {
        enqueuer.enqueueMessage(sessionId, "order is ready for member", null, null, null);
        worker.pollOnce();

        assertThat(events.list(sessionId, null, "ReadyForMember", 1, null).events())
                .singleElement()
                .satisfies(e -> {
                    // Offsets, not timestamps: analysis reads offset windows because time
                    // windows are lossy under load.
                    assertThat(e.sourceOffset()).isEqualTo("10445");
                    assertThat(e.topic()).isEqualTo("orders.events");
                    assertThat(e.payload()).containsEntry("orderId", "XXXX");
                    // Linked back to the raw batch it came from, for audit.
                    assertThat(e.artifactId()).isNotNull();
                });
    }

    @Test
    void aForgedCursorIsRejectedRatherThanSilentlyIgnored() {
        // Silently starting from the beginning would make a paging bug look like
        // duplicate events, which in this product reads as a data bug.
        assertThatThrownBy(() -> events.list(sessionId, null, null, 10, "not-a-real-cursor"))
                .isInstanceOf(com.autwit.copilot.common.ApiException.BadRequest.class)
                .hasMessageContaining("not one we issued");
    }
}
