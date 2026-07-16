package com.autwit.copilot.session;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** openapi.yaml Milestone. */
public record Milestone(
        UUID milestoneId,
        UUID sessionId,
        UUID stepId,
        String name,
        int seq,
        String status,
        Instant markedAt,
        UUID snapshotId,
        Map<String, Object> eventCursor,
        String note) {
}
