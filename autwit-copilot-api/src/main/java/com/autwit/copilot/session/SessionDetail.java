package com.autwit.copilot.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.compare.Comparison;
import com.autwit.copilot.compare.Finding;
import com.autwit.copilot.run.Run;
import com.autwit.copilot.snapshot.Snapshot;

/**
 * openapi.yaml SessionDetail — "THE source of truth. The UI hydrates from this on
 * load and refetches after any run.succeeded / run.failed notification."
 *
 * <p>Session's fields are flattened in rather than composed. openapi models this as
 * an allOf, and Jackson's @JsonUnwrapped does not apply cleanly to record components,
 * so the wire shape wins over the object model here.
 *
 * <p>Events are deliberately absent — only their count. A session can hold thousands,
 * and this endpoint is fetched on every SSE hint; GET /sessions/{id}/events paginates
 * them instead.
 */
public record SessionDetail(
        UUID sessionId,
        String correlationId,
        String testerId,
        String env,
        String title,
        String status,
        String retentionClass,
        Instant startedAt,
        Instant endedAt,
        Instant expiresAt,
        Map<String, Object> tags,
        Map<String, String> subjects,

        List<Step> steps,
        List<Milestone> milestones,
        List<Snapshot> snapshots,
        List<Comparison> comparisons,
        List<Finding> findings,
        List<Run> activeRuns,
        Counts counts) {

    public record Counts(int artifacts, int events, Map<String, Integer> findingsBySeverity) {
    }

    public static SessionDetail of(Session s, List<Step> steps, List<Milestone> milestones,
            List<Snapshot> snapshots, List<Comparison> comparisons, List<Finding> findings,
            List<Run> activeRuns, Counts counts) {
        return new SessionDetail(
                s.sessionId(), s.correlationId(), s.testerId(), s.env(), s.title(), s.status(),
                s.retentionClass(), s.startedAt(), s.endedAt(), s.expiresAt(), s.tags(), s.subjects(),
                steps, milestones, snapshots, comparisons, findings, activeRuns, counts);
    }
}
