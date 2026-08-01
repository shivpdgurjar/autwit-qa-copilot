package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.UUID;

/**
 * The durable reasoning thread for a project (one per project). {@code status} gates test-plan
 * generation: {@code open} = a round has run with unresolved findings (blocked); {@code clean}
 * = the last round returned none (unlocked); {@code overridden} = the tester recorded an
 * explicit "proceed anyway" (unlocked). {@code version} is the optimistic lock, same discipline
 * as the session/project heads.
 */
public record PlanningReasoning(
        UUID reasoningId,
        UUID projectId,
        String status,
        int round,
        String overrideReason,
        String overrideBy,
        Instant overrideAt,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public boolean isOpen() {
        return "open".equals(status);
    }
}
