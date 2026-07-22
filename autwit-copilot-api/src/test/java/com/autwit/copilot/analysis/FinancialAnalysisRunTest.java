package com.autwit.copilot.analysis;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.ArtifactFormat;
import com.autwit.copilot.artifact.ArtifactService;
import com.autwit.copilot.compare.FindingRepository;
import com.autwit.copilot.run.RunWorker;
import com.autwit.copilot.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.autwit.copilot.common.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The financial analysis run end to end against the {@code fake} client: assemble evidence →
 * enqueue → the worker calls the (fake) orchestrator → the verdict is persisted.
 *
 * <p>Drives {@code pollOnce()} directly because {@code AbstractPostgresIT} parks the worker;
 * that is the same way the other queue tests step the run machinery.
 *
 * <p>{@code all} (merged with the parent's {@code fake}) so the RunWorker bean exists —
 * we drive it by hand at concurrency 0, per {@code AbstractPostgresIT}.
 */
@ActiveProfiles("all")
class FinancialAnalysisRunTest extends AbstractPostgresIT {

    @Autowired
    private AnalysisService analyses;
    @Autowired
    private ArtifactService artifacts;
    @Autowired
    private RunWorker worker;
    @Autowired
    private AnalysisRepository analysisRepo;
    @Autowired
    private FindingRepository findings;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID sessionId;

    @BeforeEach
    void seed() {
        sessionId = UUID.randomUUID();
        jdbc.update("insert into autwit.session(session_id, correlation_id, tester_id, env) values (?,?,?,?)",
                sessionId, "corr-" + sessionId, "priya", "qa2");
    }

    private UUID captureOrder() {
        return captureOrder("order", "24.00");
    }

    private UUID captureOrder(String name, String total) {
        return artifacts.persist(sessionId, null, null, null, "api_response", "oms", name,
                ArtifactFormat.JSON, Map.of("orderId", "XXXX", "total", total), null, null, Map.of())
                .artifactId();
    }

    @Test
    void assembleEnqueueRunPersistsTheVerdict() {
        var artifactId = captureOrder();

        var result = analyses.createAndAssemble(sessionId, "SNAPSHOT_SANCTITY", "XXXX",
                List.of(EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, artifactId)));
        var analysisId = result.session().analysisId();
        var runId = result.run().run().runId();

        // The run exists, queued, not yet executed.
        assertThat(jdbc.queryForObject("select status from autwit.run where run_id = ?", String.class, runId))
                .isEqualTo("queued");

        assertThat(worker.pollOnce()).as("the worker claims and runs it").isTrue();

        // Terminal state + rich summary from the (fake) verdict.
        assertThat(jdbc.queryForObject("select status from autwit.run where run_id = ?", String.class, runId))
                .isEqualTo("succeeded");
        var summary = jdbc.queryForObject(
                "select result_summary::text from autwit.run where run_id = ?", String.class, runId);
        // jsonb::text renders a space after the colon.
        assertThat(summary)
                .contains("\"overall_status\": \"FAIL\"")
                .contains("\"ai_analysis_status\": \"UNAVAILABLE\"")
                .contains("\"findings_fail\": 1");

        // The FAIL finding reached the findings feed, severity-mapped ERROR → high.
        var feed = findings.listBySession(sessionId, null, null);
        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).severity()).isEqualTo("high");
        assertThat(feed.get(0).category()).isEqualTo("ARITHMETIC");
        assertThat(feed.get(0).message()).contains("expected 24.00, actual 26.00");

        // The chaining token and the real versions are pinned on the session head, and the
        // "unpinned" placeholder is gone.
        var session = analysisRepo.findSession(analysisId).orElseThrow();
        assertThat(session.latestResponseId()).isEqualTo("resp-fake-" + analysisId);
        assertThat(session.ruleVersion()).isEqualTo("oms-financial-rules-v1.1");
        assertThat(session.promptVersion()).isEqualTo("oms-financial-validator-v1.0");
    }

    @Test
    void aMultiStateLifecycleRunTakesTheAnalyzeLifecyclePath() {
        // Two states, LIFECYCLE_COMPARISON — exercises the runner's analyzeLifecycle
        // branch (the snapshot test covers analyzeSnapshot). This is the path
        // financial.analyze_lifecycle needs proven before the orchestrator registers it.
        var a = captureOrder("order-v1", "24.00");
        var b = captureOrder("order-v2", "26.00");

        var result = analyses.createAndAssemble(sessionId, "LIFECYCLE_COMPARISON", "XXXX",
                List.of(EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, a),
                        EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, b)));
        var analysisId = result.session().analysisId();
        var runId = result.run().run().runId();

        assertThat(worker.pollOnce()).isTrue();

        assertThat(jdbc.queryForObject("select status from autwit.run where run_id = ?", String.class, runId))
                .isEqualTo("succeeded");
        var summary = jdbc.queryForObject(
                "select result_summary::text from autwit.run where run_id = ?", String.class, runId);
        assertThat(summary).contains("\"overall_status\": \"FAIL\"");

        // Two states were assembled and sent; the session records the lifecycle mode.
        assertThat(analysisRepo.readStates(analysisId)).hasSize(2);
        var session = analysisRepo.findSession(analysisId).orElseThrow();
        assertThat(session.analysisMode()).isEqualTo("LIFECYCLE_COMPARISON");
        assertThat(session.latestResponseId()).isEqualTo("resp-fake-" + analysisId);
    }

    @Test
    void aFollowUpSeedsThePriorConversationToken() {
        // Run analysis A to completion — the fake pins a response id on its session head.
        var a = captureOrder("order-a", "24.00");
        var first = analyses.createAndAssemble(sessionId, "SNAPSHOT_SANCTITY", "XXXX",
                List.of(EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, a)));
        var priorAnalysisId = first.session().analysisId();
        assertThat(worker.pollOnce()).isTrue();
        var priorToken = analysisRepo.findSession(priorAnalysisId).orElseThrow().latestResponseId();
        assertThat(priorToken).isEqualTo("resp-fake-" + priorAnalysisId);

        // A follow-up that continues A is seeded with A's token, so its first orchestrator
        // call will send it as previous_response_id (checked before it runs, so the fake has
        // not yet overwritten it with the follow-up's own response).
        var b = captureOrder("order-b", "26.00");
        var followUp = analyses.createAndAssemble(sessionId, "SNAPSHOT_SANCTITY", "XXXX",
                List.of(EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, b)), priorAnalysisId);
        assertThat(followUp.chainedFrom()).isEqualTo(priorAnalysisId);
        assertThat(analysisRepo.findSession(followUp.session().analysisId()).orElseThrow()
                .latestResponseId()).isEqualTo(priorToken);

        // Drain the follow-up's own run so the queue is left as empty as we found it — the
        // queue is a JVM-wide FIFO with no per-method reset, and a leftover would be claimed
        // by a sibling test's pollOnce() (see AbstractPostgresIT).
        assertThat(worker.pollOnce()).isTrue();
    }

    @Test
    void chainingToANotYetRunAnalysisIsRejected() {
        // A prior analysis that has not run has no response to continue — reject, don't
        // silently seed null (the tester asked to continue a conversation that does not exist).
        var a = captureOrder("order-a", "24.00");
        var prior = analyses.createAndAssemble(sessionId, "SNAPSHOT_SANCTITY", "XXXX",
                List.of(EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, a)));
        var b = captureOrder("order-b", "26.00");
        assertThatThrownBy(() -> analyses.createAndAssemble(sessionId, "SNAPSHOT_SANCTITY", "XXXX",
                List.of(EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, b)), prior.session().analysisId()))
                .isInstanceOf(ApiException.BadRequest.class)
                .hasMessageContaining("has no conversation");

        // Drain the prior's run — it was enqueued but never polled here (see the note in
        // aFollowUpSeedsThePriorConversationToken): leave the FIFO queue empty for siblings.
        assertThat(worker.pollOnce()).isTrue();
    }

    @Test
    void aPassOnlyResultWouldLeaveTheFeedEmpty() {
        // The fake always returns one FAIL, so here we just assert the mapping does not
        // invent findings: exactly the one the fake emits, no PASS rows.
        var artifactId = captureOrder();
        analyses.createAndAssemble(sessionId, "SNAPSHOT_SANCTITY", "XXXX",
                List.of(EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, artifactId)));
        worker.pollOnce();
        assertThat(findings.listBySession(sessionId, null, null)).hasSize(1);
    }
}
