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
            String previousResponseId) {
    }

    /** A source document flattened to what the generator reads. */
    record Doc(String sourceType, String title, String text) {
    }

    record TestPlanResult(
            String overview,
            String scope,
            List<Scenario> scenarios,
            Map<String, Object> provenance,
            String responseId) {
    }

    record Scenario(String id, String title, String priority, String source) {
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
}
