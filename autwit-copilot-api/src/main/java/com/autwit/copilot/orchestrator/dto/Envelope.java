package com.autwit.copilot.orchestrator.dto;

import java.util.List;
import java.util.Map;

/**
 * SKILL_CONTRACT §5 result envelope. Returned by both /invoke and /skills/{name}/execute.
 *
 * <p>Nulls are pervasive by design — a comparison run returns notes and findings and
 * no snapshots; a failed run returns only an error. Every collection accessor below
 * normalises to an empty list so callers never branch on null.
 */
public record Envelope(
        String runId,
        String status,
        String startedAt,
        String endedAt,
        Long durationMs,
        List<Invocation> invocations,
        List<ArtifactDescriptor> artifacts,
        List<SnapshotDescriptor> snapshots,
        List<EventDescriptor> events,
        List<FindingDescriptor> findings,
        List<Note> notes,
        Map<String, String> subjectsDiscovered,
        Map<String, Object> cursorsAdvanced,
        Map<String, Object> milestone,
        Problem error) {

    /** "some artifacts landed and some didn't" — snapshot partial, run succeeded, warn finding. */
    public boolean isPartial() {
        return "partial".equals(status);
    }

    public boolean isFailed() {
        return "failed".equals(status);
    }

    public List<Invocation> invocationsOrEmpty() {
        return invocations == null ? List.of() : invocations;
    }

    public List<ArtifactDescriptor> artifactsOrEmpty() {
        return artifacts == null ? List.of() : artifacts;
    }

    public List<SnapshotDescriptor> snapshotsOrEmpty() {
        return snapshots == null ? List.of() : snapshots;
    }

    public List<EventDescriptor> eventsOrEmpty() {
        return events == null ? List.of() : events;
    }

    public List<FindingDescriptor> findingsOrEmpty() {
        return findings == null ? List.of() : findings;
    }

    public List<Note> notesOrEmpty() {
        return notes == null ? List.of() : notes;
    }

    public Map<String, String> subjectsDiscoveredOrEmpty() {
        return subjectsDiscovered == null ? Map.of() : subjectsDiscovered;
    }

    public Map<String, Object> cursorsAdvancedOrEmpty() {
        return cursorsAdvanced == null ? Map.of() : cursorsAdvanced;
    }

    /** §5: the "keeps telling about current analysis" channel. Renders in chat, not the timeline. */
    public record Note(String at, String text) {
    }

    public record Invocation(
            String skillName,
            String skillVersion,
            Map<String, Object> input,
            String status,
            Integer exitCode,
            Long durationMs,
            Map<String, Object> outputInline) {
    }
}
