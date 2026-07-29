package com.autwit.copilot.planning;

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

    public PlanningProject createProject(String featureKey, String featureDescription, String title,
            String createdBy, String env) {
        return repo.createProject(blankToNull(featureKey), blankToNull(featureDescription),
                blankToNull(title), blankToNull(createdBy), blankToNull(env));
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
    public SourceDocument addTextDocument(UUID projectId, SourceType sourceType, String title,
            String filename, String mime, String rawText) {
        requireProject(projectId);
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
        return repo.upsertDocument(projectId, sourceType, externalRef, docTitle, mime, text, hash);
    }

    public List<SourceDocument> listDocuments(UUID projectId) {
        requireProject(projectId);
        return repo.listDocuments(projectId);
    }

    public void setSelected(UUID projectId, UUID documentId, boolean selected) {
        requireDocumentInProject(projectId, documentId);
        repo.setSelected(documentId, selected);
    }

    public void deleteDocument(UUID projectId, UUID documentId) {
        requireDocumentInProject(projectId, documentId);
        repo.deleteDocument(documentId);
    }

    // ---- step 2: fetch context (Jira / Confluence over MCP) ---------------------------

    public List<PlanningClient.Candidate> searchJira(UUID projectId, String query, String project,
            Integer maxResults) {
        var p = requireProject(projectId);
        var effectiveQuery = query != null && !query.isBlank() ? query
                : p.featureDescription() != null ? p.featureDescription() : "";
        return client.jiraSearch(p.featureKey(), effectiveQuery, project, maxResults);
    }

    public List<PlanningClient.Candidate> searchConfluence(UUID projectId, String space, String query,
            Integer maxResults) {
        var p = requireProject(projectId);
        var effectiveQuery = query != null && !query.isBlank() ? query
                : p.featureDescription() != null ? p.featureDescription() : "";
        return client.confluenceSearch(space, effectiveQuery, maxResults);
    }

    /**
     * Pulls the selected Jira/Confluence items over MCP and persists each as a source document,
     * returning the console log so the UI can render Step 2's fetch activity.
     */
    @Transactional
    public FetchOutcome fetchContext(UUID projectId, List<String> jiraKeys, List<String> confluencePageIds) {
        requireProject(projectId);
        var result = client.fetchContext(jiraKeys, confluencePageIds);
        var persisted = result.documents().stream().map(d -> {
            var text = extractor.extract(d.title(), null, d.text());
            var hash = hasher.hash(ArtifactFormat.TEXT, text);
            return repo.upsertDocument(projectId, SourceType.fromWire(d.sourceType()),
                    d.externalRef(), d.title(), null, text, hash);
        }).toList();
        return new FetchOutcome(persisted, result.log());
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
        return repo.createGeneration(projectId, GenerationType.TEST_PLAN, Map.of());
    }

    /** Enqueue a test-data generation for the chosen scenarios. */
    @Transactional
    public Generation generateTestData(UUID projectId, List<Map<String, Object>> scenarios,
            List<String> edgeCases, int rowsPerScenario, Map<String, Object> exampleRecord) {
        requireProject(projectId);
        if (scenarios == null || scenarios.isEmpty()) {
            throw new ApiException.BadRequest("no_scenarios",
                    "Select at least one scenario to generate data for.");
        }
        var config = new java.util.LinkedHashMap<String, Object>();
        config.put("scenarios", scenarios);
        config.put("edge_cases", edgeCases == null ? List.of() : edgeCases);
        config.put("rows_per_scenario", rowsPerScenario <= 0 ? 8 : rowsPerScenario);
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
