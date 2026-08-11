package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Step-3 deliverable: a synthesized test plan, not a scenario summary.
 *
 * <p>v2 groups test cases under business capabilities and gives each case the detail a tester
 * needs to execute it. {@code payload} keeps the generator's full artifact body verbatim so a
 * later field addition needs no migration; the typed columns below are the parts the API and
 * UI read directly, and {@code scenarios} stays a flat child table because Step 5's data
 * generation keys off {@code scenarioKey}.
 *
 * <p>{@code planVersion} is 1 for plans written before the v2 upgrade. Those rows have an
 * empty payload, a legacy prose {@code scope} folded into {@link Scope#inScope()}, and cases
 * carrying only the four v1 fields — see {@code PlanningRepository.buildPlan}.
 */
public record TestPlan(
        UUID testPlanId,
        UUID projectId,
        UUID generationId,
        int planVersion,
        String overview,
        Scope scope,
        Map<String, Object> architectureContext,
        List<Requirement> requirements,
        List<TestDataRequirement> testDataRequirements,
        String executionStrategy,
        List<Map<String, Object>> risks,
        List<Map<String, Object>> gaps,
        Map<String, Object> provenance,
        Map<String, Object> payload,
        List<TestScenario> scenarios,
        Instant createdAt) {

    /** What the plan covers, and what it deliberately does not. */
    public record Scope(List<String> inScope, List<String> outOfScope) {
    }

    /** One normalised, atomic, testable business rule — the join between docs and cases. */
    public record Requirement(
            String id,
            String statement,
            String category,
            List<String> sources,
            String evidence,
            String lifecyclePhase) {
    }

    /** A plan-level data prerequisite (characteristics, never real order numbers). */
    public record TestDataRequirement(
            String id,
            String name,
            String description,
            List<String> attributes,
            String sourceOfTruth) {
    }

    /**
     * One executable test case. Step 5's data generation keys off {@code scenarioKey}, which
     * is why these stay a child table rather than living only inside {@code payload}.
     */
    public record TestScenario(
            String scenarioKey,
            int seq,
            String capability,
            String title,
            String priority,
            String objective,
            String lifecyclePhase,
            List<String> sources,
            List<String> requirementIds,
            List<String> preconditions,
            List<String> steps,
            List<String> expectedResults,
            List<String> testDataRequirements,
            Map<String, Object> automationMapping,
            /** sources[0], retained so v1 readers and the legacy adapter keep working. */
            String source) {
    }
}
