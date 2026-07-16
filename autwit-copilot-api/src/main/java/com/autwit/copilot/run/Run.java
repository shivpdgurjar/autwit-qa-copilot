package com.autwit.copilot.run;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * openapi.yaml Run.
 *
 * <p>lease_until and worker_id are @JsonIgnore'd: they are how the queue works, not
 * something a client should see or reason about. Exposing them would invite a UI to
 * start guessing at lease arithmetic, which is exactly the coupling ADR-001 exists to
 * keep inside the server.
 */
public record Run(
        UUID runId,
        UUID sessionId,
        UUID stepId,
        String runType,
        String status,
        Map<String, Object> request,
        Map<String, Object> progress,
        Map<String, Object> resultSummary,
        Map<String, Object> error,
        int attempts,
        int maxAttempts,
        boolean cancelRequested,
        String idempotencyKey,
        @JsonIgnore Instant leaseUntil,
        @JsonIgnore String workerId,
        Instant queuedAt,
        Instant startedAt,
        Instant endedAt,
        Long elapsedMs) {

    /**
     * Terminal statuses. A late orchestrator result for one of these is discarded.
     *
     * @implNote @JsonIgnore — Jackson would otherwise emit a "terminal" property that
     *           openapi.yaml does not declare, and the spec is the contract the UI
     *           generates from.
     */
    @JsonIgnore
    public boolean isTerminal() {
        return switch (status) {
            case "succeeded", "failed", "cancelled", "timed_out" -> true;
            default -> false;
        };
    }
}
