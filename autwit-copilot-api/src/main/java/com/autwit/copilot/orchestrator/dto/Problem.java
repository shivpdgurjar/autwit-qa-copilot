package com.autwit.copilot.orchestrator.dto;

/**
 * SKILL_CONTRACT §8. RFC 7807.
 *
 * @param retryable advisory only. copilot-api never auto-retries a mutating skill
 *                  regardless of this flag — max_attempts stays 1 and a human clicks
 *                  retry. See ADR-001.
 */
public record Problem(
        String type,
        String title,
        Integer status,
        String code,
        String detail,
        String instance,
        String runId,
        String skillName,
        Boolean retryable) {

    /** §8's table of codes copilot-api handles specially. */
    public boolean isDeadlineExceeded() {
        return "deadline_exceeded".equals(code);
    }

    public boolean requiresCatalogResync() {
        return "skill_not_found".equals(code) || "skill_disabled".equals(code);
    }
}
