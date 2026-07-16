package com.autwit.copilot.session;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

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

    /**
     * @implNote {@code @JsonIgnore} because Jackson would otherwise serialise this
     *           helper as an {@code "active"} property that openapi.yaml does not
     *           declare. The UI generates its types from that spec, so a field the spec
     *           does not know about is a field the UI cannot see — undocumented output
     *           is drift, not a bonus.
     */
    @JsonIgnore
    public boolean isActive() {
        return "active".equals(status);
    }
}
