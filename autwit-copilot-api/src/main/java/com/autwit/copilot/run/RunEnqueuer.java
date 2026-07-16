package com.autwit.copilot.run;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.registry.SkillRepository;
import com.autwit.copilot.session.MilestoneRepository;
import com.autwit.copilot.session.SessionRepository;
import com.autwit.copilot.session.StepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Submit-only (invariant 3). Every entry point here inserts the step and the run in
 * one transaction, NOTIFYs, and returns — nothing blocks on orchestrator work.
 *
 * <p>The step_id is known before the work executes, which is what lets the UI render
 * an optimistic pending card the instant the 202 lands.
 */
@Service
public class RunEnqueuer {

    private static final Logger log = LoggerFactory.getLogger(RunEnqueuer.class);

    private final RunRepository runs;
    private final StepRepository steps;
    private final SessionRepository sessions;
    private final MilestoneRepository milestones;
    private final SkillRepository skills;

    public RunEnqueuer(RunRepository runs, StepRepository steps, SessionRepository sessions,
            MilestoneRepository milestones, SkillRepository skills) {
        this.runs = runs;
        this.steps = steps;
        this.sessions = sessions;
        this.milestones = milestones;
        this.skills = skills;
    }

    /** POST /sessions/{id}/messages. */
    @Transactional
    public Accepted enqueueMessage(UUID sessionId, String message, Map<String, String> subjects,
            String skillHint, String idempotencyKey) {

        lockAndRequireActive(sessionId);
        var replay = replay(sessionId, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        // The user's utterance, then the agent's pending work: two steps, so the chat
        // shows what was said and what is being done about it as separate cards.
        var userStep = steps.insert(sessionId, "user_utterance", message, "user", "succeeded", null,
                Map.of("text", message));
        var agentStep = steps.insert(sessionId, "skill_invocation", "Working on it…", "agent", "pending",
                userStep.stepId(), skillHint != null ? Map.of("skill_hint", skillHint) : Map.of());

        sessions.mergeSubjects(sessionId, subjects);

        var request = new LinkedHashMap<String, Object>();
        request.put("message", message);
        if (skillHint != null) {
            request.put("skill_hint", skillHint);
        }

        return accept(sessionId, agentStep.stepId(), RunType.INVOKE,
                RunType.INVOKE.defaultMaxAttempts(), request, idempotencyKey, null, null);
    }

    /** POST /sessions/{id}/skills/{name} — the ⌘K palette. */
    @Transactional
    public Accepted enqueueSkill(UUID sessionId, String skillName, Map<String, Object> input, String label,
            boolean confirm, String idempotencyKey) {

        lockAndRequireActive(sessionId);
        var replay = replay(sessionId, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        var skill = skills.find(skillName)
                .orElseThrow(() -> new ApiException.BadRequest("skill_not_found",
                        "Skill '%s' is not in the catalog. It may have been renamed or the catalog may be stale."
                                .formatted(skillName)));

        if (Boolean.FALSE.equals(skill.enabled())) {
            throw new ApiException.BadRequest("skill_disabled", "Skill '%s' is disabled.".formatted(skillName));
        }

        // openapi: confirm must be true for side_effects=mutating. A double-click that
        // places two orders is the failure the whole idempotency design exists to stop;
        // this is the layer that stops the FIRST unintended one.
        if (skill.isMutating() && !confirm) {
            throw new ApiException.Conflict("confirmation_required",
                    "Skill '%s' has side_effects=mutating and requires confirm=true.".formatted(skillName));
        }

        var step = steps.insert(sessionId, "skill_invocation", label != null ? label : skillName, "agent",
                "pending", null, Map.of("skill_name", skillName, "input", input));

        var request = new LinkedHashMap<String, Object>();
        request.put("skill_name", skillName);
        request.put("input", input);

        // ADR-001: we know the skill here, so we can read side_effects rather than
        // assume. Mutating stays at 1 and is never reclaimed after a worker death.
        int maxAttempts = skill.isMutating() ? 1 : 2;

        return accept(sessionId, step.stepId(), RunType.SKILL_EXECUTE, maxAttempts, request,
                idempotencyKey, null, null);
    }

    /** POST /sessions/{id}/milestones. */
    @Transactional
    public Accepted enqueueMilestone(UUID sessionId, String name, java.util.List<String> scopes,
            boolean captureEvents, String note, String idempotencyKey) {

        lockAndRequireActive(sessionId);
        var replay = replay(sessionId, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        if (milestones.existsByName(sessionId, name)) {
            throw new ApiException.Conflict("milestone_exists",
                    "Milestone '%s' already exists in this session.".formatted(name));
        }

        var step = steps.insert(sessionId, "milestone", name, "user", "pending", null,
                Map.of("name", name, "scopes", scopes));

        // Created immediately in 'pending'; the worker fills in snapshot_id and flips
        // status. The UI shows the milestone the moment it is marked, not 90s later.
        var milestone = milestones.insertPending(sessionId, step.stepId(), name,
                milestones.nextSeq(sessionId), note);

        var request = new LinkedHashMap<String, Object>();
        request.put("message", "Mark milestone " + name);
        request.put("milestone_name", name);
        request.put("scopes", scopes);
        request.put("capture_events", captureEvents);

        return accept(sessionId, step.stepId(), RunType.MILESTONE, RunType.MILESTONE.defaultMaxAttempts(),
                request, idempotencyKey, milestone.milestoneId(), null);
    }

    private Accepted accept(UUID sessionId, UUID stepId, RunType type, int maxAttempts,
            Map<String, Object> request, String idempotencyKey, UUID milestoneId, UUID comparisonId) {

        if (milestoneId != null) {
            request.put("milestone_id", milestoneId.toString());
        }

        // No DuplicateKeyException guard around this insert. The session row lock taken
        // before the replay check already serializes concurrent enqueues for a session,
        // so uq_run_idempotency cannot fire here -- and catching it would not help
        // anyway: Postgres aborts the whole transaction on a constraint violation, so
        // the recovery query would itself fail with "current transaction is aborted".
        // The lock is the fix; the index remains the guarantee of last resort.
        var run = runs.insert(sessionId, stepId, type, request, maxAttempts, idempotencyKey);

        runs.notifyRun(sessionId, run.runId(), stepId, "queued", "run.queued");
        log.debug("Enqueued {} run {} for session {} (max_attempts={})",
                type.wire(), run.runId(), sessionId, maxAttempts);
        return new Accepted(run, milestoneId, comparisonId, false);
    }

    /** "Same Idempotency-Key twice → one run, same run_id returned." */
    private Optional<Accepted> replay(UUID sessionId, String idempotencyKey) {
        return runs.findByIdempotencyKey(sessionId, idempotencyKey)
                .map(run -> {
                    log.debug("Idempotency-Key replay for session {}: returning existing run {}",
                            sessionId, run.runId());
                    var milestoneId = Optional.ofNullable(run.request().get("milestone_id"))
                            .map(String::valueOf).map(UUID::fromString).orElse(null);
                    return new Accepted(run, milestoneId, null, true);
                });
    }

    /**
     * Takes the session row lock, then validates.
     *
     * <p>Order matters, and it is the whole reason this is one method. The lock must be
     * held BEFORE the Idempotency-Key replay check, or two concurrent requests both find
     * no prior run, both proceed, and both insert — colliding on either step(session_id,
     * seq) or uq_run_idempotency. With the lock, the second request blocks here, then
     * sees the first request's committed run and replays it. That is what makes
     * "double-clicking Fulfil order must not place two orders" true rather than likely.
     */
    private void lockAndRequireActive(UUID sessionId) {
        steps.lockSession(sessionId);
        var session = sessions.find(sessionId)
                .orElseThrow(() -> new ApiException.NotFound("session", sessionId));
        if (!session.isActive()) {
            throw new ApiException.Conflict("session_ended",
                    "Session %s is %s; no further runs can be enqueued.".formatted(sessionId, session.status()));
        }
    }

    public record Accepted(Run run, UUID milestoneId, UUID comparisonId, boolean replayed) {
    }
}
