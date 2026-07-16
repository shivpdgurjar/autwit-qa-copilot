package com.autwit.copilot.run;

/**
 * autwit.run.run_type, and the source of truth for max_attempts (ADR-001).
 *
 * <p>max_attempts is fixed at enqueue because that is the only moment we can reason
 * about it, and it gates whether the dequeue may reclaim a dead worker's run. It is
 * NOT left to the column default — the default exists for safety, not for use.
 */
public enum RunType {

    /**
     * The LLM picks the skill inside the orchestrator, i.e. after we enqueue. We
     * therefore cannot know whether it mutates, and unknown is treated as mutating:
     * never reclaimed, reaped to timed_out, a human decides.
     *
     * <p>Raise to 2 only if SKILL_CONTRACT §11 item 1 is answered "MUST replay on
     * run_id" — see CONTRACT_RATIFICATION_REQUEST.md Q2.
     */
    INVOKE("invoke", 1),

    /** Overridden at enqueue from the catalog's side_effects; 1 is the safe default. */
    SKILL_EXECUTE("skill_execute", 1),

    /** snapshot.capture is side_effects: none. */
    MILESTONE("milestone", 2),

    /** Local diff. Never touches the orchestrator. */
    COMPARISON("comparison", 2),

    /** Local render. Never touches the orchestrator. */
    REPORT("report", 2);

    private final String wire;
    private final int defaultMaxAttempts;

    RunType(String wire, int defaultMaxAttempts) {
        this.wire = wire;
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    public String wire() {
        return wire;
    }

    public int defaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    /** True when a dead worker's run of this type may be re-executed. */
    public boolean reclaimable() {
        return defaultMaxAttempts > 1;
    }
}
