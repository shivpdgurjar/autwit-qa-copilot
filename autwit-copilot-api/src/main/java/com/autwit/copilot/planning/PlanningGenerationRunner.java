package com.autwit.copilot.planning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
            case DOCUMENT_ANALYSIS -> runDocumentAnalysis(gen, workerId, project, session);
        }
    }

    private void runTestPlan(Generation gen, String workerId, PlanningProject project, PlanningSession session) {
        var selected = repo.listSelectedDocuments(project.projectId());

        // Documents tagged `existing_tests` travel as a DISTINCT input, not folded into the
        // corpus. That separation is what lets the generator treat them as evidence of
        // intended coverage instead of rows to reproduce as new cases.
        var corpus = new ArrayList<PlanningClient.Doc>();
        var existingTests = new ArrayList<PlanningClient.Doc>();
        for (var d : selected) {
            var doc = new PlanningClient.Doc(d.sourceType().wire(), d.docRole().wire(), d.title(),
                    d.textContent());
            if (d.docRole() == DocRole.EXISTING_TESTS) {
                existingTests.add(doc);
            } else {
                corpus.add(doc);
            }
        }

        // Seed from the SESSION lineage, not the project — so a re-plan (and later data) build on
        // whatever the session has generated so far (the reusable-history requirement).
        var request = new PlanningClient.TestPlanRequest(
                project.featureKey(), project.featureDescription(), corpus, existingTests,
                project.domain(), session.latestResponseId());

        var result = client.generateTestPlan(request);

        // Flattened in capability order; the orchestrator already de-duplicated the ids, which
        // the (test_plan_id, scenario_key) primary key depends on.
        var scenarios = new ArrayList<TestPlan.TestScenario>();
        int seq = 1;
        for (var cap : result.capabilities()) {
            for (var s : cap.testCases()) {
                scenarios.add(new TestPlan.TestScenario(
                        s.id(), seq++, cap.name(), s.title(), s.priority(), s.objective(),
                        s.lifecyclePhase(), s.sources(), s.requirementIds(), s.preconditions(),
                        s.steps(), s.expectedResults(), s.testDataRequirements(),
                        s.automationMapping(),
                        s.sources().isEmpty() ? null : s.sources().get(0)));
            }
        }
        repo.insertPlan(project.projectId(), gen.generationId(), result, scenarios);

        finish(gen, workerId, session, result.responseId(), "plan_generated",
                "Generated a test plan (" + scenarios.size() + " cases across "
                        + result.capabilities().size() + " capabilities)");
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
     * The reasoning pass: analyze the selected corpus (plus the tester's accumulated resolutions)
     * for conflicts and clarifications, persist the round + findings, and set the thread clean or
     * open. Chains on the session lineage exactly like the generators; the analysis is advisory —
     * the tester resolves each finding, the model decides nothing.
     */
    private void runDocumentAnalysis(Generation gen, String workerId, PlanningProject project,
            PlanningSession session) {
        var reasoning = repo.findReasoningByProject(project.projectId())
                .orElseThrow(() -> new IllegalStateException(
                        "planning_reasoning gone for project " + project.projectId()));

        var selected = repo.listSelectedDocuments(project.projectId());
        var docs = selected.stream()
                .map(d -> new PlanningClient.Doc(d.sourceType().wire(), d.docRole().wire(), d.title(),
                        d.textContent()))
                .toList();
        var resolutions = repo.listResolutions(reasoning.reasoningId()).stream()
                .map(r -> new PlanningClient.ResolutionRef(r.prompt(), r.kind(), r.answer()))
                .toList();

        var request = new PlanningClient.AnalyzeRequest(
                project.featureKey(), project.featureDescription(), docs, resolutions,
                session.latestResponseId());
        var result = client.analyzeDocuments(request);

        var findings = new ArrayList<AnalysisFinding>();
        for (var c : result.conflicts()) {
            findings.add(toFinding("conflict", c));
        }
        for (var c : result.clarifications()) {
            findings.add(toFinding("clarification", c));
        }
        repo.createAnalysisRound(reasoning.reasoningId(), gen.generationId(), reasoning.round(),
                result.conflicts().size(), result.clarifications().size(), findings);

        boolean clean = result.conflicts().isEmpty() && result.clarifications().isEmpty();
        repo.setReasoningStatus(reasoning.reasoningId(), clean ? "clean" : "open");

        int total = result.conflicts().size() + result.clarifications().size();
        finish(gen, workerId, session, result.responseId(), "documents_analyzed",
                clean ? "Analyzed documents — no conflicts or gaps"
                        : "Analyzed documents — " + total + " item(s) to resolve");
    }

    private static AnalysisFinding toFinding(String kind, PlanningClient.Finding f) {
        var sources = new ArrayList<Map<String, Object>>();
        for (var s : f.sources()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("doc_title", s.docTitle());
            m.put("quote", s.quote());
            sources.add(m);
        }
        // seq is assigned by the repository as it inserts; findingId is DB-generated.
        return new AnalysisFinding(null, kind, 0, f.title(), f.detail(), sources, f.options());
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
