package com.autwit.copilot.run;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.orchestrator.dto.InvokeRequest;
import com.autwit.copilot.session.MilestoneRepository;
import com.autwit.copilot.session.SessionRepository;
import com.autwit.copilot.session.StepRepository;
import org.springframework.stereotype.Component;

/**
 * Builds session_context from the DB (BUILD_BRIEF §6).
 *
 * <p>SKILL_CONTRACT §3: "session_context is the whole reason the orchestrator can be
 * stateless. It carries everything needed for 'capture events since the last
 * milestone'." Every call carries the full picture; the orchestrator remembers
 * nothing between them.
 */
@Component
public class SessionContextBuilder {

    /** Enough for the orchestrator to know what just happened, not the whole session. */
    private static final int RECENT_STEPS = 10;

    private final SessionRepository sessions;
    private final MilestoneRepository milestones;
    private final StepRepository steps;

    public SessionContextBuilder(SessionRepository sessions, MilestoneRepository milestones,
            StepRepository steps) {
        this.sessions = sessions;
        this.milestones = milestones;
        this.steps = steps;
    }

    public InvokeRequest.SessionContext build(UUID sessionId) {
        var session = sessions.find(sessionId).orElseThrow();
        var allMilestones = milestones.listBySession(sessionId);

        var milestoneRefs = allMilestones.stream()
                .map(m -> new InvokeRequest.MilestoneRef(
                        m.name(),
                        m.milestoneId().toString(),
                        m.markedAt() != null ? m.markedAt().toString() : null,
                        m.snapshotId() != null ? m.snapshotId().toString() : null,
                        m.eventCursor()))
                .toList();

        // The union of every milestone's cursor, latest offset per topic. This is what
        // makes "events since step 2" work: the orchestrator re-reads from here and
        // returns everything, and our dedupe constraint extracts the delta.
        var cursors = mergeCursors(allMilestones);

        var latestSnapshot = allMilestones.stream()
                .filter(m -> m.snapshotId() != null)
                .reduce((a, b) -> b)
                .map(m -> m.snapshotId().toString())
                .orElse(null);

        var allSteps = steps.listBySession(sessionId, null);
        var recent = allSteps.stream()
                .skip(Math.max(0, allSteps.size() - RECENT_STEPS))
                .map(s -> new InvokeRequest.StepRef(s.seq(), s.kind(), s.label()))
                .toList();

        return new InvokeRequest.SessionContext(
                session.env(), session.testerId(), session.subjects(),
                milestoneRefs, latestSnapshot, cursors, recent);
    }

    /**
     * Latest offset wins per topic/partition. Milestones are ordered by seq, so a later
     * milestone's cursor supersedes an earlier one — going backwards would re-read
     * events we already have, which is harmless (dedupe drops them) but wasteful.
     */
    private static Map<String, Object> mergeCursors(java.util.List<com.autwit.copilot.session.Milestone> ms) {
        var merged = new HashMap<String, Object>();
        for (var m : ms) {
            if (m.eventCursor() != null) {
                merged.putAll(m.eventCursor());
            }
        }
        return merged;
    }
}
