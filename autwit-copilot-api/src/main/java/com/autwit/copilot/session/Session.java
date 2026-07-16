package com.autwit.copilot.session;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** openapi.yaml Session. */
public record Session(
        UUID sessionId,
        String correlationId,
        String testerId,
        String env,
        String title,
        String status,
        String retentionClass,
        Instant startedAt,
        Instant endedAt,
        Instant expiresAt,
        Map<String, Object> tags,
        Map<String, String> subjects) {

    public boolean isActive() {
        return "active".equals(status);
    }
}
