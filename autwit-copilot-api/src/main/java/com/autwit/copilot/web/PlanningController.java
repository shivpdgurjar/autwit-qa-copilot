package com.autwit.copilot.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.planning.Generation;
import com.autwit.copilot.planning.PlanningClient;
import com.autwit.copilot.planning.PlanningService;
import com.autwit.copilot.planning.SourceDocument;
import com.autwit.copilot.planning.SourceType;
import com.autwit.copilot.planning.TestDataset;
import com.autwit.copilot.planning.TestPlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Planning Copilot ("Test Plan & Data Studio") surface — the second flavor of the app,
 * under its own {@code /planning} namespace, entirely separate from the session/execution
 * routes. Backs the four-step wizard: add inputs → fetch context → test plan → test data.
 *
 * <p>Generation is async: the two generate endpoints return 202 with a generation id the UI
 * polls, the same posture as the financial analysis run.
 */
@RestController
@RequestMapping("/planning")
public class PlanningController {

    private final PlanningService planning;

    public PlanningController(PlanningService planning) {
        this.planning = planning;
    }

    // ---- projects --------------------------------------------------------------------

    public record CreateProjectRequest(String featureKey, String featureDescription, String title,
            String createdBy, String env) {
    }

    public record ProjectView(String projectId, String featureKey, String featureDescription, String title,
            String status, String createdBy, String env, boolean chainable, Instant createdAt) {
    }

    @PostMapping("/projects")
    ResponseEntity<ProjectView> createProject(@RequestBody CreateProjectRequest req) {
        var p = planning.createProject(req.featureKey(), req.featureDescription(), req.title(),
                req.createdBy(), req.env());
        return ResponseEntity.status(201).body(project(p));
    }

    @GetMapping("/projects")
    List<ProjectView> listProjects(@RequestParam(defaultValue = "50") int limit) {
        return planning.listProjects(limit).stream().map(PlanningController::project).toList();
    }

    @GetMapping("/projects/{projectId}")
    ProjectView getProject(@PathVariable UUID projectId) {
        return project(planning.requireProject(projectId));
    }

    // ---- step 1: documents -----------------------------------------------------------

    public record AddDocumentRequest(
            @NotBlank String sourceType,
            String title,
            String filename,
            String mime,
            @NotBlank String text) {
    }

    public record DocumentView(String documentId, String sourceType, String externalRef, String title,
            String mime, boolean selected, int textLength, Instant createdAt) {
    }

    @PostMapping("/projects/{projectId}/documents")
    ResponseEntity<DocumentView> addDocument(@PathVariable UUID projectId,
            @Valid @RequestBody AddDocumentRequest req) {
        var doc = planning.addTextDocument(projectId, SourceType.fromWire(req.sourceType()),
                req.title(), req.filename(), req.mime(), req.text());
        return ResponseEntity.status(201).body(document(doc));
    }

    @GetMapping("/projects/{projectId}/documents")
    List<DocumentView> listDocuments(@PathVariable UUID projectId) {
        return planning.listDocuments(projectId).stream().map(PlanningController::document).toList();
    }

    public record SelectRequest(boolean selected) {
    }

    @PatchMapping("/projects/{projectId}/documents/{documentId}")
    ResponseEntity<Void> select(@PathVariable UUID projectId, @PathVariable UUID documentId,
            @RequestBody SelectRequest req) {
        planning.setSelected(projectId, documentId, req.selected());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/projects/{projectId}/documents/{documentId}")
    ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID documentId) {
        planning.deleteDocument(projectId, documentId);
        return ResponseEntity.noContent().build();
    }

    // ---- step 2: fetch context -------------------------------------------------------

    public record CandidateView(String ref, String title, String kind, String status, String meta, String url) {
    }

    @GetMapping("/projects/{projectId}/jira-search")
    List<CandidateView> jiraSearch(@PathVariable UUID projectId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) Integer max) {
        return planning.searchJira(projectId, query, project, max).stream()
                .map(PlanningController::candidate).toList();
    }

    @GetMapping("/projects/{projectId}/confluence-search")
    List<CandidateView> confluenceSearch(@PathVariable UUID projectId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String space,
            @RequestParam(required = false) Integer max) {
        return planning.searchConfluence(projectId, space, query, max).stream()
                .map(PlanningController::candidate).toList();
    }

    public record FetchRequest(List<String> jiraKeys, List<String> confluencePageIds) {
    }

    public record LogLineView(String ts, String level, String source, String ref, String message) {
    }

    public record FetchResponse(List<DocumentView> documents, List<LogLineView> log) {
    }

    @PostMapping("/projects/{projectId}/fetch")
    FetchResponse fetch(@PathVariable UUID projectId, @RequestBody FetchRequest req) {
        var outcome = planning.fetchContext(projectId, req.jiraKeys(), req.confluencePageIds());
        var docs = outcome.documents().stream().map(PlanningController::document).toList();
        var log = outcome.log().stream()
                .map(l -> new LogLineView(l.ts(), l.level(), l.source(), l.ref(), l.message()))
                .toList();
        return new FetchResponse(docs, log);
    }

    // ---- step 3 & 4: generation ------------------------------------------------------

    public record GenerationView(String generationId, String projectId, String generationType, String status,
            String responseId, Map<String, Object> error, Instant createdAt, Instant updatedAt) {
    }

    @PostMapping("/projects/{projectId}/test-plan")
    ResponseEntity<GenerationView> generateTestPlan(@PathVariable UUID projectId) {
        var gen = planning.generateTestPlan(projectId);
        return ResponseEntity.accepted().body(generation(gen));
    }

    public record GenerateDataRequest(
            List<Map<String, Object>> scenarios,
            List<String> edgeCases,
            Integer rowsPerScenario,
            Map<String, Object> exampleRecord) {
    }

    @PostMapping("/projects/{projectId}/test-data")
    ResponseEntity<GenerationView> generateTestData(@PathVariable UUID projectId,
            @RequestBody GenerateDataRequest req) {
        // rows_per_scenario default is owned by the service (single source); pass through as-is.
        var gen = planning.generateTestData(projectId, req.scenarios(), req.edgeCases(),
                req.rowsPerScenario(), req.exampleRecord());
        return ResponseEntity.accepted().body(generation(gen));
    }

    @GetMapping("/projects/{projectId}/generations")
    List<GenerationView> listGenerations(@PathVariable UUID projectId) {
        return planning.listGenerations(projectId).stream().map(PlanningController::generation).toList();
    }

    @GetMapping("/projects/{projectId}/generations/{generationId}")
    GenerationView getGeneration(@PathVariable UUID projectId, @PathVariable UUID generationId) {
        return generation(planning.requireGeneration(projectId, generationId));
    }

    // ---- deliverables ----------------------------------------------------------------

    public record ScenarioView(String scenarioKey, int seq, String title, String priority, String source) {
    }

    public record PlanView(String testPlanId, String generationId, String overview, String scope,
            Map<String, Object> provenance, List<ScenarioView> scenarios, Instant createdAt) {
    }

    @GetMapping("/projects/{projectId}/test-plan")
    ResponseEntity<PlanView> latestPlan(@PathVariable UUID projectId) {
        return planning.latestPlan(projectId).map(p -> ResponseEntity.ok(plan(p)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/projects/{projectId}/generations/{generationId}/test-plan")
    ResponseEntity<PlanView> planByGeneration(@PathVariable UUID projectId, @PathVariable UUID generationId) {
        return planning.planByGeneration(projectId, generationId).map(p -> ResponseEntity.ok(plan(p)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record DatasetView(String scenarioKey, List<String> columns, List<Map<String, Object>> rows) {
    }

    @GetMapping("/projects/{projectId}/generations/{generationId}/test-data")
    List<DatasetView> datasets(@PathVariable UUID projectId, @PathVariable UUID generationId) {
        return planning.datasets(projectId, generationId).stream()
                .map(d -> new DatasetView(d.scenarioKey(), d.columns(), d.rows())).toList();
    }

    // ---- mappers ---------------------------------------------------------------------

    private static ProjectView project(com.autwit.copilot.planning.PlanningProject p) {
        return new ProjectView(p.projectId().toString(), p.featureKey(), p.featureDescription(), p.title(),
                p.status(), p.createdBy(), p.env(), p.latestResponseId() != null, p.createdAt());
    }

    private static DocumentView document(SourceDocument d) {
        return new DocumentView(d.documentId().toString(), d.sourceType().wire(), d.externalRef(), d.title(),
                d.mime(), d.selected(), d.textContent() == null ? 0 : d.textContent().length(), d.createdAt());
    }

    private static CandidateView candidate(PlanningClient.Candidate c) {
        return new CandidateView(c.ref(), c.title(), c.kind(), c.status(), c.meta(), c.url());
    }

    private static GenerationView generation(Generation g) {
        return new GenerationView(g.generationId().toString(), g.projectId().toString(),
                g.generationType().wire(), g.status(), g.responseId(), g.error(), g.createdAt(), g.updatedAt());
    }

    private static PlanView plan(TestPlan p) {
        var scenarios = p.scenarios().stream()
                .map(s -> new ScenarioView(s.scenarioKey(), s.seq(), s.title(), s.priority(), s.source()))
                .toList();
        return new PlanView(p.testPlanId().toString(), p.generationId().toString(), p.overview(), p.scope(),
                p.provenance(), scenarios, p.createdAt());
    }
}
