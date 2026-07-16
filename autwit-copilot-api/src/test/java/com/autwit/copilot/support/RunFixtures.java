package com.autwit.copilot.support;

import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.run.Run;
import com.autwit.copilot.run.RunRepository;
import com.autwit.copilot.run.RunType;
import com.autwit.copilot.session.CreateSessionRequest;
import com.autwit.copilot.session.SessionService;
import com.autwit.copilot.session.StepRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Seeds sessions/steps/runs directly, bypassing the API, for the queue-level tests. */
@Component
public class RunFixtures {

    private final SessionService sessions;
    private final StepRepository steps;
    private final RunRepository runs;
    private final JdbcTemplate jdbc;

    public RunFixtures(SessionService sessions, StepRepository steps, RunRepository runs, JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.steps = steps;
        this.runs = runs;
        this.jdbc = jdbc;
    }

    public UUID newSession() {
        return sessions.create(new CreateSessionRequest("priya", "qa2", null, null, null, null, null))
                .sessionId();
    }

    /**
     * Empties the queue.
     *
     * <p>Required before any test that reasons about which run a dequeue returns. The
     * queue is global — dequeue takes the oldest queued run in the whole table, not the
     * oldest in "your" session — so a run left behind by an earlier test is a perfectly
     * valid thing for it to hand back, and the test then asserts against the wrong row.
     * Deleting the sessions cascades to everything.
     */
    public void clearQueue() {
        jdbc.update("delete from autwit.session");
    }

    public Run queueRun(UUID sessionId, RunType type) {
        return queueRun(sessionId, type, type.defaultMaxAttempts(), Map.of("message", "test"));
    }

    public Run queueRun(UUID sessionId, RunType type, int maxAttempts, Map<String, Object> request) {
        var step = steps.insert(sessionId, "skill_invocation", "test", "agent", "pending", null, Map.of());
        return runs.insert(sessionId, step.stepId(), type, request, maxAttempts, null);
    }

    /** Simulates a worker that died: the lease is in the past and nobody is renewing it. */
    public void expireLease(UUID runId) {
        jdbc.update("update autwit.run set lease_until = now() - interval '1 minute' where run_id = ?", runId);
    }

    public String statusOf(UUID runId) {
        return jdbc.queryForObject("select status from autwit.run where run_id = ?", String.class, runId);
    }

    public int attemptsOf(UUID runId) {
        return jdbc.queryForObject("select attempts from autwit.run where run_id = ?", Integer.class, runId);
    }

    public String errorCodeOf(UUID runId) {
        return jdbc.queryForObject(
                "select error->>'code' from autwit.run where run_id = ?", String.class, runId);
    }
}
