package com.autwit.copilot.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes one planning generation: read the project + its selected corpus, call the
 * orchestrator's generate skill, persist the deliverable, pin the chaining token.
 *
 * <p>Mirrors {@code FinancialAnalysisRunner}. REQUIRES_NEW for the same reason: the terminal
 * {@code succeedGeneration}/{@code failGeneration} is the last write, and if it does not land
 * (the job already went terminal elsewhere) everything written here unwinds with it.
 */
@Service
public class PlanningGenerationRunner {

    private final PlanningRepository repo;
    private final PlanningClient client;

    public PlanningGenerationRunner(PlanningRepository repo, PlanningClient client) {
        this.repo = repo;
        this.client = client;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(Generation gen, String workerId) {
        var project = repo.findProject(gen.projectId())
                .orElseThrow(() -> new IllegalStateException("planning_project gone: " + gen.projectId()));
        var session = repo.findSession(project.sessionId())
                .orElseThrow(() -> new IllegalStateException("planning_session gone: " + project.sessionId()));

        switch (gen.generationType()) {
            case TEST_PLAN -> runTestPlan(gen, workerId, project, session);
            case TEST_DATA -> runTestData(gen, workerId, project, session);
        }
    }

    private void runTestPlan(Generation gen, String workerId, PlanningProject project, PlanningSession session) {
        var selected = repo.listSelectedDocuments(project.projectId());
        var docs = selected.stream()
                .map(d -> new PlanningClient.Doc(d.sourceType().wire(), d.title(), d.textContent()))
                .toList();

        // Seed from the SESSION lineage, not the project — so a re-plan (and later data) build on
        // whatever the session has generated so far (the reusable-history requirement).
        var request = new PlanningClient.TestPlanRequest(
                project.featureKey(), project.featureDescription(), docs,
                // Existing-test-cases as a distinct input is a pass-2 refinement; pass 1 folds
                // everything selected into the corpus.
                List.of(), session.latestResponseId());

        var result = client.generateTestPlan(request);

        var scenarios = new ArrayList<TestPlan.TestScenario>();
        int seq = 1;
        for (var s : result.scenarios()) {
            scenarios.add(new TestPlan.TestScenario(s.id(), seq++, s.title(), s.priority(), s.source()));
        }
        repo.insertPlan(project.projectId(), gen.generationId(), result.overview(), result.scope(),
                result.provenance(), scenarios);

        finish(gen, workerId, session, result.responseId(),
                "plan_generated", "Generated a test plan (" + scenarios.size() + " scenarios)");
    }

    @SuppressWarnings("unchecked")
    private void runTestData(Generation gen, String workerId, PlanningProject project, PlanningSession session) {
        var config = gen.config();
        var scenarioRefs = new ArrayList<PlanningClient.ScenarioRef>();
        for (var raw : (List<Object>) config.getOrDefault("scenarios", List.of())) {
            var m = (Map<String, Object>) raw;
            scenarioRefs.add(new PlanningClient.ScenarioRef(
                    String.valueOf(m.get("id")), String.valueOf(m.get("title"))));
        }
        var edgeCases = new ArrayList<String>();
        for (var e : (List<Object>) config.getOrDefault("edge_cases", List.of())) {
            edgeCases.add(String.valueOf(e));
        }
        int rows = config.get("rows_per_scenario") instanceof Number nr
                ? nr.intValue() : PlanningService.DEFAULT_ROWS_PER_SCENARIO;
        var exampleRecord = config.get("example_record") instanceof Map<?, ?> em
                ? (Map<String, Object>) em : null;

        var request = new PlanningClient.TestDataRequest(
                scenarioRefs, exampleRecord, edgeCases, rows, session.latestResponseId());

        var result = client.generateTestData(request);

        for (var ds : result.datasets()) {
            repo.insertDataset(project.projectId(), gen.generationId(), ds.scenarioKey(),
                    ds.columns(), ds.rows());
        }

        finish(gen, workerId, session, result.responseId(),
                "data_generated", "Generated test data (" + result.datasets().size() + " scenarios)");
    }

    /**
     * Pin the lineage on the SESSION head (optimistic), record the history entry, then mark the
     * job succeeded. Pinning on the session is what lets the next generation reuse this one.
     */
    private void finish(Generation gen, String workerId, PlanningSession session, String responseId,
            String activityKind, String activitySummary) {
        if (responseId != null) {
            repo.bumpSessionHead(session.sessionId(), session.version(), responseId);
        }
        repo.addActivity(session.sessionId(), activityKind, gen.generationId().toString(), activitySummary);
        if (!repo.succeedGeneration(gen.generationId(), workerId, responseId)) {
            throw new IllegalStateException("generation " + gen.generationId()
                    + " was no longer running when it completed");
        }
    }
}
