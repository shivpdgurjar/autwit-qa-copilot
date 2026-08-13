package com.autwit.copilot.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.automation.AutomationRunClient;
import com.autwit.copilot.automation.AutomationUnavailableException;
import com.autwit.copilot.orchestrator.OrchestratorClient;
import com.autwit.copilot.orchestrator.dto.Envelope;
import com.autwit.copilot.orchestrator.dto.InvokeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The automation plane's API — a proxy in front of the AUTWIT run service.
 *
 * <p>Runs are not owned here. This exists so the browser sees one origin: an embedded
 * report needs same-origin to be framed, a live log needs it for SSE, and routing through
 * copilot means one authentication story rather than exposing the runner to the network.</p>
 *
 * <p>Upstream statuses are passed through unchanged. In particular <b>409
 * confirmation_required</b> is a question, not a failure — it carries the runs already
 * active on that environment so the UI can ask "a run is already active on qa3 — continue?"
 * and resubmit with {@code confirm: true}.</p>
 */
@RestController
@RequestMapping("/automation/runs")
public class AutomationController {

    private static final Logger log = LoggerFactory.getLogger(AutomationController.class);
    /** How many recent same-env runs to hand the analyser for cross-run flakiness. */
    private static final int HISTORY_RUNS = 20;
    private static final String ANALYSIS_SKILL = "testanalysis.analyze_run";
    private static final String REPORT_ARTIFACT = "test_analysis_report";

    private final AutomationRunClient runs;
    private final OrchestratorClient orchestrator;

    public AutomationController(AutomationRunClient runs, OrchestratorClient orchestrator) {
        this.runs = runs;
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ResponseEntity<JsonNode> submit(@RequestBody Map<String, Object> request) {
        AutomationRunClient.UpstreamResponse response = runs.submit(request);
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @GetMapping
    public ResponseEntity<JsonNode> list(@RequestParam(required = false) String env,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String startedBy,
                                         @RequestParam(defaultValue = "50") int limit) {
        AutomationRunClient.UpstreamResponse response = runs.list(env, status, startedBy, limit);
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @GetMapping("/{runId}")
    public ResponseEntity<JsonNode> get(@PathVariable UUID runId) {
        AutomationRunClient.UpstreamResponse response = runs.get(runId);
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @GetMapping("/{runId}/log")
    public ResponseEntity<JsonNode> log(@PathVariable UUID runId) {
        AutomationRunClient.UpstreamResponse response = runs.log(runId);
        return ResponseEntity.status(response.status()).body(response.body());
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<JsonNode> cancel(@PathVariable UUID runId) {
        AutomationRunClient.UpstreamResponse response = runs.cancel(runId);
        return ResponseEntity.status(response.status()).body(response.body());
    }

    /**
     * The run's Allure report, served from this origin so the plane can embed it.
     *
     * <p>Reports can contain the member and card data {@code api.fetch_order} persists, so
     * they are served through copilot's authentication rather than published for anyone
     * holding the URL.</p>
     */
    @GetMapping(value = "/{runId}/report", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> report(@PathVariable UUID runId) {
        String html = runs.report(runId);
        return html == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(html);
    }

    /**
     * The run's AI test-analysis report, served from this origin so the plane can embed it.
     *
     * <p>Calls the orchestrator's {@code testanalysis.analyze_run} skill (deterministic Allure
     * analysis + cross-run flakiness + optional git correlation + LLM narrative) and returns
     * the self-contained HTML report it produces. The orchestrator reads the run's preserved
     * Allure results off the shared artifact root, so this needs no run payload. Errors render
     * as a small HTML notice rather than a broken iframe.</p>
     */
    @GetMapping(value = "/{runId}/analysis", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> analysis(@PathVariable UUID runId,
                                           @RequestParam(defaultValue = "false") boolean git,
                                           @RequestParam(defaultValue = "true") boolean llm) {
        String env = envOf(runId);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("run_id", runId.toString());
        if (env != null) {
            input.put("env", env);
            List<String> recent = recentRunIds(env, runId);
            if (!recent.isEmpty()) {
                input.put("recent_run_ids", recent);
            }
        }
        input.put("git_enabled", git);
        input.put("llm_enabled", llm);

        InvokeRequest.Execute request = new InvokeRequest.Execute(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                runId.toString(),
                input,
                new InvokeRequest.SessionContext(env, null, null, null, null, null, null));

        Envelope envelope;
        try {
            envelope = orchestrator.execute(ANALYSIS_SKILL, request);
        } catch (RuntimeException e) {
            log.warn("Test analysis for run {} failed: {}", runId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(errorHtml("The analysis could not be produced.", e.getMessage()));
        }

        String html = envelope.artifactsOrEmpty().stream()
                .filter(a -> REPORT_ARTIFACT.equals(a.artifactType()) && a.body() instanceof String)
                .map(a -> (String) a.body())
                .findFirst()
                .orElse(null);

        return html == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(errorHtml("No analysis report was produced for this run.", null))
                : ResponseEntity.ok(html);
    }

    /** Best-effort lookup of a run's environment; the analyser still runs without it. */
    private String envOf(UUID runId) {
        try {
            JsonNode detail = runs.get(runId).body();
            if (detail != null && detail.hasNonNull("env")) {
                return detail.get("env").asText();
            }
        } catch (RuntimeException e) {
            log.debug("Could not read env for run {}: {}", runId, e.getMessage());
        }
        return null;
    }

    /** Recent same-env run ids (excluding this one) to widen the cross-run flakiness window. */
    private List<String> recentRunIds(String env, UUID exclude) {
        List<String> ids = new ArrayList<>();
        try {
            JsonNode body = runs.list(env, null, null, HISTORY_RUNS).body();
            JsonNode arr = body != null && body.isArray() ? body
                    : body != null && body.has("runs") ? body.get("runs") : null;
            if (arr != null) {
                for (JsonNode run : arr) {
                    JsonNode id = run.hasNonNull("runId") ? run.get("runId") : run.get("run_id");
                    if (id != null && !id.asText().equals(exclude.toString())) {
                        ids.add(id.asText());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("Could not list recent runs for env {}: {}", env, e.getMessage());
        }
        return ids;
    }

    private static String errorHtml(String message, String detail) {
        String safeDetail = detail == null ? "" : "<p style=\"color:#64748b;font-size:12px\">" + escape(detail) + "</p>";
        return "<!doctype html><html><body style=\"font:14px/1.5 system-ui;padding:24px;color:#334155\">"
                + "<h3>Test analysis unavailable</h3><p>" + escape(message) + "</p>" + safeDetail
                + "</body></html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @ExceptionHandler(AutomationUnavailableException.class)
    public ResponseEntity<Map<String, Object>> unavailable(AutomationUnavailableException e) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("code", "automation_unavailable");
        problem.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
