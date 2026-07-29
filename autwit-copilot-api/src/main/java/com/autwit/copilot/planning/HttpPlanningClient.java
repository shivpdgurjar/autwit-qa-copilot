package com.autwit.copilot.planning;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.autwit.copilot.config.AutwitProperties;
import com.autwit.copilot.orchestrator.OrchestratorException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The real planning client, calling the orchestrator's planning surface.
 *
 * <p><b>Proposed contract — pending orchestrator confirmation (our v1.0.26, their reply will
 * be v1.0.27).</b> Built against the shapes in message-from-qa-copilot/v1.0.26 §4 so the wiring
 * is ready the moment they land; the routes and field names below are reconciled when they
 * confirm. This mirrors {@code HttpFinancialAnalysisClient}: a dedicated non-session HTTP
 * surface (planning has no session), snake_case I/O, a null-retaining mapper, and one PII-safe
 * wire line per call.
 */
@Component
@Profile("!fake")
public class HttpPlanningClient implements PlanningClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPlanningClient.class);
    private static final Logger wire = LoggerFactory.getLogger("com.autwit.copilot.planning.wire");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final RestClient client;
    private final JsonMapper mapper;
    private final AutwitProperties props;

    public HttpPlanningClient(AutwitProperties props) {
        this.props = props;
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Retain nulls on the way out — a null field in evidence is a distinct claim
                // from an absent one, the same lesson as ContentHasher / the financial client.
                .serializationInclusion(JsonInclude.Include.ALWAYS)
                .build();

        var orchestrator = props.orchestrator();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) orchestrator.timeout().toMillis());

        this.client = RestClient.builder()
                .baseUrl(orchestrator.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + orchestrator.token())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("HttpPlanningClient targeting {} with a {} deadline (proposed contract, pending v1.0.27)",
                orchestrator.baseUrl(), orchestrator.timeout());
    }

    @Override
    public List<Candidate> jiraSearch(String featureKey, String query, String project, Integer maxResults) {
        var body = new LinkedHashMap<String, Object>();
        body.put("feature_key", featureKey);
        body.put("query", query);
        body.put("project", project);
        body.put("max_results", maxResults);
        var res = post("/v1/planning/jira-search", body);
        var out = new ArrayList<Candidate>();
        for (var issue : list(res.get("issues"))) {
            out.add(new Candidate(str(issue, "key"), str(issue, "title"), "jira",
                    str(issue, "status"),
                    join(str(issue, "issue_type"), str(issue, "status"), str(issue, "updated_at")),
                    str(issue, "url")));
        }
        return out;
    }

    @Override
    public List<Candidate> confluenceSearch(String space, String query, Integer maxResults) {
        var body = new LinkedHashMap<String, Object>();
        body.put("space", space);
        body.put("query", query);
        body.put("max_results", maxResults);
        var res = post("/v1/planning/confluence-search", body);
        var out = new ArrayList<Candidate>();
        for (var page : list(res.get("pages"))) {
            out.add(new Candidate(str(page, "page_id"), str(page, "title"), "confluence", null,
                    join("Edited by " + str(page, "edited_by"), str(page, "edited_at")),
                    str(page, "url")));
        }
        return out;
    }

    @Override
    public FetchResult fetchContext(List<String> jiraKeys, List<String> confluencePageIds) {
        var body = new LinkedHashMap<String, Object>();
        body.put("jira_keys", jiraKeys);
        body.put("confluence_page_ids", confluencePageIds);
        var res = post("/v1/planning/fetch-context", body);
        var docs = new ArrayList<FetchedDocument>();
        for (var d : list(res.get("documents"))) {
            docs.add(new FetchedDocument(str(d, "source_type"), str(d, "external_ref"),
                    str(d, "title"), str(d, "text"), Instant.EPOCH));
        }
        var logLines = new ArrayList<LogLine>();
        for (var l : list(res.get("log"))) {
            logLines.add(new LogLine(str(l, "ts"), str(l, "level"), str(l, "source"),
                    str(l, "ref"), str(l, "message")));
        }
        return new FetchResult(docs, logLines);
    }

    @Override
    public TestPlanResult generateTestPlan(TestPlanRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("feature_key", request.featureKey());
        body.put("feature_description", request.featureDescription());
        body.put("source_documents", docs(request.sourceDocuments()));
        body.put("existing_test_cases", docs(request.existingTestCases()));
        body.put("previous_response_id", request.previousResponseId());
        var res = post("/v1/planning/test-plan", body);
        var scenarios = new ArrayList<Scenario>();
        for (var s : list(res.get("scenarios"))) {
            scenarios.add(new Scenario(str(s, "id"), str(s, "title"), str(s, "priority"), str(s, "source")));
        }
        return new TestPlanResult(str(res, "overview"), str(res, "scope"), scenarios,
                obj(res.get("provenance")), str(res, "response_id"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public TestDataResult generateTestData(TestDataRequest request) {
        var body = new LinkedHashMap<String, Object>();
        var scenarios = new ArrayList<Map<String, Object>>();
        for (var s : request.scenarios()) {
            scenarios.add(Map.of("id", s.id(), "title", s.title()));
        }
        body.put("scenarios", scenarios);
        body.put("example_record", request.exampleRecord());
        body.put("edge_cases", request.edgeCases());
        body.put("rows_per_scenario", request.rowsPerScenario());
        body.put("previous_response_id", request.previousResponseId());
        var res = post("/v1/planning/test-data", body);
        var datasets = new ArrayList<Dataset>();
        for (var d : list(res.get("datasets"))) {
            var columns = new ArrayList<String>();
            if (d.get("columns") instanceof List<?> cols) {
                for (var c : cols) {
                    columns.add(String.valueOf(c));
                }
            }
            var rows = new ArrayList<Map<String, Object>>();
            for (var r : list(d.get("rows"))) {
                rows.add((Map<String, Object>) r);
            }
            datasets.add(new Dataset(str(d, "scenario_id"), columns, rows));
        }
        return new TestDataResult(datasets, str(res, "response_id"));
    }

    // ---- transport -------------------------------------------------------------------

    private Map<String, Object> post(String path, Map<String, Object> body) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialise the planning request: " + e.getMessage(), e);
        }
        // Metadata only — never the corpus body (requirement docs can carry sensitive content).
        wire.info("--> POST {}{} keys={}", props.orchestrator().baseUrl(), path, body.keySet());
        try {
            var out = client.post()
                    .uri(path)
                    .body(json)
                    .exchange((req, response) -> {
                        var raw = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (response.getStatusCode().isError()) {
                            wire.warn("<-- {} FAILED: HTTP {} {}", path, response.getStatusCode().value(), truncate(raw));
                            throw new OrchestratorException.Failed(
                                    "Planning call %s returned %s: %s"
                                            .formatted(path, response.getStatusCode().value(), truncate(raw)),
                                    null, null);
                        }
                        return mapper.readValue(raw, MAP);
                    });
            wire.info("<-- 200 {}", path);
            return out == null ? Map.of() : out;
        } catch (OrchestratorException e) {
            throw e;
        } catch (ResourceAccessException e) {
            wire.warn("<-- {} TIMEOUT after {}", path, props.orchestrator().timeout());
            throw new OrchestratorException.Failed(
                    "Planning call did not respond within %s".formatted(props.orchestrator().timeout()), null, e);
        } catch (Exception e) {
            wire.warn("<-- {} ERROR: {}", path, e.getMessage());
            throw new OrchestratorException.Failed("Planning call failed: " + e.getMessage(), null, e);
        }
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
    private static List<Map<String, Object>> list(Object o) {
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

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
