package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One async LLM generation job — a call to the orchestrator's {@code generate_test_plan} or
 * {@code generate_test_data} skill. Its own durable job record (not {@code autwit.run}, which
 * is session-scoped), carrying the same status/attempts/lease control so
 * {@code PlanningGenerationWorker} can dequeue it with SKIP LOCKED.
 *
 * @param config for {@code test_data}: the selected scenarios, edge_cases, rows_per_scenario,
 *               example_record. Empty for {@code test_plan} (it reads the whole selected corpus).
 */
public record Generation(
        UUID generationId,
        UUID projectId,
        GenerationType generationType,
        String status,
        Map<String, Object> config,
        String responseId,
        int attempts,
        int maxAttempts,
        String workerId,
        Instant leaseExpiresAt,
        Map<String, Object> error,
        Instant createdAt,
        Instant updatedAt) {
}
