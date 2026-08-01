package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry in a planning session's history timeline — what was done and produced, so a tester
 * can resume where they left off and see the accumulating history later generations build on.
 *
 * @param kind session_created | project_added | document_added | context_fetched |
 *             plan_generated | data_generated
 */
public record PlanningActivity(
        long id,
        UUID sessionId,
        String kind,
        String ref,
        String summary,
        Instant at) {
}
