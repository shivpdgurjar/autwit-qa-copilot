package com.autwit.copilot.planning;

import java.util.List;
import java.util.Map;

import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both generations end to end against the {@code fake} planning client: create project + a
 * source → enqueue → the worker calls the (fake) orchestrator → the deliverable is persisted.
 *
 * <p>Drives {@code pollOnce()} directly because {@code AbstractPostgresIT} parks the worker;
 * {@code all} (merged with the parent's {@code fake}) so the worker bean exists.
 */
@ActiveProfiles("all")
class PlanningGenerationRunTest extends AbstractPostgresIT {

    @Autowired
    private PlanningService service;
    @Autowired
    private PlanningRepository repo;
    @Autowired
    private PlanningGenerationWorker worker;

    @Autowired
    private PlanningClient planningClient;

    @Test
    void generatesAndPersistsATestPlan() {
        var project = service.createProject("PAY-2481", "Payment retry logic", null, "m.alvarez", "qa2", null);
        service.addTextDocument(project.projectId(), SourceType.PASTE, "requirement", "spec", null, null,
                "Retry a failed charge with exponential backoff and idempotency.");

        var gen = service.generateTestPlan(project.projectId());
        assertThat(gen.status()).isEqualTo("pending");

        assertThat(worker.pollOnce()).as("the worker claims and runs it").isTrue();

        assertThat(repo.findGeneration(gen.generationId())).get()
                .extracting(Generation::status).isEqualTo("succeeded");

        var plan = repo.findPlanByGeneration(gen.generationId()).orElseThrow();
        assertThat(plan.planVersion()).isEqualTo(2);
        assertThat(plan.overview()).contains("retry");
        assertThat(plan.scenarios()).hasSize(5);
        assertThat(plan.scenarios().get(0).scenarioKey()).isEqualTo("TC-01");

        // The point of the upgrade: a persisted case is materially richer than
        // "TC-01 | title | priority | source".
        var first = plan.scenarios().get(0);
        assertThat(first.capability()).isEqualTo("Retry execution");
        assertThat(first.objective()).isNotBlank();
        assertThat(first.preconditions()).isNotEmpty();
        assertThat(first.steps()).isNotEmpty();
        assertThat(first.expectedResults()).isNotEmpty();
        assertThat(first.requirementIds()).isNotEmpty();
        assertThat(plan.scenarios()).extracting(TestPlan.TestScenario::capability)
                .containsExactly("Retry execution", "Retry execution", "Idempotency",
                        "Backoff and escalation", "Backoff and escalation");
        assertThat(plan.requirements()).isNotEmpty();
        assertThat(plan.scope().inScope()).isNotEmpty();
        assertThat(plan.executionStrategy()).isNotBlank();

        // The chaining token is pinned on the SESSION head (V7) so the next generation reuses it.
        assertThat(repo.findSession(project.sessionId())).get()
                .extracting(PlanningSession::latestResponseId).isEqualTo("resp-fake-plan-PAY-2481");
    }

    @Test
    void existingTestDocumentsTravelAsADistinctInputRatherThanJoiningTheCorpus() {
        // The whole "existing tests are evidence, not a template" contract depends on this
        // split; before it, existing_test_cases was hardcoded empty and every document was
        // folded into one corpus, so the model could only guess from the title.
        var project = service.createProject("PAY-2481", "Payment retry logic", null, "m.alvarez",
                "qa2", "oes");
        service.addTextDocument(project.projectId(), SourceType.PASTE, "requirement", "spec", null,
                null, "Retry a failed charge with exponential backoff.");
        service.addTextDocument(project.projectId(), SourceType.PASTE, "existing_tests", "old cases",
                null, null, "TC-99 legacy retry case");
        service.addTextDocument(project.projectId(), SourceType.PASTE, "architecture", "design",
                null, null, "The retry orchestrator sits after payment auth.");

        var gen = service.generateTestPlan(project.projectId());
        assertThat(worker.pollOnce()).isTrue();
        assertThat(repo.findGeneration(gen.generationId())).get()
                .extracting(Generation::status).isEqualTo("succeeded");

        var request = ((FakePlanningClient) planningClient).lastTestPlanRequest();
        assertThat(request.domain()).isEqualTo("oes");
        assertThat(request.existingTestCases()).extracting(PlanningClient.Doc::title)
                .containsExactly("old cases");
        assertThat(request.sourceDocuments()).extracting(PlanningClient.Doc::title)
                .containsExactlyInAnyOrder("spec", "design");
        assertThat(request.sourceDocuments()).extracting(PlanningClient.Doc::role)
                .containsExactlyInAnyOrder("requirement", "architecture");
    }

    @Test
    void generationPinsTheSessionLineageAndHistoryForReuse() {
        var sp = service.createSession("m.alvarez", "qa2", "Retry", "PAY-2481", "Payment retry logic", null);
        service.addTextDocument(sp.project().projectId(), SourceType.PASTE, "requirement", "spec", null, null, "retry with backoff");

        service.generateTestPlan(sp.project().projectId());
        assertThat(worker.pollOnce()).isTrue();

        // The lineage is pinned on the SESSION head (not just the project) — this is what the
        // next generation reuses via previous_response_id.
        assertThat(repo.findSession(sp.session().sessionId())).get()
                .extracting(PlanningSession::latestResponseId).isEqualTo("resp-fake-plan-PAY-2481");
        // …and the history timeline recorded the arc.
        assertThat(repo.listActivity(sp.session().sessionId()))
                .extracting(PlanningActivity::kind)
                .contains("session_created", "project_added", "document_added", "plan_generated");
    }

    @Test
    void analysisSurfacesFindingsAndGatesGeneration() {
        var project = service.createProject("PAY-2481", "Payment retry logic", null, "m.alvarez", "qa2", null);
        service.addTextDocument(project.projectId(), SourceType.PASTE, "requirement", "spec", null, null,
                "Retry a failed charge; give up after N attempts.");

        var gen = service.analyzeDocuments(project.projectId());
        assertThat(gen.generationType()).isEqualTo(GenerationType.DOCUMENT_ANALYSIS);
        assertThat(worker.pollOnce()).as("the worker runs the analysis").isTrue();

        var reasoning = service.getReasoning(project.projectId()).orElseThrow();
        assertThat(reasoning.reasoning().status()).isEqualTo("open");
        assertThat(reasoning.latest().conflictsTotal()).isEqualTo(1);
        assertThat(reasoning.latest().clarificationsTotal()).isEqualTo(1);
        assertThat(reasoning.latest().findings()).extracting(AnalysisFinding::kind)
                .containsExactlyInAnyOrder("conflict", "clarification");

        // The gate: an open reasoning thread blocks test-plan generation with a 409.
        assertThatThrownBy(() -> service.generateTestPlan(project.projectId()))
                .isInstanceOf(ApiException.Conflict.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("reasoning_incomplete"));
    }

    @Test
    void resolvingAllFindingsClearsTheGateOnReanalysis() {
        var project = service.createProject("PAY-2481", "Payment retry logic", null, "m.alvarez", "qa2", null);
        service.addTextDocument(project.projectId(), SourceType.PASTE, "requirement", "spec", null, null, "retry with backoff");

        service.analyzeDocuments(project.projectId());
        assertThat(worker.pollOnce()).isTrue();

        // Answer every finding (prompt = the finding's title, which the fake keys "resolved" off).
        var round1 = service.getReasoning(project.projectId()).orElseThrow().latest();
        for (var f : round1.findings()) {
            service.addResolution(project.projectId(), f.findingId(), f.kind(), f.title(), "resolved: use 15 attempts");
        }

        // Re-analyze: with both points resolved, the fake returns a clean corpus.
        service.analyzeDocuments(project.projectId());
        assertThat(worker.pollOnce()).isTrue();

        var reasoning = service.getReasoning(project.projectId()).orElseThrow();
        assertThat(reasoning.reasoning().status()).isEqualTo("clean");
        assertThat(reasoning.reasoning().round()).isEqualTo(2);
        assertThat(reasoning.latest().findings()).isEmpty();

        // Gate cleared → generation is allowed and runs.
        var gen = service.generateTestPlan(project.projectId());
        assertThat(gen.status()).isEqualTo("pending");
        assertThat(worker.pollOnce()).isTrue();
        assertThat(repo.findPlanByGeneration(gen.generationId())).isPresent();
    }

    @Test
    void overrideUnlocksGenerationDespiteOpenFindings() {
        var project = service.createProject("PAY-2481", "Payment retry logic", null, "m.alvarez", "qa2", null);
        service.addTextDocument(project.projectId(), SourceType.PASTE, "requirement", "spec", null, null, "retry with backoff");

        service.analyzeDocuments(project.projectId());
        assertThat(worker.pollOnce()).isTrue();
        assertThat(service.getReasoning(project.projectId()).orElseThrow().reasoning().status()).isEqualTo("open");

        service.overrideReasoning(project.projectId(), "Known-good; docs lag the decision", "m.alvarez");

        var reasoning = service.getReasoning(project.projectId()).orElseThrow().reasoning();
        assertThat(reasoning.status()).isEqualTo("overridden");
        assertThat(reasoning.overrideReason()).contains("docs lag");

        // Override unlocks generation.
        var gen = service.generateTestPlan(project.projectId());
        assertThat(gen.status()).isEqualTo("pending");
    }

    @Test
    void generatesAndPersistsTestData() {
        var project = service.createProject("PAY-2481", "Payment retry logic", null, null, "qa2", null);

        var gen = service.generateTestData(project.projectId(),
                List.of(Map.of("id", "TC-01", "title", "Retry succeeds"),
                        Map.of("id", "TC-02", "title", "Retry exhausts")),
                List.of("boundary", "null"), 5, null);

        assertThat(worker.pollOnce()).isTrue();

        assertThat(repo.findGeneration(gen.generationId())).get()
                .extracting(Generation::status).isEqualTo("succeeded");

        var datasets = repo.listDatasetsByGeneration(gen.generationId());
        assertThat(datasets).hasSize(2);
        assertThat(datasets).extracting(TestDataset::scenarioKey).containsExactly("TC-01", "TC-02");
        // 5 rows per scenario, deterministic columns from the fake.
        assertThat(datasets.get(0).rows()).hasSize(5);
        assertThat(datasets.get(0).columns()).contains("transaction_id", "expected_status");
    }
}
