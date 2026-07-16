package com.autwit.copilot;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.compare.ComparisonRepository;
import com.autwit.copilot.compare.FindingRepository;
import com.autwit.copilot.run.RunEnqueuer;
import com.autwit.copilot.run.RunWorker;
import com.autwit.copilot.snapshot.SnapshotRepository;
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
 * The canonical session from BUILD_BRIEF §1, end to end, and step 7's done-when
 * ("/end produces a downloadable html").
 *
 * <pre>
 * 1. start session
 * 2. "I created order XXXX"      -> milestone + snapshot
 * 4. "order is ready for member" -> milestone + snapshot + events
 * 5. "I fulfilled the order"     -> milestone + snapshot
 * 6. compare snapshots           -> consistency + financial validation -> findings
 * 7. end session                 -> renders an html report for download
 * </pre>
 *
 * <p>The fixtures make this a real demonstration rather than a smoke test:
 * invoke_fulfilled reports total_amount 1450.00 while its line items still sum to
 * 1200.00, so the financial validation finds a genuine 250.00 discrepancy — exactly
 * the bug class the product exists to catch, arrived at by walking the flow rather
 * than by constructing the case.
 */
@ActiveProfiles("all")
@TestPropertySource(properties = "autwit.run.worker-concurrency=0")
class CanonicalSessionTest extends AbstractPostgresIT {

    @Autowired
    private RunWorker worker;
    @Autowired
    private RunEnqueuer enqueuer;
    @Autowired
    private RunFixtures fixtures;
    @Autowired
    private SnapshotRepository snapshots;
    @Autowired
    private ComparisonRepository comparisons;
    @Autowired
    private FindingRepository findings;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID sessionId;

    @BeforeEach
    void setUp() {
        fixtures.clearQueue();
        sessionId = fixtures.newSession();
    }

    /** Runs the milestone and waits for the worker to finish it. */
    private UUID milestone(String name) {
        var accepted = enqueuer.enqueueMilestone(sessionId, name, List.of("order_flow"), true, null, null);
        assertThat(worker.pollOnce()).isTrue();
        return accepted.milestoneId();
    }

    private UUID snapshotOf(UUID milestoneId) {
        return jdbc.queryForObject("select snapshot_id from autwit.milestone where milestone_id = ?",
                UUID.class, milestoneId);
    }

    @Test
    void theCanonicalSessionProducesADownloadableReportNamingTheFinancialBug() {
        // 2. "I created order XXXX" -> milestone + snapshot (9 parts)
        var created = milestone("order_created");
        var fromSnapshot = snapshotOf(created);
        assertThat(fromSnapshot).isNotNull();

        // 5. "I fulfilled the order" -> second snapshot, identical part_keys
        var fulfilled = milestone("fulfilled");
        var toSnapshot = snapshotOf(fulfilled);

        assertThat(snapshots.parts(fromSnapshot)).extracting(s -> s.partKey())
                .as("comparison is a key-wise join; the keys must match exactly")
                .containsExactlyInAnyOrderElementsOf(
                        snapshots.parts(toSnapshot).stream().map(p -> p.partKey()).toList());

        // 6. compare -> financial validation
        var comparison = enqueuer.enqueueComparison(sessionId, fromSnapshot, toSnapshot,
                "financial_validation", Map.of(), null);
        assertThat(worker.pollOnce()).isTrue();

        var result = comparisons.find(comparison.comparisonId()).orElseThrow();

        // The order says 1450.00; its line items still sum to 1200.00.
        assertThat(result.verdict()).isEqualTo("fail");
        assertThat(findings.listBySession(sessionId, "critical", null))
                .as("order total != Σ line items")
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.category()).isEqualTo("financial");
                    assertThat(f.message()).contains("1450.00").contains("1200.00").contains("250.00");
                });

        // Ignore rules are surfaced, not applied silently.
        assertThat(result.partResults()).anySatisfy(p -> {
            assertThat(p.partKey()).isEqualTo("oms.orders");
            assertThat(p.ignoredColumns()).contains("updated_at");
        });

        // 7. end session -> renders the report
        enqueuer.enqueueReport(sessionId, "both", "Checked the order flow end to end.", null);
        assertThat(sessionStatus()).as("ended immediately; the report follows").isEqualTo("ended");
        assertThat(worker.pollOnce()).isTrue();

        var html = reportBody("report.html");
        assertThat(html)
                .contains("<!DOCTYPE html>")
                .contains("order_total_equals_line_items")
                .contains("250.00")
                .contains("CRITICAL".toLowerCase())
                .contains("Checked the order flow end to end.")
                // The ignored columns must appear in the report itself -- if updated_at
                // diffs vanish without explanation, nobody trusts it.
                .contains("updated_at");

        var md = reportBody("report.md");
        assertThat(md).contains("# AutWit session report").contains("250.00").contains("| **critical** |");
    }

    @Test
    void eventsCapturedBetweenMilestonesLandOnTheTimeline() {
        milestone("order_created");

        // 4. "order is ready for member" -> api_response + event_batch + 14 events
        enqueuer.enqueueMessage(sessionId, "order is ready for member", null, null, null);
        worker.pollOnce();

        assertThat(jdbc.queryForObject(
                "select count(*) from autwit.event_record where session_id = ?", Integer.class, sessionId))
                .isEqualTo(14);
        assertThat(jdbc.queryForObject(
                "select count(*) from autwit.artifact where session_id = ? and artifact_type = 'api_response'",
                Integer.class, sessionId))
                .isEqualTo(1);
    }

    @Test
    void aStructuralComparisonOfIdenticalSnapshotsPasses() {
        var a = milestone("order_created");

        // Compare a snapshot with itself: the same 9 parts, byte for byte.
        var comparison = enqueuer.enqueueComparison(sessionId, snapshotOf(a), snapshotOf(a),
                "structural", Map.of(), null);
        worker.pollOnce();

        var result = comparisons.find(comparison.comparisonId()).orElseThrow();
        assertThat(result.verdict()).isEqualTo("pass");
        assertThat(result.partResults()).hasSize(9).allSatisfy(p -> {
            assertThat(p.hasChanges()).isFalse();
            assertThat(p.inconclusive()).isFalse();
        });
        assertThat(findings.listBySession(sessionId, null, null)).isEmpty();
    }

    @Test
    void aComparisonRunNeverTouchesTheOrchestrator() {
        // Local, but still a run: same queue, same lock, same lease (invariant 2 --
        // "Uniformity beats saving 200ms").
        var a = milestone("order_created");
        var accepted = enqueuer.enqueueComparison(sessionId, snapshotOf(a), snapshotOf(a),
                "structural", Map.of(), null);

        assertThat(accepted.run().runType()).isEqualTo("comparison");
        assertThat(accepted.run().status()).isEqualTo("queued");
        assertThat(accepted.comparisonId()).as("real before the diff runs -- the UI opens the card at once")
                .isNotNull();

        worker.pollOnce();

        var run = jdbc.queryForMap("select status, result_summary from autwit.run where run_id = ?",
                accepted.run().runId());
        assertThat(run).containsEntry("status", "succeeded");
        assertThat(String.valueOf(run.get("result_summary"))).contains("verdict");
    }

    @Test
    void anEndedSessionAcceptsNoFurtherRuns() {
        enqueuer.enqueueReport(sessionId, "html", null, null);
        worker.pollOnce();

        assertThat(sessionStatus()).isEqualTo("ended");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> enqueuer.enqueueMessage(sessionId, "one more thing", null, null, null))
                .hasMessageContaining("no further runs");
    }

    private String sessionStatus() {
        return jdbc.queryForObject("select status from autwit.session where session_id = ?",
                String.class, sessionId);
    }

    private String reportBody(String logicalName) {
        return jdbc.queryForObject("""
                select body_text from autwit.artifact
                where session_id = ? and artifact_type = 'final_report' and logical_name = ?
                """, String.class, sessionId, logicalName);
    }
}
