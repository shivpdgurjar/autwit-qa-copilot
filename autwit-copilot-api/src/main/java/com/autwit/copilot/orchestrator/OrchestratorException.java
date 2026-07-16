package com.autwit.copilot.orchestrator;

import com.autwit.copilot.orchestrator.dto.Problem;

/** Failures from the orchestrator call itself, as opposed to a failed envelope. */
public sealed class OrchestratorException extends RuntimeException {

    private final transient Problem problem;

    OrchestratorException(String message, Problem problem, Throwable cause) {
        super(message, cause);
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }

    /**
     * The deadline passed. Distinct from a failure on purpose: the outcome is UNKNOWN,
     * so the run lands timed_out rather than failed and is never auto-retried
     * (invariant 8). The orchestrator may still be working — cancellation is
     * cooperative and best-effort, and there is no DELETE /invoke (SKILL_CONTRACT §9).
     */
    public static final class Timeout extends OrchestratorException {
        public Timeout(String message, Throwable cause) {
            super(message, new Problem(
                    "https://autwit/errors/deadline-exceeded", "Deadline exceeded", 504,
                    "deadline_exceeded", message, "/invoke", null, null, false), cause);
        }
    }

    /** A non-2xx RFC 7807 response, or a transport failure. */
    public static final class Failed extends OrchestratorException {
        public Failed(String message, Problem problem, Throwable cause) {
            super(message, problem, cause);
        }
    }
}
