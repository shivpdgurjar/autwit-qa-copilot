package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.UUID;

/**
 * A tester's answer to a finding, accumulated across rounds. Every subsequent analysis receives
 * ALL resolutions so the model does not re-raise a settled point. {@code findingId} is the
 * round's finding this answers (nullable — findings are regenerated each round); {@code prompt}
 * keeps the question text so a resolution is self-describing even after that finding is gone.
 */
public record Resolution(
        UUID resolutionId,
        UUID reasoningId,
        int round,
        UUID findingId,
        String kind,
        String prompt,
        String answer,
        Instant createdAt) {
}
