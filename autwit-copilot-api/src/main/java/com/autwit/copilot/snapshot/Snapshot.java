package com.autwit.copilot.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** openapi.yaml Snapshot. */
public record Snapshot(
        UUID snapshotId,
        UUID sessionId,
        UUID milestoneId,
        UUID stepId,
        String label,
        String scope,
        Map<String, Object> scopeDef,
        String status,
        Instant capturedAt,
        String compositeHash,
        List<Part> parts) {

    /**
     * @param partKey stable across snapshots of the same scope — comparison is a
     *                key-wise join on it.
     */
    public record Part(String partKey, UUID artifactId, Integer rowCount, String contentHash) {
    }

    public Snapshot withParts(List<Part> parts) {
        return new Snapshot(snapshotId, sessionId, milestoneId, stepId, label, scope, scopeDef,
                status, capturedAt, compositeHash, parts);
    }
}
