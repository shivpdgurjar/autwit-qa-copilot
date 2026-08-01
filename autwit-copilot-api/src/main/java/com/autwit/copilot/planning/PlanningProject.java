package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.UUID;

/**
 * A planning project: the anchor for one feature's test-plan work. Holds the feature
 * key/description the tester set in Step 1, and the OpenAI chaining token from the latest
 * generation so a regenerate can continue the conversation.
 *
 * @param sessionId        the planning session this project belongs to (V7).
 * @param latestResponseId a cache, never a dependency — null/expired degrades to a fresh
 *                         generation (same rule as {@code analysis_session}). The session now
 *                         carries the authoritative lineage; this stays for per-project history.
 * @param version          optimistic lock; a generation records its result WHERE version matches.
 */
public record PlanningProject(
        UUID projectId,
        UUID sessionId,
        String featureKey,
        String featureDescription,
        String title,
        String status,
        String createdBy,
        String env,
        String latestResponseId,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
