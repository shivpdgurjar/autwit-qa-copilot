package com.autwit.copilot.planning;

import java.util.List;
import java.util.Map;

import com.autwit.copilot.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both generations end to end against the {@code fake} planning client: create project + a
 * source → enqueue → the worker calls the (fake) orchestrator → the deliverable is persisted.
 *
 * <p>Drives {@code pollOnce()} directly because {@code AbstractPostgresIT} parks the worker;
 * {@code all} (merged with the parent's {@code fake}) so the worker bean exists.
 */
@ActiveProfiles("all")
class PlanningGenerationRunTest extends AbstractPostgresIT {

    @Autowired
    private PlanningService service;
    @Autowired
    private PlanningRepository repo;
    @Autowired
    private PlanningGenerationWorker worker;

    @Test
    void generatesAndPersistsATestPlan() {
        var project = service.createProject("PAY-2481", "Payment retry logic", null, "m.alvarez", "qa2");
        service.addTextDocument(project.projectId(), SourceType.PASTE, "spec", null, null,
                "Retry a failed charge with exponential backoff and idempotency.");

        var gen = service.generateTestPlan(project.projectId());
        assertThat(gen.status()).isEqualTo("pending");

        assertThat(worker.pollOnce()).as("the worker claims and runs it").isTrue();

        assertThat(repo.findGeneration(gen.generationId())).get()
                .extracting(Generation::status).isEqualTo("succeeded");

        var plan = repo.findPlanByGeneration(gen.generationId()).orElseThrow();
        assertThat(plan.overview()).contains("retry");
        assertThat(plan.scenarios()).hasSize(5);
        assertThat(plan.scenarios().get(0).scenarioKey()).isEqualTo("TC-01");

        // The chaining token was pinned on the project head so a regenerate can continue it.
        assertThat(repo.findProject(project.projectId())).get()
                .extracting(PlanningProject::latestResponseId).isEqualTo("resp-fake-plan-PAY-2481");
    }

    @Test
    void generatesAndPersistsTestData() {
        var project = service.createProject("PAY-2481", "Payment retry logic", null, null, "qa2");

        var gen = service.generateTestData(project.projectId(),
                List.of(Map.of("id", "TC-01", "title", "Retry succeeds"),
                        Map.of("id", "TC-02", "title", "Retry exhausts")),
                List.of("boundary", "null"), 5, null);

        assertThat(worker.pollOnce()).isTrue();

        assertThat(repo.findGeneration(gen.generationId())).get()
                .extracting(Generation::status).isEqualTo("succeeded");

        var datasets = repo.listDatasetsByGeneration(gen.generationId());
        assertThat(datasets).hasSize(2);
        assertThat(datasets).extracting(TestDataset::scenarioKey).containsExactly("TC-01", "TC-02");
        // 5 rows per scenario, deterministic columns from the fake.
        assertThat(datasets.get(0).rows()).hasSize(5);
        assertThat(datasets.get(0).columns()).contains("transaction_id", "expected_status");
    }
}
