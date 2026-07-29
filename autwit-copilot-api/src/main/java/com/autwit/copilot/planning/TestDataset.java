package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Step-4 deliverable: one dataset per scenario (the tabbed output, one tab per scenario).
 * {@code columns} is the ordered column list; {@code rows} is a list of column→value maps, so
 * any scenario shape is representable without a per-feature schema.
 */
public record TestDataset(
        UUID datasetId,
        UUID projectId,
        UUID generationId,
        String scenarioKey,
        List<String> columns,
        List<Map<String, Object>> rows,
        Instant createdAt) {
}
