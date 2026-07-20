package com.autwit.copilot.analysis;

import java.time.Instant;
import java.util.UUID;

/**
 * A financial-analysis session — the durable head that a {@code LIFECYCLE_COMPARISON}
 * accumulates state against (V2 {@code analysis_session}). copilot-api owns this because
 * the orchestrator is stateless (SKILL_CONTRACT §0 invariant 2).
 *
 * @param analysisId       our allocation; the orchestrator's {@code analysis_id text PK}.
 * @param latestResponseId OpenAI chaining token — a cache, never a dependency. NULL/expired
 *                         degrades to a full re-read (v1.0.16 §4).
 * @param version          optimistic lock. A writer updates WHERE version matches and 409s
 *                         on mismatch — a concurrent chain from the same response id is the
 *                         race it prevents.
 */
public record AnalysisSession(
        String analysisId,
        UUID sessionId,
        String orderNumber,
        String analysisMode,
        String latestResponseId,
        int lastSequence,
        String promptVersion,
        String ruleVersion,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
