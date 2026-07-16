package com.autwit.copilot.session;

import java.util.Map;

/** openapi.yaml UpdateSessionRequest. Every field optional; nulls leave columns untouched. */
public record UpdateSessionRequest(
        String title,
        Map<String, Object> tags,
        Map<String, String> subjects,
        String retentionClass) {
}
