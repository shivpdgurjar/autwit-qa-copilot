package com.autwit.copilot.artifact;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * openapi.yaml ArtifactRef (metadata) and Artifact (metadata + body).
 *
 * <p>One record serves both: {@code body} is null on the list path and populated on
 * the get path. The alternative — two near-identical records — drifts.
 */
public record Artifact(
        UUID artifactId,
        UUID sessionId,
        UUID stepId,
        UUID milestoneId,
        UUID runId,
        String artifactType,
        String sourceSystem,
        String logicalName,
        ArtifactFormat format,
        long sizeBytes,
        Integer rowCount,
        String contentHash,
        Instant capturedAt,
        Instant purgedAt,
        boolean bodyAvailable,
        Map<String, Object> meta,

        /** Null when purged, and on the list path where bodies are never served. */
        @JsonInclude(JsonInclude.Include.NON_NULL) Object body) {

    /** Metadata only — what GET /sessions/{id}/artifacts returns. */
    public Artifact withoutBody() {
        return new Artifact(artifactId, sessionId, stepId, milestoneId, runId, artifactType, sourceSystem,
                logicalName, format, sizeBytes, rowCount, contentHash, capturedAt, purgedAt, bodyAvailable, meta, null);
    }
}
