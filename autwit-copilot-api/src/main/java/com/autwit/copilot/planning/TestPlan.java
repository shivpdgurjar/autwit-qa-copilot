package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Step-3 deliverable: overview + scope prose and an ordered scenario table, with the
 * provenance line (which tickets / doc version it was built from).
 */
public record TestPlan(
        UUID testPlanId,
        UUID projectId,
        UUID generationId,
        String overview,
        String scope,
        Map<String, Object> provenance,
        List<TestScenario> scenarios,
        Instant createdAt) {

    /** One row of the scenario table; Step 4's data generation keys off {@code scenarioKey}. */
    public record TestScenario(
            String scenarioKey,
            int seq,
            String title,
            String priority,
            String source) {
    }
}
