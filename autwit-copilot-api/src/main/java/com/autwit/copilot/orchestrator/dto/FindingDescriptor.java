package com.autwit.copilot.orchestrator.dto;

/**
 * SKILL_CONTRACT §6.4. Findings the orchestrator raises directly, e.g. a skill
 * detecting a missing event. Comparison findings come from our own diff engine.
 */
public record FindingDescriptor(
        String severity,
        String category,
        String partKey,
        String entityKey,
        String field,
        Object beforeValue,
        Object afterValue,
        String message) {
}
