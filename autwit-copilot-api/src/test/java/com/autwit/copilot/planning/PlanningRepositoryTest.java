package com.autwit.copilot.planning;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.autwit.copilot.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** V4 persistence + the generation job queue. */
class PlanningRepositoryTest extends AbstractPostgresIT {

    @Autowired
    private PlanningRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The queue tests below claim "the" pending generation and assert nothing is left, but
     * {@code dequeueGeneration} and {@code reapExpiredGenerations} sweep the WHOLE database and
     * the Postgres container is a JVM-wide singleton. They therefore only hold while no other
     * test's generation is outstanding — an assumption that was accidental until adding two
     * tests reshuffled JUnit's method order and broke them. Draining first makes it explicit.
     */
    @BeforeEach
    void drainTheGenerationQueue() {
        jdbc.update("update autwit.generation set status = 'succeeded' where status in ('pending', 'running')");
    }

    /** V7: every project needs a session — create one and a project under it. */
    private PlanningProject mkProject(String featureKey, String desc, String title, String by, String env) {
        var s = repo.createSession(by, env, title);
        return repo.createProject(s.sessionId(), featureKey, desc, null, title, by, env);
    }

    @Test
    void createsAndReadsAProject() {
        var p = mkProject("PAY-2481", "Payment retry", "Retry plan", "m.alvarez", "qa2");
        assertThat(p.projectId()).isNotNull();
        assertThat(repo.findProject(p.projectId())).get()
                .extracting(PlanningProject::featureKey).isEqualTo("PAY-2481");
        assertThat(repo.listProjects(10)).extracting(PlanningProject::projectId).contains(p.projectId());
    }

    @Test
    void upsertDedupesTheSameExternalRefWithinAProject() {
        var p = mkProject("PAY-1", null, null, null, "qa2");
        var first = repo.upsertDocument(p.projectId(), SourceType.JIRA, DocRole.REQUIREMENT, "PAY-1", "v1", null, "old text", "h1");
        var second = repo.upsertDocument(p.projectId(), SourceType.JIRA, DocRole.REQUIREMENT, "PAY-1", "v2", null, "new text", "h2");

        // Same (project, source_type, external_ref) → one row, refreshed in place.
        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(repo.listDocuments(p.projectId())).hasSize(1);
        assertThat(repo.findDocument(first.documentId())).get()
                .extracting(SourceDocument::textContent).isEqualTo("new text");
    }

    @Test
    void selectedFilterDrivesTheGenerationCorpus() {
        var p = mkProject("PAY-2", null, null, null, "qa2");
        var a = repo.upsertDocument(p.projectId(), SourceType.UPLOAD, DocRole.REQUIREMENT, "a.md", "A", null, "aaa", "ha");
        repo.upsertDocument(p.projectId(), SourceType.UPLOAD, DocRole.REQUIREMENT, "b.md", "B", null, "bbb", "hb");
        repo.setSelected(a.documentId(), false);

        assertThat(repo.listSelectedDocuments(p.projectId())).hasSize(1)
                .extracting(SourceDocument::title).containsExactly("B");
    }

    @Test
    void dequeueClaimsExactlyOncePerGeneration() {
        var p = mkProject("PAY-3", null, null, null, "qa2");
        var gen = repo.createGeneration(p.projectId(), GenerationType.TEST_PLAN, Map.of());
        assertThat(gen.status()).isEqualTo("pending");

        var claimed = repo.dequeueGeneration("w1", Duration.ofMinutes(1));
        assertThat(claimed).get().extracting(Generation::generationId).isEqualTo(gen.generationId());
        assertThat(claimed.get().status()).isEqualTo("running");

        // Nothing left to claim — a second worker gets empty.
        assertThat(repo.dequeueGeneration("w2", Duration.ofMinutes(1))).isEmpty();

        assertThat(repo.succeedGeneration(gen.generationId(), "w1", "resp-1")).isTrue();
        assertThat(repo.findGeneration(gen.generationId())).get()
                .extracting(Generation::status, Generation::responseId)
                .containsExactly("succeeded", "resp-1");
    }

    @Test
    void persistsAndReadsBackARichTestPlan() {
        var p = mkProject("PAY-4", null, null, null, "qa2");
        var gen = repo.createGeneration(p.projectId(), GenerationType.TEST_PLAN, Map.of());
        settle(gen);
        var plan = repo.insertPlan(p.projectId(), gen.generationId(), richResult(), richScenarios());

        var read = repo.findPlanByGeneration(gen.generationId()).orElseThrow();
        assertThat(read.testPlanId()).isEqualTo(plan.testPlanId());
        assertThat(read.planVersion()).isEqualTo(2);
        assertThat(read.overview()).isEqualTo("Overview");
        assertThat(read.scope().inScope()).containsExactly("Retry");
        assertThat(read.scope().outOfScope()).containsExactly("Refunds");
        assertThat(read.executionStrategy()).isEqualTo("Retry execution first.");
        assertThat(read.architectureContext()).containsEntry("summary", "An overlay on payment auth.");
        assertThat(read.requirements()).extracting(TestPlan.Requirement::id).containsExactly("REQ-01");
        assertThat(read.testDataRequirements()).extracting(TestPlan.TestDataRequirement::id)
                .containsExactly("DATA-01");
        assertThat(read.risks()).hasSize(1);
        assertThat(read.gaps()).hasSize(1);

        var first = read.scenarios().get(0);
        assertThat(read.scenarios()).extracting(TestPlan.TestScenario::scenarioKey)
                .containsExactly("TC-01", "TC-02");
        assertThat(first.capability()).isEqualTo("Retry execution");
        assertThat(first.objective()).isEqualTo("Proves a transient failure recovers.");
        assertThat(first.lifecyclePhase()).isEqualTo("Payment authorisation");
        assertThat(first.preconditions()).containsExactly("A charge that failed transiently");
        assertThat(first.steps()).containsExactly("Trigger the retry job");
        assertThat(first.expectedResults()).containsExactly("The charge is CAPTURED");
        assertThat(first.testDataRequirements()).containsExactly("DATA-01");
        assertThat(first.requirementIds()).containsExactly("REQ-01");
        assertThat(first.sources()).containsExactly("PAY-4");
        assertThat(first.source()).isEqualTo("PAY-4");
        assertThat(first.automationMapping()).containsEntry("type", "api");
        // A case the material gave no automation target for must stay null, not {}.
        assertThat(read.scenarios().get(1).automationMapping()).isNull();

        assertThat(repo.findLatestPlan(p.projectId())).get()
                .extracting(TestPlan::testPlanId).isEqualTo(plan.testPlanId());
    }

    @Test
    void aLegacyPlanRowStillReadsBackThroughTheCompatibilityAdapter() {
        // Written the way v1 wrote it: plan_version 1, prose scope, empty payload, and only
        // the four original scenario columns. Reopening such a plan must not break.
        var p = mkProject("PAY-9", null, null, null, "qa2");
        var gen = repo.createGeneration(p.projectId(), GenerationType.TEST_PLAN, Map.of());
        settle(gen);
        var planId = jdbc.queryForObject(
                """
                insert into autwit.test_plan (project_id, generation_id, overview, scope, provenance)
                values (?, ?, 'Legacy overview', 'Covers retry. Excludes refunds.', '{}'::jsonb)
                returning test_plan_id
                """,
                java.util.UUID.class, p.projectId(), gen.generationId());
        jdbc.update("""
                insert into autwit.test_scenario (test_plan_id, scenario_key, seq, title, priority, source)
                values (?, 'TC-01', 1, 'Legacy scenario', 'High', 'PAY-9')
                """, planId);

        var read = repo.findPlan(planId).orElseThrow();

        assertThat(read.planVersion()).isEqualTo(1);
        assertThat(read.overview()).isEqualTo("Legacy overview");
        // The prose scope folds into in_scope so the structured field is never null.
        assertThat(read.scope().inScope()).containsExactly("Covers retry. Excludes refunds.");
        assertThat(read.scope().outOfScope()).isEmpty();
        assertThat(read.architectureContext()).isNull();
        // No capability on any row is what makes the UI fall back to the flat table.
        assertThat(read.scenarios()).allSatisfy(s -> assertThat(s.capability()).isNull());
        assertThat(read.requirements()).isEmpty();
        assertThat(read.gaps()).isEmpty();

        var only = read.scenarios().get(0);
        assertThat(only.title()).isEqualTo("Legacy scenario");
        assertThat(only.capability()).isNull();
        assertThat(only.steps()).isEmpty();
        assertThat(only.expectedResults()).isEmpty();
        assertThat(only.automationMapping()).isNull();
    }

    /**
     * Takes a generation out of the shared queue.
     *
     * <p>{@code dequeueGeneration} claims the oldest PENDING row across the whole database,
     * and the dequeue/reap tests assert they claim their own. A test that only needs a
     * generation row to hang a plan off must therefore not leave it pending, or it silently
     * steals their claim — which is exactly what happened when these two tests were added and
     * JUnit's method ordering shifted.
     */
    private void settle(Generation gen) {
        jdbc.update("update autwit.generation set status = 'succeeded' where generation_id = ?",
                gen.generationId());
    }

    /** Null-tolerant Map.of, for the payload fields that are legitimately null. */
    private static Map<String, Object> mapOf(Object... kv) {
        var m = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static PlanningClient.TestPlanResult richResult() {
        return new PlanningClient.TestPlanResult(
                "Overview",
                new TestPlan.Scope(List.of("Retry"), List.of("Refunds")),
                Map.of("summary", "An overlay on payment auth."),
                List.of(new TestPlan.Requirement("REQ-01", "Failed charges retry.", "functional",
                        List.of("PAY-4"), "Charges are retried.", "Payment authorisation")),
                List.of(new TestPlan.TestDataRequirement("DATA-01", "Failing charge", "A rejected charge",
                        List.of("credit card"), null)),
                List.of(),
                "Retry execution first.",
                List.of(Map.of("title", "Sandbox flakiness", "detail", "502s", "mitigation", "Assert on record")),
                List.of(Map.of("title", "Backoff ceiling", "detail", "Unstated", "blocks_testing", false)),
                Map.of("sources", List.of("PAY-4")),
                Map.of("overview", "Overview", "scope", Map.of("in_scope", List.of("Retry"),
                        "out_of_scope", List.of("Refunds")),
                        "architecture_context", Map.of("summary", "An overlay on payment auth."),
                        "requirements", List.of(Map.of("id", "REQ-01", "statement", "Failed charges retry.",
                                "category", "functional", "sources", List.of("PAY-4"),
                                "evidence", "Charges are retried.", "lifecycle_phase", "Payment authorisation")),
                        // Map.of rejects null values, and source_of_truth is legitimately null
                        // when the material names no owner for the data.
                        "test_data_requirements", List.of(mapOf("id", "DATA-01", "name", "Failing charge",
                                "description", "A rejected charge", "attributes", List.of("credit card"),
                                "source_of_truth", null)),
                        "execution_strategy", "Retry execution first.",
                        "risks", List.of(Map.of("title", "Sandbox flakiness", "detail", "502s",
                                "mitigation", "Assert on record")),
                        "gaps", List.of(Map.of("title", "Backoff ceiling", "detail", "Unstated",
                                "blocks_testing", false))),
                "resp-1");
    }

    private static List<TestPlan.TestScenario> richScenarios() {
        return List.of(
                new TestPlan.TestScenario("TC-01", 1, "Retry execution", "Retry succeeds", "High",
                        "Proves a transient failure recovers.", "Payment authorisation",
                        List.of("PAY-4"), List.of("REQ-01"), List.of("A charge that failed transiently"),
                        List.of("Trigger the retry job"), List.of("The charge is CAPTURED"),
                        List.of("DATA-01"), Map.of("type", "api", "target", "POST /retry", "notes", ""),
                        "PAY-4"),
                new TestPlan.TestScenario("TC-02", 2, "Retry execution", "Retry exhausts", "Medium",
                        "Proves the budget is bounded.", null, List.of("PAY-4"), List.of("REQ-01"),
                        List.of("A charge failing every time"), List.of("Exhaust the budget"),
                        List.of("The charge is FAILED"), List.of(), null, "PAY-4"));
    }

    @Test
    void persistsAndReadsBackDatasets() {
        var p = mkProject("PAY-5", null, null, null, "qa2");
        var gen = repo.createGeneration(p.projectId(), GenerationType.TEST_DATA, Map.of());
        repo.insertDataset(p.projectId(), gen.generationId(), "TC-01",
                List.of("a", "b"), List.of(Map.of("a", 1, "b", 2)));

        var datasets = repo.listDatasetsByGeneration(gen.generationId());
        assertThat(datasets).hasSize(1);
        assertThat(datasets.get(0).columns()).containsExactly("a", "b");
        assertThat(datasets.get(0).rows()).hasSize(1);
    }

    @Test
    void reapsAGenerationWhoseWorkerLeaseExpired() {
        var p = mkProject("PAY-6", null, null, null, "qa2");
        var gen = repo.createGeneration(p.projectId(), GenerationType.TEST_PLAN, Map.of());
        // Claim it with an already-expired lease — a worker that died before renewing. attempts
        // becomes 1 (= max_attempts), so the dequeue can never reclaim it; only the reaper can.
        repo.dequeueGeneration("dead-worker", Duration.ofSeconds(-1));
        assertThat(repo.findGeneration(gen.generationId())).get()
                .extracting(Generation::status).isEqualTo("running");

        repo.reapExpiredGenerations();

        // Asserted on this generation specifically — reap is a global sweep on a shared DB.
        assertThat(repo.findGeneration(gen.generationId())).get()
                .extracting(Generation::status).isEqualTo("failed");
    }

    @Test
    void doesNotReapAHealthyRunningGeneration() {
        var p = mkProject("PAY-7", null, null, null, "qa2");
        var gen = repo.createGeneration(p.projectId(), GenerationType.TEST_PLAN, Map.of());
        repo.dequeueGeneration("live-worker", Duration.ofMinutes(5)); // lease well in the future

        repo.reapExpiredGenerations();

        assertThat(repo.findGeneration(gen.generationId())).get()
                .extracting(Generation::status).isEqualTo("running");
    }
}
