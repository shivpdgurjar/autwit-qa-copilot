package com.autwit.copilot.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.ArtifactFormat;
import com.autwit.copilot.artifact.ContentHasher;
import com.autwit.copilot.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Planning Copilot's application service — the entry point the wizard's controller calls.
 * Owns the four steps: add inputs, fetch context, generate the plan, generate the data.
 *
 * <p>Search and fetch are synchronous (fast MCP calls); generation is a ~60s LLM round trip so
 * it goes async through the {@code generation} job queue ({@link PlanningGenerationWorker}) —
 * the same sync-fast / async-slow split as the rest of the app.
 */
@Service
public class PlanningService {

    private final PlanningRepository repo;
    private final PlanningClient client;
    private final TextExtractor extractor;
    private final ContentHasher hasher;

    public PlanningService(PlanningRepository repo, PlanningClient client, TextExtractor extractor,
            ContentHasher hasher) {
        this.repo = repo;
        this.client = client;
        this.extractor = extractor;
        this.hasher = hasher;
    }

    // ---- step 1: project + inputs ----------------------------------------------------

    // ---- sessions (resumable, history-bearing context) -------------------------------

    public record SessionWithProject(PlanningSession session, PlanningProject project) {
    }

    public record SessionDetail(PlanningSession session, List<PlanningProject> projects,
            List<PlanningActivity> activity) {
    }

    /** Create a planning session and its first project — the "New session" entry point. */
    @Transactional
    public SessionWithProject createSession(String testerId, String env, String title,
            String featureKey, String featureDescription, String domain) {
        var session = repo.createSession(blankToNull(testerId), blankToNull(env), blankToNull(title));
        repo.addActivity(session.sessionId(), "session_created", null,
                "Planning session created" + (title != null && !title.isBlank() ? ": " + title.trim() : ""));
        var project = repo.createProject(session.sessionId(), blankToNull(featureKey),
                blankToNull(featureDescription), blankToNull(domain), blankToNull(title),
                blankToNull(testerId), blankToNull(env));
        repo.addActivity(session.sessionId(), "project_added", blankToNull(featureKey),
                "Started " + (featureKey != null && !featureKey.isBlank() ? featureKey.trim() : "a feature"));
        return new SessionWithProject(session, project);
    }

    public List<PlanningSession> listRecentSessions(String testerId, int limit) {
        return repo.listRecentSessions(blankToNull(testerId), limit);
    }

    public PlanningSession requireSession(UUID sessionId) {
        return repo.findSession(sessionId)
                .orElseThrow(() -> new ApiException.NotFound("planning_session", sessionId));
    }

    /** A session with its project(s) and history timeline — what the wizard resumes against. */
    public SessionDetail getSession(UUID sessionId) {
        var session = requireSession(sessionId);
        return new SessionDetail(session, repo.listProjectsBySession(sessionId), repo.listActivity(sessionId));
    }

    // ---- projects --------------------------------------------------------------------

    /** Back-compat: create a project, auto-creating a session to hold it. */
    @Transactional
    public PlanningProject createProject(String featureKey, String featureDescription, String title,
            String createdBy, String env, String domain) {
        return createSession(createdBy, env, title, featureKey, featureDescription, domain).project();
    }

    public PlanningProject requireProject(UUID projectId) {
        return repo.findProject(projectId).orElseThrow(() -> new ApiException.NotFound("planning_project", projectId));
    }

    public List<PlanningProject> listProjects(int limit) {
        return repo.listProjects(limit);
    }

    /**
     * Adds an uploaded or pasted document to the corpus. The UI reads text files client-side
     * and posts their content, so pass 1 needs no multipart or binary parsing (deferred to
     * pass 2 in {@link TextExtractor}).
     */
    @Transactional
    public SourceDocument addTextDocument(UUID projectId, SourceType sourceType, String docRole,
            String title, String filename, String mime, String rawText) {
        var project = requireProject(projectId);
        if (sourceType != SourceType.UPLOAD && sourceType != SourceType.PASTE) {
            throw new ApiException.BadRequest("invalid_source_type",
                    "Only 'upload' or 'paste' documents can be added directly; jira/confluence come via fetch.");
        }
        var text = extractor.extract(filename, mime, rawText);
        var hash = hasher.hash(ArtifactFormat.TEXT, text);
        var docTitle = title != null && !title.isBlank() ? title.trim()
                : filename != null && !filename.isBlank() ? filename.trim() : "Pasted text";
        // Uploads dedupe on filename; a paste has no external ref so each is distinct.
        var externalRef = sourceType == SourceType.UPLOAD ? filename : null;
        var doc = repo.upsertDocument(projectId, sourceType, DocRole.fromWire(docRole), externalRef,
                docTitle, mime, text, hash);
        repo.addActivity(project.sessionId(), "document_added", externalRef, "Added " + docTitle);
        return doc;
    }

    /**
     * Adds an uploaded document from its raw bytes — parsed server-side by Tika (PDF/DOCX/XLSX
     * and text alike). This is the file-upload path; {@link #addTextDocument} stays for paste.
     * Uploads dedupe on filename within the project (re-uploading refreshes in place).
     */
    @Transactional
    public SourceDocument addUploadedFile(UUID projectId, String filename, String mime, byte[] bytes,
            String title, String docRole) {
        var project = requireProject(projectId);
        var text = extractor.extractFile(filename, mime, bytes);
        var hash = hasher.hash(ArtifactFormat.TEXT, text);
        var docTitle = title != null && !title.isBlank() ? title.trim()
                : filename != null && !filename.isBlank() ? filename.trim() : "Uploaded document";
        var doc = repo.upsertDocument(projectId, SourceType.UPLOAD, DocRole.fromWire(docRole), filename,
                docTitle, mime, text, hash);
        repo.addActivity(project.sessionId(), "document_added", filename, "Uploaded " + docTitle);
        return doc;
    }

    public List<SourceDocument> listDocuments(UUID projectId) {
        requireProject(projectId);
        return repo.listDocuments(projectId);
    }

    public void setSelected(UUID projectId, UUID documentId, boolean selected) {
        requireDocumentInProject(projectId, documentId);
        repo.setSelected(documentId, selected);
    }

    /**
     * Re-tags a document after it was added. An unrecognised role falls back to `requirement`
     * rather than 400ing — the role is an advisory hint to the generator, and a stricter
     * contract would break the UI the next time a role is introduced.
     */
    public void setDocRole(UUID projectId, UUID documentId, String docRole) {
        requireDocumentInProject(projectId, documentId);
        repo.setDocRole(documentId, DocRole.fromWire(docRole));
    }

    public void deleteDocument(UUID projectId, UUID documentId) {
        requireDocumentInProject(projectId, documentId);
        repo.deleteDocument(documentId);
    }

    // ---- step 2: fetch context (Jira / Confluence over MCP) ---------------------------

    public List<PlanningClient.Candidate> searchJira(UUID projectId, String query, String project,
            Integer maxResults) {
        var p = requireProject(projectId);
        return client.jiraSearch(p.featureKey(), effectiveQuery(p, query), project, maxResults);
    }

    public List<PlanningClient.Candidate> searchConfluence(UUID projectId, String space, String query,
            Integer maxResults) {
        var p = requireProject(projectId);
        return client.confluenceSearch(space, effectiveQuery(p, query), maxResults);
    }

    /** The tester's query, or the project's feature description as a fallback, or empty. */
    private static String effectiveQuery(PlanningProject p, String query) {
        if (query != null && !query.isBlank()) {
            return query;
        }
        return p.featureDescription() != null ? p.featureDescription() : "";
    }

    /**
     * Pulls the selected Jira/Confluence items over MCP and persists each as a source document,
     * returning the console log so the UI can render Step 2's fetch activity.
     *
     * <p>{@code refs} carries whatever the tester typed or pasted — a bare key, a bare page id,
     * or a link — and is normalised into the two lists alongside anything picked from search.
     * An entry nothing can be made of is reported as its own console line and the rest still
     * fetch: one bad paste should not cost the whole batch, which is the same tolerance the
     * fetch already applies to a truncated Jira body.
     */
    @Transactional
    public FetchOutcome fetchContext(UUID projectId, List<String> jiraKeys,
            List<String> confluencePageIds, List<String> refs) {
        var project = requireProject(projectId);

        var parsed = ContextRefParser.parse(refs);
        var allJira = merge(jiraKeys, parsed.jiraKeys());
        var allPages = merge(confluencePageIds, parsed.confluencePageIds());

        if (allJira.isEmpty() && allPages.isEmpty()) {
            // Everything the caller offered was unusable; say so rather than round-tripping
            // to MCP with two empty lists and returning a confusingly empty success.
            if (!parsed.rejected().isEmpty()) {
                return new FetchOutcome(List.of(), rejectionLog(parsed.rejected()));
            }
            throw new ApiException.BadRequest("no_refs",
                    "Select or enter at least one Jira ticket or Confluence page.");
        }

        var result = client.fetchContext(allJira, allPages);
        var persisted = result.documents().stream().map(d -> {
            // Fetched text is already extracted; normalise without the upload guards so an
            // empty body (truncated Jira, PLAN-1) still lands rather than failing the fetch.
            var text = extractor.normalize(d.text());
            var hash = hasher.hash(ArtifactFormat.TEXT, text);
            // Fetched items default to `requirement`; the tester re-tags in the UI if a
            // fetched page is actually architecture or existing tests.
            return repo.upsertDocument(projectId, SourceType.fromWire(d.sourceType()),
                    DocRole.REQUIREMENT, d.externalRef(), d.title(), null, text, hash);
        }).toList();
        repo.addActivity(project.sessionId(), "context_fetched", null,
                "Fetched " + persisted.size() + " document(s) from Jira/Confluence");

        // Rejections lead: the tester needs to see what was dropped before reading what landed.
        var log = new ArrayList<>(rejectionLog(parsed.rejected()));
        log.addAll(result.log());
        return new FetchOutcome(persisted, log);
    }

    /** Union preserving order, first occurrence wins — a ref both searched and pasted is one item. */
    private static List<String> merge(List<String> selected, List<String> entered) {
        var out = new ArrayList<String>();
        for (var list : List.of(selected == null ? List.<String>of() : selected, entered)) {
            for (var v : list) {
                if (v != null && !v.isBlank() && !out.contains(v.trim())) {
                    out.add(v.trim());
                }
            }
        }
        return out;
    }

    private static List<PlanningClient.LogLine> rejectionLog(List<ContextRefParser.Rejected> rejected) {
        return rejected.stream()
                .map(r -> new PlanningClient.LogLine(null, "warn", "input", r.input(),
                        "Skipped \"" + r.input() + "\" — " + r.reason()))
                .toList();
    }

    public record FetchOutcome(List<SourceDocument> documents, List<PlanningClient.LogLine> log) {
    }

    // ---- step 3 & 4: generation ------------------------------------------------------

    /** Enqueue a test-plan generation over the project's selected corpus. */
    @Transactional
    public Generation generateTestPlan(UUID projectId) {
        requireProject(projectId);
        if (repo.listSelectedDocuments(projectId).isEmpty()) {
            throw new ApiException.BadRequest("no_sources",
                    "Select at least one document before generating a test plan.");
        }
        // Reasoning gate (opt-in): if a reasoning thread was started and still has open findings,
        // block generation until they are resolved or the tester records a "proceed anyway"
        // override. A project that never ran reasoning has no thread and generates as before.
        repo.findReasoningByProject(projectId).ifPresent(r -> {
            if (r.isOpen()) {
                throw new ApiException.Conflict("reasoning_incomplete",
                        "Resolve the open document conflicts and clarifications, or choose "
                                + "'proceed anyway', before generating a test plan.");
            }
        });
        return repo.createGeneration(projectId, GenerationType.TEST_PLAN, Map.of());
    }

    // ---- reasoning (pre-generation conflict/clarification loop) ------------------------

    public record ReasoningDetail(PlanningReasoning reasoning, PlanningAnalysis latest,
            List<Resolution> resolutions) {
    }

    /**
     * Enqueue a reasoning round over the project's selected corpus. The first call starts the
     * thread (round 1); each subsequent call re-analyzes (round + 1), feeding back every
     * resolution the tester has recorded so the model does not re-raise a settled point.
     */
    @Transactional
    public Generation analyzeDocuments(UUID projectId) {
        requireProject(projectId);
        if (repo.listSelectedDocuments(projectId).isEmpty()) {
            throw new ApiException.BadRequest("no_sources",
                    "Select at least one document before analyzing.");
        }
        var reasoning = repo.startReasoningRound(projectId);
        var config = new java.util.LinkedHashMap<String, Object>();
        config.put("reasoning_id", reasoning.reasoningId().toString());
        config.put("round", reasoning.round());
        return repo.createGeneration(projectId, GenerationType.DOCUMENT_ANALYSIS, config);
    }

    /** The reasoning thread with its latest round + accumulated resolutions, or empty if none. */
    public java.util.Optional<ReasoningDetail> getReasoning(UUID projectId) {
        requireProject(projectId);
        return repo.findReasoningByProject(projectId).map(r -> new ReasoningDetail(
                r, repo.findLatestAnalysis(r.reasoningId()).orElse(null),
                repo.listResolutions(r.reasoningId())));
    }

    /** Record a tester's answer to a finding (a conflict confirmation or a clarification). */
    @Transactional
    public void addResolution(UUID projectId, UUID findingId, String kind, String prompt, String answer) {
        requireProject(projectId);
        var reasoning = repo.findReasoningByProject(projectId)
                .orElseThrow(() -> new ApiException.NotFound("planning_reasoning", projectId));
        if (prompt == null || prompt.isBlank() || answer == null || answer.isBlank()) {
            throw new ApiException.BadRequest("invalid_resolution",
                    "A resolution needs both the point it answers and an answer.");
        }
        var normalizedKind = "conflict".equals(kind) ? "conflict" : "clarification";
        repo.addResolution(reasoning.reasoningId(), reasoning.round(), findingId, normalizedKind,
                prompt.trim(), answer.trim());
    }

    /** Record the explicit "proceed anyway" override, unlocking generation despite open findings. */
    @Transactional
    public void overrideReasoning(UUID projectId, String reason, String by) {
        requireProject(projectId);
        var reasoning = repo.findReasoningByProject(projectId)
                .orElseThrow(() -> new ApiException.NotFound("planning_reasoning", projectId));
        repo.overrideReasoning(reasoning.reasoningId(), blankToNull(reason), blankToNull(by));
    }

    /**
     * The rows-per-scenario default, applied once here (the domain authority). The controller
     * passes the request value through unchanged and the runner trusts the persisted config, so
     * this is the single source of the policy.
     */
    public static final int DEFAULT_ROWS_PER_SCENARIO = 8;

    /** Enqueue a test-data generation for the chosen scenarios. */
    @Transactional
    public Generation generateTestData(UUID projectId, List<Map<String, Object>> scenarios,
            List<String> edgeCases, Integer rowsPerScenario, Map<String, Object> exampleRecord) {
        requireProject(projectId);
        if (scenarios == null || scenarios.isEmpty()) {
            throw new ApiException.BadRequest("no_scenarios",
                    "Select at least one scenario to generate data for.");
        }
        var config = new java.util.LinkedHashMap<String, Object>();
        config.put("scenarios", scenarios);
        config.put("edge_cases", edgeCases == null ? List.of() : edgeCases);
        config.put("rows_per_scenario",
                rowsPerScenario == null || rowsPerScenario <= 0 ? DEFAULT_ROWS_PER_SCENARIO : rowsPerScenario);
        if (exampleRecord != null) {
            config.put("example_record", exampleRecord);
        }
        return repo.createGeneration(projectId, GenerationType.TEST_DATA, config);
    }

    public Generation requireGeneration(UUID projectId, UUID generationId) {
        var gen = repo.findGeneration(generationId)
                .orElseThrow(() -> new ApiException.NotFound("generation", generationId));
        if (!gen.projectId().equals(projectId)) {
            throw new ApiException.NotFound("generation", generationId);
        }
        return gen;
    }

    public List<Generation> listGenerations(UUID projectId) {
        requireProject(projectId);
        return repo.listGenerations(projectId);
    }

    public java.util.Optional<TestPlan> latestPlan(UUID projectId) {
        requireProject(projectId);
        return repo.findLatestPlan(projectId);
    }

    public java.util.Optional<TestPlan> planByGeneration(UUID projectId, UUID generationId) {
        requireGeneration(projectId, generationId);
        return repo.findPlanByGeneration(generationId);
    }

    public List<TestDataset> datasets(UUID projectId, UUID generationId) {
        requireGeneration(projectId, generationId);
        return repo.listDatasetsByGeneration(generationId);
    }

    // ---- helpers ---------------------------------------------------------------------

    private void requireDocumentInProject(UUID projectId, UUID documentId) {
        var doc = repo.findDocument(documentId)
                .orElseThrow(() -> new ApiException.NotFound("source_document", documentId));
        if (!doc.projectId().equals(projectId)) {
            throw new ApiException.NotFound("source_document", documentId);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
