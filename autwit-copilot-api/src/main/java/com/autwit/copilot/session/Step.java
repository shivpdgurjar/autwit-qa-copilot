package com.autwit.copilot.session;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * openapi.yaml Step.
 *
 * <p>{@code runId} has no column on autwit.step — the FK points the other way
 * (run.step_id). It is resolved by a left join, and stays null until a run exists.
 */
public record Step(
        UUID stepId,
        UUID sessionId,
        int seq,
        String kind,
        String label,
        String actor,
        String status,
        Instant startedAt,
        Instant endedAt,
        UUID parentStepId,
        Map<String, Object> detail,
        UUID runId) {
}
