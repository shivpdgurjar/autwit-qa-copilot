package com.autwit.copilot.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * openapi.yaml SessionDetail — "THE source of truth. The UI hydrates from this on
 * load and refetches after any run.succeeded / run.failed notification."
 *
 * <p>Session's fields are flattened in rather than composed. openapi models this as
 * an allOf, and Jackson's @JsonUnwrapped does not apply cleanly to record
 * components, so the wire shape wins over the object model here.
 *
 * <p>snapshots, comparisons, findings and activeRuns are typed loosely and served
 * empty until the steps that write them land (3 for runs/snapshots, 7 for
 * comparisons/findings). They are present because openapi marks them required and
 * the UI's generated client expects the keys; nothing writes those tables at step 2,
 * so empty is accurate rather than a placeholder.
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
        List<Object> snapshots,
        List<Object> comparisons,
        List<Object> findings,
        List<Object> activeRuns,
        Counts counts) {

    public record Counts(int artifacts, int events, Map<String, Integer> findingsBySeverity) {
    }

    public static SessionDetail of(Session s, List<Step> steps, List<Milestone> milestones, Counts counts) {
        return new SessionDetail(
                s.sessionId(), s.correlationId(), s.testerId(), s.env(), s.title(), s.status(),
                s.retentionClass(), s.startedAt(), s.endedAt(), s.expiresAt(), s.tags(), s.subjects(),
                steps, milestones, List.of(), List.of(), List.of(), List.of(), counts);
    }
}
