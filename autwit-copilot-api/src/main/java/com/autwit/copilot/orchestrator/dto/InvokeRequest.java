package com.autwit.copilot.orchestrator.dto;

import java.util.List;
import java.util.Map;

/**
 * SKILL_CONTRACT §3 and §4 requests, and the session_context both carry.
 *
 * <p>No {@code deadline_ms}: v0.1.5 removed it after the orchestrator audit found it was
 * accepted and never read, advertising enforcement that did not exist. Per §9 the
 * orchestrator enforces no deadline at all, so copilot-api's client-side timeout
 * ({@code autwit.orchestrator.timeout}, 10m) is the only one in the system.
 */
public final class InvokeRequest {

    private InvokeRequest() {
    }

    /** §3 POST /invoke. */
    public record Invoke(
            String sessionId,
            String correlationId,
            String runId,
            String message,
            String skillHint,
            SessionContext sessionContext) {
    }

    /** §4 POST /skills/{name}/execute. No LLM. */
    public record Execute(
            String sessionId,
            String correlationId,
            String runId,
            Map<String, Object> input,
            SessionContext sessionContext) {
    }

    /**
     * §3: "the whole reason the orchestrator can be stateless. It carries everything
     * needed for 'capture events since the last milestone'."
     */
    public record SessionContext(
            String env,
            String testerId,
            Map<String, String> subjects,
            List<MilestoneRef> milestones,
            String latestSnapshotId,
            Map<String, Object> eventCursors,
            List<StepRef> recentSteps) {
    }

    public record MilestoneRef(
            String name,
            String milestoneId,
            String markedAt,
            String snapshotId,
            Map<String, Object> eventCursor) {
    }

    public record StepRef(int seq, String kind, String label) {
    }
}
