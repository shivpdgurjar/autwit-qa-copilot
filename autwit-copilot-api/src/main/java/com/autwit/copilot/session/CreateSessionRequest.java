package com.autwit.copilot.session;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/** openapi.yaml CreateSessionRequest. */
public record CreateSessionRequest(
        @NotBlank String testerId,
        @NotBlank String env,
        String title,
        Map<String, Object> tags,
        Map<String, String> subjects,
        String retentionClass,
        Integer ttlDays) {
}
