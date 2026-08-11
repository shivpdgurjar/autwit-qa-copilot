package com.autwit.copilot.web;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.planning.Generation;
import com.autwit.copilot.planning.PlanningAnalysis;
import com.autwit.copilot.planning.PlanningClient;
import com.autwit.copilot.planning.PlanningService;
import com.autwit.copilot.planning.SourceDocument;
import com.autwit.copilot.planning.SourceType;
import com.autwit.copilot.planning.TestDataset;
import com.autwit.copilot.planning.TestPlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

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

    // ---- sessions (resumable, history-bearing context) -------------------------------

    public record CreateSessionRequest(String testerId, String env, String title,
            String featureKey, String featureDescription,
            /** Domain-context key, e.g. "oes"; null leaves the plan domain-neutral. */
            String domain) {
    }

    public record SessionView(String sessionId, String testerId, String env, String title, String status,
            boolean chainable, Instant createdAt, Instant lastActiveAt) {
    }

    public record ActivityView(long id, String kind, String ref, String summary, Instant at) {
    }

    public record SessionDetailView(SessionView session, List<ProjectView> projects, List<ActivityView> activity) {
    }

    public record CreateSessionResponse(SessionView session, ProjectView project) {
    }

    @PostMapping("/sessions")
    ResponseEntity<CreateSessionResponse> createSession(@RequestBody CreateSessionRequest req) {
        var r = planning.createSession(req.testerId(), req.env(), req.title(),
                req.featureKey(), req.featureDescription(), req.domain());
        return ResponseEntity.status(201)
                .body(new CreateSessionResponse(session(r.session()), project(r.project())));
    }

    @GetMapping("/sessions")
    List<SessionView> listSessions(@RequestParam(name = "tester_id", required = false) String testerId,
            @RequestParam(defaultValue = "50") int limit) {
        return planning.listRecentSessions(testerId, limit).stream().map(PlanningController::session).toList();
    }

    @GetMapping("/sessions/{sessionId}")
    SessionDetailView getSession(@PathVariable UUID sessionId) {
        var d = planning.getSession(sessionId);
        return new SessionDetailView(
                session(d.session()),
                d.projects().stream().map(PlanningController::project).toList(),
                d.activity().stream()
                        .map(a -> new ActivityView(a.id(), a.kind(), a.ref(), a.summary(), a.at()))
                        .toList());
    }

    // ---- projects --------------------------------------------------------------------

    public record CreateProjectRequest(String featureKey, String featureDescription, String title,
            String createdBy, String env, String domain) {
    }

    public record ProjectView(String projectId, String sessionId, String featureKey, String featureDescription,
            String domain, String title, String status, String createdBy, String env, boolean chainable,
            Instant createdAt) {
    }

    @PostMapping("/projects")
    ResponseEntity<ProjectView> createProject(@RequestBody CreateProjectRequest req) {
        var p = planning.createProject(req.featureKey(), req.featureDescription(), req.title(),
                req.createdBy(), req.env(), req.domain());
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
            /** What the document IS. Absent or unknown reads as "requirement". */
            String docRole,
            String title,
            String filename,
            String mime,
            @NotBlank String text) {
    }

    public record DocumentView(String documentId, String sourceType, String docRole, String externalRef,
            String title, String mime, boolean selected, int textLength, Instant createdAt) {
    }

    @PostMapping("/projects/{projectId}/documents")
    ResponseEntity<DocumentView> addDocument(@PathVariable UUID projectId,
            @Valid @RequestBody AddDocumentRequest req) {
        var doc = planning.addTextDocument(projectId, SourceType.fromWire(req.sourceType()),
                req.docRole(), req.title(), req.filename(), req.mime(), req.text());
        return ResponseEntity.status(201).body(document(doc));
    }

    /**
     * Upload a file (PDF/DOCX/XLSX or text) — the bytes are parsed server-side by Tika. The JSON
     * endpoint above stays for paste; the wizard's file picker posts here as multipart.
     */
    @PostMapping(value = "/projects/{projectId}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentView> uploadDocument(@PathVariable UUID projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "doc_role", required = false) String docRole) {
        if (file == null || file.isEmpty()) {
            throw new ApiException.BadRequest("empty_document", "No file was uploaded.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException.BadRequest("upload_failed", "Could not read the uploaded file: " + e.getMessage());
        }
        var doc = planning.addUploadedFile(projectId, file.getOriginalFilename(), file.getContentType(),
                bytes, title, docRole);
        return ResponseEntity.status(201).body(document(doc));
    }

    @GetMapping("/projects/{projectId}/documents")
    List<DocumentView> listDocuments(@PathVariable UUID projectId) {
        return planning.listDocuments(projectId).stream().map(PlanningController::document).toList();
    }

    /** Both fields optional: a PATCH may toggle selection, re-tag the role, or both. */
    public record SelectRequest(Boolean selected, String docRole) {
    }

    @PatchMapping("/projects/{projectId}/documents/{documentId}")
    ResponseEntity<Void> select(@PathVariable UUID projectId, @PathVariable UUID documentId,
            @RequestBody SelectRequest req) {
        if (req.selected() != null) {
            planning.setSelected(projectId, documentId, req.selected());
        }
        if (req.docRole() != null) {
            planning.setDocRole(projectId, documentId, req.docRole());
        }
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

    // ---- reasoning (pre-generation conflict/clarification loop) -----------------------

    public record FindingView(String findingId, String kind, int seq, String title, String detail,
            List<Map<String, Object>> sources, List<String> options) {
    }

    public record AnalysisView(String analysisId, String generationId, int round, int conflictsTotal,
            int clarificationsTotal, List<FindingView> findings, Instant createdAt) {
    }

    public record ResolutionView(String resolutionId, int round, String findingId, String kind,
            String prompt, String answer, Instant createdAt) {
    }

    public record ReasoningView(String reasoningId, String status, int round, String overrideReason,
            Instant overrideAt, AnalysisView latest, List<ResolutionView> resolutions) {
    }

    /** Start (or re-run) a reasoning round over the selected corpus. Async, like the generators. */
    @PostMapping("/projects/{projectId}/analyze")
    ResponseEntity<GenerationView> analyze(@PathVariable UUID projectId) {
        return ResponseEntity.accepted().body(generation(planning.analyzeDocuments(projectId)));
    }

    @GetMapping("/projects/{projectId}/reasoning")
    ResponseEntity<ReasoningView> reasoning(@PathVariable UUID projectId) {
        return planning.getReasoning(projectId).map(d -> ResponseEntity.ok(reasoningView(d)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record AddResolutionRequest(String findingId, String kind, String prompt, String answer) {
    }

    @PostMapping("/projects/{projectId}/reasoning/resolutions")
    ResponseEntity<Void> addResolution(@PathVariable UUID projectId, @RequestBody AddResolutionRequest req) {
        var findingId = req.findingId() == null || req.findingId().isBlank()
                ? null : UUID.fromString(req.findingId());
        planning.addResolution(projectId, findingId, req.kind(), req.prompt(), req.answer());
        return ResponseEntity.noContent().build();
    }

    public record OverrideRequest(String reason, String by) {
    }

    @PostMapping("/projects/{projectId}/reasoning/override")
    ResponseEntity<Void> overrideReasoning(@PathVariable UUID projectId, @RequestBody OverrideRequest req) {
        planning.overrideReasoning(projectId, req.reason(), req.by());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/projects/{projectId}/generations/{generationId}")
    GenerationView getGeneration(@PathVariable UUID projectId, @PathVariable UUID generationId) {
        return generation(planning.requireGeneration(projectId, generationId));
    }

    // ---- deliverables ----------------------------------------------------------------

    /** One executable test case. Legacy (plan_version 1) rows carry only the first five fields. */
    public record ScenarioView(String scenarioKey, int seq, String capability, String title, String priority,
            String objective, String lifecyclePhase, List<String> sources, List<String> requirementIds,
            List<String> preconditions, List<String> steps, List<String> expectedResults,
            List<String> testDataRequirements, Map<String, Object> automationMapping, String source) {
    }

    public record ScopeView(List<String> inScope, List<String> outOfScope) {
    }

    public record RequirementView(String id, String statement, String category, List<String> sources,
            String evidence, String lifecyclePhase) {
    }

    public record TestDataRequirementView(String id, String name, String description,
            List<String> attributes, String sourceOfTruth) {
    }

    /** A capability group — how the plan is organised and how the UI renders it. */
    public record CapabilityView(String name, String description, List<ScenarioView> testCases) {
    }

    public record PlanView(String testPlanId, String generationId, int planVersion, String overview,
            ScopeView scope, Map<String, Object> architectureContext, List<RequirementView> requirements,
            List<TestDataRequirementView> testDataRequirements, List<CapabilityView> capabilities,
            String executionStrategy, List<Map<String, Object>> risks, List<Map<String, Object>> gaps,
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

    private static SessionView session(com.autwit.copilot.planning.PlanningSession s) {
        return new SessionView(s.sessionId().toString(), s.testerId(), s.env(), s.title(), s.status(),
                s.latestResponseId() != null, s.createdAt(), s.lastActiveAt());
    }

    private static ProjectView project(com.autwit.copilot.planning.PlanningProject p) {
        return new ProjectView(p.projectId().toString(), p.sessionId().toString(), p.featureKey(),
                p.featureDescription(), p.domain(), p.title(),
                p.status(), p.createdBy(), p.env(), p.latestResponseId() != null, p.createdAt());
    }

    private static DocumentView document(SourceDocument d) {
        return new DocumentView(d.documentId().toString(), d.sourceType().wire(), d.docRole().wire(),
                d.externalRef(), d.title(), d.mime(), d.selected(),
                d.textContent() == null ? 0 : d.textContent().length(), d.createdAt());
    }

    private static CandidateView candidate(PlanningClient.Candidate c) {
        return new CandidateView(c.ref(), c.title(), c.kind(), c.status(), c.meta(), c.url());
    }

    private static GenerationView generation(Generation g) {
        return new GenerationView(g.generationId().toString(), g.projectId().toString(),
                g.generationType().wire(), g.status(), g.responseId(), g.error(), g.createdAt(), g.updatedAt());
    }

    private static ReasoningView reasoningView(PlanningService.ReasoningDetail d) {
        var r = d.reasoning();
        var latest = d.latest() == null ? null : analysis(d.latest());
        var resolutions = d.resolutions().stream()
                .map(x -> new ResolutionView(x.resolutionId().toString(), x.round(),
                        x.findingId() == null ? null : x.findingId().toString(),
                        x.kind(), x.prompt(), x.answer(), x.createdAt()))
                .toList();
        return new ReasoningView(r.reasoningId().toString(), r.status(), r.round(),
                r.overrideReason(), r.overrideAt(), latest, resolutions);
    }

    private static AnalysisView analysis(PlanningAnalysis a) {
        var findings = a.findings().stream()
                .map(f -> new FindingView(f.findingId().toString(), f.kind(), f.seq(), f.title(), f.detail(),
                        f.sources(), f.options()))
                .toList();
        return new AnalysisView(a.analysisId().toString(), a.generationId().toString(), a.round(),
                a.conflictsTotal(), a.clarificationsTotal(), findings, a.createdAt());
    }

    private static PlanView plan(TestPlan p) {
        var scenarios = p.scenarios().stream().map(PlanningController::scenario).toList();
        // Grouped in scenario order, so the UI renders capabilities in the order the plan
        // presented them. A legacy plan has no capability on any row, which yields an empty
        // list and makes the UI fall back to the flat table.
        var capabilities = new ArrayList<CapabilityView>();
        for (var s : p.scenarios()) {
            if (s.capability() == null || s.capability().isBlank()) {
                continue;
            }
            var last = capabilities.isEmpty() ? null : capabilities.get(capabilities.size() - 1);
            if (last == null || !last.name().equals(s.capability())) {
                capabilities.add(new CapabilityView(s.capability(), null, new ArrayList<>()));
                last = capabilities.get(capabilities.size() - 1);
            }
            last.testCases().add(scenario(s));
        }
        // The capability description lives in the payload, not on the scenario rows.
        var described = capabilities.stream()
                .map(c -> new CapabilityView(c.name(), capabilityDescription(p, c.name()), c.testCases()))
                .toList();

        return new PlanView(p.testPlanId().toString(), p.generationId().toString(), p.planVersion(),
                p.overview(),
                new ScopeView(p.scope().inScope(), p.scope().outOfScope()),
                p.architectureContext(),
                p.requirements().stream()
                        .map(r -> new RequirementView(r.id(), r.statement(), r.category(), r.sources(),
                                r.evidence(), r.lifecyclePhase()))
                        .toList(),
                p.testDataRequirements().stream()
                        .map(d -> new TestDataRequirementView(d.id(), d.name(), d.description(),
                                d.attributes(), d.sourceOfTruth()))
                        .toList(),
                described, p.executionStrategy(), p.risks(), p.gaps(), p.provenance(), scenarios,
                p.createdAt());
    }

    private static ScenarioView scenario(TestPlan.TestScenario s) {
        return new ScenarioView(s.scenarioKey(), s.seq(), s.capability(), s.title(), s.priority(),
                s.objective(), s.lifecyclePhase(), s.sources(), s.requirementIds(), s.preconditions(),
                s.steps(), s.expectedResults(), s.testDataRequirements(), s.automationMapping(),
                s.source());
    }

    @SuppressWarnings("unchecked")
    private static String capabilityDescription(TestPlan p, String name) {
        if (!(p.payload().get("capabilities") instanceof List<?> caps)) {
            return null;
        }
        for (var c : caps) {
            if (c instanceof Map<?, ?> m && name.equals(m.get("name"))) {
                var d = ((Map<String, Object>) m).get("description");
                return d == null ? null : String.valueOf(d);
            }
        }
        return null;
    }
}
