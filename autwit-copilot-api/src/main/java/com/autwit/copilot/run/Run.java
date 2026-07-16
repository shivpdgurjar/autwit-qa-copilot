package com.autwit.copilot.run;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** openapi.yaml Run. */
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
        Instant leaseUntil,
        String workerId,
        Instant queuedAt,
        Instant startedAt,
        Instant endedAt,
        Long elapsedMs) {

    /** Terminal statuses. A late orchestrator result for one of these is discarded. */
    public boolean isTerminal() {
        return switch (status) {
            case "succeeded", "failed", "cancelled", "timed_out" -> true;
            default -> false;
        };
    }
}
