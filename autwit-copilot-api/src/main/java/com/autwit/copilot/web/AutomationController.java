package com.autwit.copilot.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.automation.AutomationRunClient;
import com.autwit.copilot.automation.AutomationUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
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

    private final AutomationRunClient runs;

    public AutomationController(AutomationRunClient runs) {
        this.runs = runs;
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

    @ExceptionHandler(AutomationUnavailableException.class)
    public ResponseEntity<Map<String, Object>> unavailable(AutomationUnavailableException e) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("code", "automation_unavailable");
        problem.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
