package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Calls the orchestrator's planning surface — the two MCP connectors (Jira/Confluence
 * search + fetch) and the two generation skills (test plan, test data) proposed in
 * message-from-qa-copilot/v1.0.26 §4.
 *
 * <p>An interface so the {@code fake} profile can drive the whole 4-step wizard without a
 * live orchestrator, an MCP server, or an OpenAI key — the same split as
 * {@link com.autwit.copilot.analysis.FinancialAnalysisClient}. copilot-api never speaks MCP
 * itself; it calls these and persists what comes back.
 */
public interface PlanningClient {

    /** Step 2: candidate Jira issues for the checklist (metadata only, fast). */
    List<Candidate> jiraSearch(String featureKey, String query, String project, Integer maxResults);

    /** Step 2: candidate Confluence pages for the checklist. */
    List<Candidate> confluenceSearch(String space, String query, Integer maxResults);

    /** Step 2: pull full bodies for the selected items, with a per-item log for the console. */
    FetchResult fetchContext(List<String> jiraKeys, List<String> confluencePageIds);

    /** Step 3: generate the test plan from the selected corpus. */
    TestPlanResult generateTestPlan(TestPlanRequest request);

    /** Step 4: generate per-scenario test data. */
    TestDataResult generateTestData(TestDataRequest request);

    /** Reasoning: analyze the selected corpus for conflicts + clarifications before generating. */
    AnalyzeResult analyzeDocuments(AnalyzeRequest request);

    // ---- search ----------------------------------------------------------------------

    /**
     * A candidate issue or page. {@code ref} is the Jira key or Confluence page id;
     * {@code meta} is the display line ("Epic · In Progress · 2d ago").
     */
    record Candidate(String ref, String title, String kind, String status, String meta, String url) {
    }

    // ---- fetch -----------------------------------------------------------------------

    record FetchResult(List<FetchedDocument> documents, List<LogLine> log) {
    }

    record FetchedDocument(String sourceType, String externalRef, String title, String text, Instant fetchedAt) {
    }

    /** One line of the Step-2 fetch console. {@code level} is ok|pending|info. */
    record LogLine(String ts, String level, String source, String ref, String message) {
    }

    // ---- generate test plan ----------------------------------------------------------

    record TestPlanRequest(
            String featureKey,
            String featureDescription,
            List<Doc> sourceDocuments,
            List<Doc> existingTestCases,
            /** Selects an orchestrator-owned domain-context block (e.g. "oes"). Null = none. */
            String domain,
            String previousResponseId) {
    }

    /**
     * A source document flattened to what the generator reads. {@code role} tells the model
     * how to weight it — see {@link DocRole}; null is read as a requirement.
     */
    record Doc(String sourceType, String role, String title, String text) {
    }

    /**
     * The v2 plan. {@code payload} is the whole artifact body kept verbatim, so a field the
     * mapper below does not yet read is still persisted rather than silently dropped — which
     * is exactly what the v1 client did to everything outside its five keys.
     */
    record TestPlanResult(
            String overview,
            TestPlan.Scope scope,
            Map<String, Object> architectureContext,
            List<TestPlan.Requirement> requirements,
            List<TestPlan.TestDataRequirement> testDataRequirements,
            List<Capability> capabilities,
            String executionStrategy,
            List<Map<String, Object>> risks,
            List<Map<String, Object>> gaps,
            Map<String, Object> provenance,
            Map<String, Object> payload,
            String responseId) {
    }

    /** A business-capability group of test cases — how the plan is organised and rendered. */
    record Capability(String name, String description, List<Scenario> testCases) {
    }

    record Scenario(
            String id,
            String title,
            String priority,
            String objective,
            String lifecyclePhase,
            List<String> sources,
            List<String> requirementIds,
            List<String> preconditions,
            List<String> steps,
            List<String> expectedResults,
            List<String> testDataRequirements,
            Map<String, Object> automationMapping) {
    }

    // ---- generate test data ----------------------------------------------------------

    record TestDataRequest(
            List<ScenarioRef> scenarios,
            Map<String, Object> exampleRecord,
            List<String> edgeCases,
            int rowsPerScenario,
            String previousResponseId) {
    }

    record ScenarioRef(String id, String title) {
    }

    record TestDataResult(List<Dataset> datasets, String responseId) {
    }

    record Dataset(String scenarioKey, List<String> columns, List<Map<String, Object>> rows) {
    }

    // ---- analyze documents (reasoning) -----------------------------------------------

    record AnalyzeRequest(
            String featureKey,
            String featureDescription,
            List<Doc> sourceDocuments,
            List<ResolutionRef> resolutions,
            String previousResponseId) {
    }

    /** A tester answer carried forward so the model does not re-raise a settled point. */
    record ResolutionRef(String point, String kind, String answer) {
    }

    record AnalyzeResult(List<Finding> conflicts, List<Finding> clarifications, String responseId) {
    }

    /** One conflict or clarification. {@code options} is empty for a clarification. */
    record Finding(String title, String detail, List<Source> sources, List<String> options) {
    }

    record Source(String docTitle, String quote) {
    }
}
