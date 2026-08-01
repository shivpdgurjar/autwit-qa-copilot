package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.autwit.copilot.orchestrator.OrchestratorClient;
import com.autwit.copilot.orchestrator.OrchestratorException;
import com.autwit.copilot.orchestrator.dto.ArtifactDescriptor;
import com.autwit.copilot.orchestrator.dto.Envelope;
import com.autwit.copilot.orchestrator.dto.InvokeRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The real planning client. The orchestrator ships the five planning skills as ordinary
 * catalog skills (v1.0.30, catalog v1/693ede402294), so this drives them through the standard
 * skill-execute surface ({@link OrchestratorClient#execute}) and reads the result out of the
 * returned {@link Envelope}: the data rides back in a json <b>artifact body</b> (the
 * {@code planning_*} artifacts the orchestrator's executor emits), and {@code fetch_context}'s
 * per-item console log rides in {@code output_inline.log}. Reusing {@code OrchestratorClient}
 * means the auth, wire log, timeout and error handling are the ones the rest of the app
 * already uses — no second HTTP client, no hand-rolled JSON traversal.
 *
 * <p>Planning has no session, so each call carries a synthetic execute envelope (a fresh
 * id, an empty session context). The planning skills read only {@code input}; if the
 * orchestrator ever requires a real session_context field here, the live run will surface it.
 */
@Component
@Profile("!fake")
public class HttpPlanningClient implements PlanningClient {

    private final OrchestratorClient orchestrator;

    public HttpPlanningClient(OrchestratorClient orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public List<Candidate> jiraSearch(String featureKey, String query, String project, Integer maxResults) {
        var input = new LinkedHashMap<String, Object>();
        input.put("query", query);
        input.put("feature_key", featureKey);
        input.put("project", project);
        input.put("max_results", maxResults);
        var body = artifactBody(execute("planning.jira_search", input), "planning_jira_search");
        var out = new ArrayList<Candidate>();
        for (var issue : listOfMaps(body.get("issues"))) {
            out.add(new Candidate(str(issue, "key"), str(issue, "title"), "jira", str(issue, "status"),
                    join(str(issue, "issue_type"), str(issue, "status"), str(issue, "updated_at")),
                    str(issue, "url")));
        }
        return out;
    }

    @Override
    public List<Candidate> confluenceSearch(String space, String query, Integer maxResults) {
        var input = new LinkedHashMap<String, Object>();
        input.put("query", query);
        input.put("space", space);
        input.put("max_results", maxResults);
        var body = artifactBody(execute("planning.confluence_search", input), "planning_confluence_search");
        var out = new ArrayList<Candidate>();
        for (var page : listOfMaps(body.get("pages"))) {
            out.add(new Candidate(str(page, "page_id"), str(page, "title"), "confluence", null,
                    join("Edited by " + str(page, "edited_by"), str(page, "edited_at")), str(page, "url")));
        }
        return out;
    }

    @Override
    public FetchResult fetchContext(List<String> jiraKeys, List<String> confluencePageIds) {
        var input = new LinkedHashMap<String, Object>();
        input.put("jira_keys", jiraKeys == null ? List.of() : jiraKeys);
        input.put("confluence_page_ids", confluencePageIds == null ? List.of() : confluencePageIds);
        var env = execute("planning.fetch_context", input);

        var docs = new ArrayList<FetchedDocument>();
        for (var d : listOfMaps(artifactBody(env, "planning_context").get("documents"))) {
            // text may be empty for a truncated Jira body (KNOWN_ISSUES PLAN-1) — carried through.
            docs.add(new FetchedDocument(str(d, "source_type"), str(d, "external_ref"),
                    str(d, "title"), str(d, "text"), Instant.EPOCH));
        }
        // The fetch console rides in output_inline.log (there is no streaming), per v1.0.27 §4.
        var logLines = new ArrayList<LogLine>();
        for (var l : listOfMaps(outputInline(env).get("log"))) {
            logLines.add(new LogLine(str(l, "ts"), str(l, "level"), str(l, "source"),
                    str(l, "ref"), str(l, "message")));
        }
        return new FetchResult(docs, logLines);
    }

    @Override
    public TestPlanResult generateTestPlan(TestPlanRequest request) {
        var input = new LinkedHashMap<String, Object>();
        input.put("feature_key", request.featureKey());
        input.put("feature_description", request.featureDescription());
        input.put("source_documents", docs(request.sourceDocuments()));
        input.put("existing_test_cases", docs(request.existingTestCases()));
        input.put("previous_response_id", request.previousResponseId());
        var body = artifactBody(execute("planning.generate_test_plan", input), "planning_test_plan");

        var scenarios = new ArrayList<Scenario>();
        for (var s : listOfMaps(body.get("scenarios"))) {
            scenarios.add(new Scenario(str(s, "id"), str(s, "title"), str(s, "priority"), str(s, "source")));
        }
        return new TestPlanResult(str(body, "overview"), str(body, "scope"), scenarios,
                obj(body.get("provenance")), str(body, "response_id"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public TestDataResult generateTestData(TestDataRequest request) {
        var scenarios = new ArrayList<Map<String, Object>>();
        for (var s : request.scenarios()) {
            scenarios.add(Map.of("id", s.id(), "title", s.title()));
        }
        var input = new LinkedHashMap<String, Object>();
        input.put("scenarios", scenarios);
        input.put("edge_cases", request.edgeCases());
        input.put("rows_per_scenario", request.rowsPerScenario());
        input.put("example_record", request.exampleRecord());
        input.put("previous_response_id", request.previousResponseId());
        var body = artifactBody(execute("planning.generate_test_data", input), "planning_test_data");

        var datasets = new ArrayList<Dataset>();
        for (var d : listOfMaps(body.get("datasets"))) {
            var columns = new ArrayList<String>();
            if (d.get("columns") instanceof List<?> cols) {
                for (var c : cols) {
                    columns.add(String.valueOf(c));
                }
            }
            var rows = new ArrayList<Map<String, Object>>();
            for (var r : listOfMaps(d.get("rows"))) {
                rows.add((Map<String, Object>) r);
            }
            datasets.add(new Dataset(str(d, "scenario_id"), columns, rows));
        }
        return new TestDataResult(datasets, str(body, "response_id"));
    }

    @Override
    public AnalyzeResult analyzeDocuments(AnalyzeRequest request) {
        var resolutions = new ArrayList<Map<String, Object>>();
        for (var r : request.resolutions() == null ? List.<ResolutionRef>of() : request.resolutions()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("point", r.point());
            m.put("kind", r.kind());
            m.put("answer", r.answer());
            resolutions.add(m);
        }
        var input = new LinkedHashMap<String, Object>();
        input.put("feature_key", request.featureKey());
        input.put("feature_description", request.featureDescription());
        input.put("source_documents", docs(request.sourceDocuments()));
        input.put("resolutions", resolutions);
        input.put("previous_response_id", request.previousResponseId());
        var body = artifactBody(execute("planning.analyze_documents", input), "planning_document_analysis");

        return new AnalyzeResult(findings(body.get("conflicts")), findings(body.get("clarifications")),
                str(body, "response_id"));
    }

    private static List<Finding> findings(Object raw) {
        var out = new ArrayList<Finding>();
        for (var f : listOfMaps(raw)) {
            var sources = new ArrayList<Source>();
            for (var s : listOfMaps(f.get("sources"))) {
                sources.add(new Source(str(s, "doc_title"), str(s, "quote")));
            }
            var options = new ArrayList<String>();
            if (f.get("options") instanceof List<?> opts) {
                for (var o : opts) {
                    options.add(String.valueOf(o));
                }
            }
            out.add(new Finding(str(f, "title"), str(f, "detail"), sources, options));
        }
        return out;
    }

    // ---- transport -------------------------------------------------------------------

    /**
     * Runs one planning skill through the standard execute surface with a synthetic,
     * session-less envelope, and returns the result envelope (failed → thrown).
     */
    private Envelope execute(String skillName, Map<String, Object> input) {
        input.values().removeIf(Objects::isNull); // omit unset optionals (project, max_results, …)
        var id = UUID.randomUUID().toString();
        var ctx = new InvokeRequest.SessionContext(null, null, Map.of(), List.of(), null, Map.of(), List.of());
        var env = orchestrator.execute(skillName, new InvokeRequest.Execute("planning-" + id, id, id, input, ctx));
        if (env.isFailed()) {
            throw new OrchestratorException.Failed(
                    "planning skill %s failed: %s".formatted(skillName,
                            env.error() != null ? env.error().detail() : "unknown"),
                    env.error(), null);
        }
        return env;
    }

    /** The body of the first artifact of the given type, as a map (data rides in the artifact). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> artifactBody(Envelope env, String artifactType) {
        return env.artifactsOrEmpty().stream()
                .filter(a -> artifactType.equals(a.artifactType()))
                .map(ArtifactDescriptor::body)
                .filter(b -> b instanceof Map)
                .map(b -> (Map<String, Object>) b)
                .findFirst()
                .orElse(Map.of());
    }

    private static Map<String, Object> outputInline(Envelope env) {
        return env.invocationsOrEmpty().stream()
                .map(Envelope.Invocation::outputInline)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(Map.of());
    }

    private static List<Map<String, Object>> docs(List<Doc> docs) {
        var out = new ArrayList<Map<String, Object>>();
        for (var d : docs == null ? List.<Doc>of() : docs) {
            var m = new LinkedHashMap<String, Object>();
            m.put("source_type", d.sourceType());
            m.put("title", d.title());
            m.put("text", d.text());
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object o) {
        if (!(o instanceof List<?> l)) {
            return List.of();
        }
        var out = new ArrayList<Map<String, Object>>();
        for (var e : l) {
            if (e instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Map<String, Object> m, String key) {
        var v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String join(String... parts) {
        var kept = new ArrayList<String>();
        for (var p : parts) {
            if (p != null && !p.isBlank() && !p.equals("null")) {
                kept.add(p);
            }
        }
        return String.join(" · ", kept);
    }
}
