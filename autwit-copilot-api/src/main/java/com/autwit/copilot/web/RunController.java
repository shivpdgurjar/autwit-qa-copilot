package com.autwit.copilot.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.run.Run;
import com.autwit.copilot.run.RunEnqueuer;
import com.autwit.copilot.run.RunRepository;
import com.autwit.copilot.session.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Submit-only. Every POST here returns 202 with a run_id and step_id and never blocks
 * on orchestrator work (invariant 3).
 */
@RestController
public class RunController {

    private final RunEnqueuer enqueuer;
    private final RunRepository runs;
    private final SessionService sessions;

    public RunController(RunEnqueuer enqueuer, RunRepository runs, SessionService sessions) {
        this.enqueuer = enqueuer;
        this.runs = runs;
        this.sessions = sessions;
    }

    public record PostMessageRequest(
            @NotBlank String message, Map<String, String> subjects, String skillHint) {
    }

    public record InvokeSkillRequest(Map<String, Object> input, String label, Boolean confirm) {
    }

    public record MarkMilestoneRequest(
            @NotBlank String name, List<String> scopes, Boolean captureEvents, String note) {
    }

    @PostMapping("/sessions/{sessionId}/messages")
    ResponseEntity<Map<String, Object>> postMessage(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PostMessageRequest req) {

        return accepted(enqueuer.enqueueMessage(
                sessionId, req.message(), req.subjects(), req.skillHint(), idempotencyKey));
    }

    @PostMapping("/sessions/{sessionId}/skills/{skillName}")
    ResponseEntity<Map<String, Object>> invokeSkill(
            @PathVariable UUID sessionId,
            @PathVariable String skillName,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody InvokeSkillRequest req) {

        return accepted(enqueuer.enqueueSkill(sessionId, skillName,
                req.input() != null ? req.input() : Map.of(), req.label(),
                Boolean.TRUE.equals(req.confirm()), idempotencyKey));
    }

    @PostMapping("/sessions/{sessionId}/milestones")
    ResponseEntity<Map<String, Object>> markMilestone(
            @PathVariable UUID sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MarkMilestoneRequest req) {

        return accepted(enqueuer.enqueueMilestone(sessionId, req.name(),
                req.scopes() != null ? req.scopes() : List.of("order_flow"),
                req.captureEvents() == null || req.captureEvents(), req.note(), idempotencyKey));
    }

    @GetMapping("/runs/{runId}")
    Run getRun(@PathVariable UUID runId) {
        return runs.find(runId).orElseThrow(() -> new ApiException.NotFound("run", runId));
    }

    /**
     * Cooperative and not guaranteed. A queued run is cancelled immediately; a running
     * one is flagged and the worker checks between orchestrator calls. There is no
     * DELETE /invoke — if the orchestrator is mid-skill it keeps going, and we discard
     * the result when it lands (SKILL_CONTRACT §9).
     */
    @PostMapping("/runs/{runId}/cancel")
    ResponseEntity<Run> cancelRun(@PathVariable UUID runId) {
        var existing = runs.find(runId).orElseThrow(() -> new ApiException.NotFound("run", runId));
        if (existing.isTerminal()) {
            throw new ApiException.Conflict("already_terminal",
                    "Run %s is already %s and cannot be cancelled.".formatted(runId, existing.status()));
        }
        return ResponseEntity.accepted().body(runs.requestCancel(runId).orElse(existing));
    }

    /** Poll fallback for when SSE is unavailable (invariant 4). */
    @GetMapping("/sessions/{sessionId}/runs")
    Map<String, List<Run>> listRuns(
            @PathVariable UUID sessionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean active) {
        sessions.get(sessionId);
        return Map.of("runs", runs.listBySession(sessionId, status, active));
    }

    private static ResponseEntity<Map<String, Object>> accepted(RunEnqueuer.Accepted a) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("run_id", a.run().runId());
        // Known before execution -- the UI renders an optimistic pending card from it.
        body.put("step_id", a.run().stepId());
        body.put("status", a.run().status());
        body.put("queued_at", a.run().queuedAt());
        if (a.milestoneId() != null) {
            body.put("milestone_id", a.milestoneId());
        }
        if (a.comparisonId() != null) {
            body.put("comparison_id", a.comparisonId());
        }
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/runs/" + a.run().runId()))
                .body(body);
    }
}
