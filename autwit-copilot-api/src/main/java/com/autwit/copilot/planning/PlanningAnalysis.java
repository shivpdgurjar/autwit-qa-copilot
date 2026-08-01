package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One completed analysis round — the deliverable a {@code document_analysis} generation
 * produces (the reasoning analogue of {@link TestPlan}). A re-analysis makes a new round;
 * history is kept. Carries its findings so the UI can render the latest round in one read.
 */
public record PlanningAnalysis(
        UUID analysisId,
        UUID reasoningId,
        UUID generationId,
        int round,
        int conflictsTotal,
        int clarificationsTotal,
        List<AnalysisFinding> findings,
        Instant createdAt) {
}
