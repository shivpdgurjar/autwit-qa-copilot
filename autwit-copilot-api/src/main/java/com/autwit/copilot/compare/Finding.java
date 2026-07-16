package com.autwit.copilot.compare;

import java.time.Instant;
import java.util.UUID;

/** openapi.yaml Finding. */
public record Finding(
        UUID findingId,
        UUID sessionId,
        UUID comparisonId,
        UUID stepId,
        String severity,
        String category,
        String partKey,
        String entityKey,
        String field,
        Object beforeValue,
        Object afterValue,
        String message,
        Instant createdAt) {
}
