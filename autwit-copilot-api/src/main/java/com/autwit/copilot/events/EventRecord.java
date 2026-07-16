package com.autwit.copilot.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** openapi.yaml EventRecord. */
public record EventRecord(
        UUID eventId,
        UUID sessionId,
        UUID artifactId,
        String source,
        String topic,
        String eventType,
        String eventKey,
        String sourceOffset,
        Instant occurredAt,
        Instant capturedAt,
        UUID afterMilestoneId,
        Map<String, Object> payload) {
}
