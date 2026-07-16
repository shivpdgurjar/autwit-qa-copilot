package com.autwit.copilot.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.ArtifactService;
import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.compare.Comparison;
import com.autwit.copilot.compare.ComparisonRepository;
import com.autwit.copilot.compare.Finding;
import com.autwit.copilot.compare.FindingRepository;
import com.autwit.copilot.run.RunEnqueuer;
import com.autwit.copilot.session.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalysisController {

    private final RunEnqueuer enqueuer;
    private final ComparisonRepository comparisons;
    private final FindingRepository findings;
    private final ArtifactService artifacts;
    private final SessionService sessions;

    public AnalysisController(RunEnqueuer enqueuer, ComparisonRepository comparisons,
            FindingRepository findings, ArtifactService artifacts, SessionService sessions) {
        this.enqueuer = enqueuer;
        this.comparisons = comparisons;
        this.findings = findings;
        this.artifacts = artifacts;
        this.sessions = sessions;
    }

    public record CreateComparisonRequest(
            @NotNull UUID fromSnapshotId,
            @NotNull UUID toSnapshotId,
            @NotNull String compareType,
            Map<String, Object> rules) {
    }

    public record EndSessionRequest(String format, String notes) {
    }

    @PostMapping("/sessions/{sessionId}/comparisons")
    ResponseEntity<Map<String, Object>> createComparison(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateComparisonRequest req) {

        if (!List.of("structural", "financial_validation", "consistency").contains(req.compareType())) {
            throw new ApiException.BadRequest("invalid_compare_type",
                    "compare_type must be structural, financial_validation or consistency.");
        }

        var accepted = enqueuer.enqueueComparison(sessionId, req.fromSnapshotId(), req.toSnapshotId(),
                req.compareType(), req.rules(), idempotencyKey);

        return ResponseEntity.accepted().body(Map.of(
                "run_id", accepted.run().runId(),
                "step_id", accepted.run().stepId(),
                "status", accepted.run().status(),
                "comparison_id", accepted.comparisonId()));
    }

    @GetMapping("/sessions/{sessionId}/comparisons/list")
    Map<String, List<Comparison>> listComparisons(@PathVariable UUID sessionId) {
        sessions.get(sessionId);
        return Map.of("comparisons", comparisons.listBySession(sessionId));
    }

    @GetMapping("/comparisons/{comparisonId}")
    Comparison getComparison(@PathVariable UUID comparisonId) {
        var comparison = comparisons.find(comparisonId)
                .orElseThrow(() -> new ApiException.NotFound("comparison", comparisonId));

        var its = findings.listBySession(comparison.sessionId(), null, null).stream()
                .filter(f -> comparisonId.equals(f.comparisonId()))
                .toList();

        return comparison.withDetail(its, countBySeverity(its));
    }

    @GetMapping("/sessions/{sessionId}/findings")
    Map<String, List<Finding>> listFindings(
            @PathVariable UUID sessionId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String category) {
        sessions.get(sessionId);
        return Map.of("findings", findings.listBySession(sessionId, severity, category));
    }

    /** Session status flips to 'ended' immediately; the report appears when the run completes. */
    @PostMapping("/sessions/{sessionId}/end")
    ResponseEntity<Map<String, Object>> endSession(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) EndSessionRequest req) {

        var format = req != null && req.format() != null ? req.format() : "html";
        var accepted = enqueuer.enqueueReport(sessionId, format,
                req != null ? req.notes() : null, idempotencyKey);

        return ResponseEntity.accepted().body(Map.of(
                "run_id", accepted.run().runId(),
                "step_id", accepted.run().stepId(),
                "status", accepted.run().status()));
    }

    @GetMapping("/sessions/{sessionId}/report")
    ResponseEntity<String> getReport(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "html") String format) {

        sessions.get(sessionId);
        var name = "report." + format;

        var report = artifacts.list(sessionId, "final_report", null, name).stream()
                .reduce((a, b) -> b) // the newest, if /end ran more than once
                .orElseThrow(() -> new ApiException.NotFound("report for session " + sessionId, name));

        var body = artifacts.get(report.artifactId()).body();
        return ResponseEntity.ok()
                .contentType("md".equals(format) ? MediaType.valueOf("text/markdown") : MediaType.TEXT_HTML)
                .body(String.valueOf(body));
    }

    private static Map<String, Integer> countBySeverity(List<Finding> findings) {
        return findings.stream().collect(java.util.stream.Collectors.groupingBy(
                Finding::severity, java.util.stream.Collectors.summingInt(f -> 1)));
    }
}
