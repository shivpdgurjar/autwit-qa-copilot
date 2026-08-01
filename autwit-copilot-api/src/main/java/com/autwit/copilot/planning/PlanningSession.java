package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.UUID;

/**
 * A resumable, single-tester planning context (docs/PLANNING_SESSIONS_DESIGN.md). Owns the
 * project(s) worked in it and carries the running OpenAI conversation lineage so each new
 * generation continues the previous one.
 *
 * @param latestResponseId the running lineage — a cache, never a dependency (null/expired ⇒ fresh).
 * @param version          optimistic lock; a generation pins its result WHERE version matches.
 * @param lastActiveAt     resume ordering — the landing list is by this, most-recent first.
 */
public record PlanningSession(
        UUID sessionId,
        String testerId,
        String env,
        String title,
        String status,
        String latestResponseId,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant lastActiveAt) {
}
