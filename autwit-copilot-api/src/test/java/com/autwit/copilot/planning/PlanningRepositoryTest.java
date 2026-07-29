package com.autwit.copilot.planning;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.autwit.copilot.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** V4 persistence + the generation job queue. */
class PlanningRepositoryTest extends AbstractPostgresIT {

    @Autowired
    private PlanningRepository repo;

    @Test
    void createsAndReadsAProject() {
        var p = repo.createProject("PAY-2481", "Payment retry", "Retry plan", "m.alvarez", "qa2");
        assertThat(p.projectId()).isNotNull();
        assertThat(repo.findProject(p.projectId())).get()
                .extracting(PlanningProject::featureKey).isEqualTo("PAY-2481");
        assertThat(repo.listProjects(10)).extracting(PlanningProject::projectId).contains(p.projectId());
    }

    @Test
    void upsertDedupesTheSameExternalRefWithinAProject() {
        var p = repo.createProject("PAY-1", null, null, null, "qa2");
        var first = repo.upsertDocument(p.projectId(), SourceType.JIRA, "PAY-1", "v1", null, "old text", "h1");
        var second = repo.upsertDocument(p.projectId(), SourceType.JIRA, "PAY-1", "v2", null, "new text", "h2");

        // Same (project, source_type, external_ref) → one row, refreshed in place.
        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(repo.listDocuments(p.projectId())).hasSize(1);
        assertThat(repo.findDocument(first.documentId())).get()
                .extracting(SourceDocument::textContent).isEqualTo("new text");
    }

    @Test
    void selectedFilterDrivesTheGenerationCorpus() {
        var p = repo.createProject("PAY-2", null, null, null, "qa2");
        var a = repo.upsertDocument(p.projectId(), SourceType.UPLOAD, "a.md", "A", null, "aaa", "ha");
        repo.upsertDocument(p.projectId(), SourceType.UPLOAD, "b.md", "B", null, "bbb", "hb");
        repo.setSelected(a.documentId(), false);

        assertThat(repo.listSelectedDocuments(p.projectId())).hasSize(1)
                .extracting(SourceDocument::title).containsExactly("B");
    }

    @Test
    void dequeueClaimsExactlyOncePerGeneration() {
        var p = repo.createProject("PAY-3", null, null, null, "qa2");
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
    void persistsAndReadsBackATestPlanWithScenarios() {
        var p = repo.createProject("PAY-4", null, null, null, "qa2");
        var gen = repo.createGeneration(p.projectId(), GenerationType.TEST_PLAN, Map.of());
        var scenarios = List.of(
                new TestPlan.TestScenario("TC-01", 1, "Retry succeeds", "High", "PAY-4"),
                new TestPlan.TestScenario("TC-02", 2, "Retry exhausts", "Medium", "PAY-4"));

        var plan = repo.insertPlan(p.projectId(), gen.generationId(), "Overview", "Scope",
                Map.of("sources", List.of("PAY-4")), scenarios);

        var read = repo.findPlanByGeneration(gen.generationId()).orElseThrow();
        assertThat(read.testPlanId()).isEqualTo(plan.testPlanId());
        assertThat(read.overview()).isEqualTo("Overview");
        assertThat(read.scenarios()).extracting(TestPlan.TestScenario::scenarioKey)
                .containsExactly("TC-01", "TC-02");
        assertThat(repo.findLatestPlan(p.projectId())).get()
                .extracting(TestPlan::testPlanId).isEqualTo(plan.testPlanId());
    }

    @Test
    void persistsAndReadsBackDatasets() {
        var p = repo.createProject("PAY-5", null, null, null, "qa2");
        var gen = repo.createGeneration(p.projectId(), GenerationType.TEST_DATA, Map.of());
        repo.insertDataset(p.projectId(), gen.generationId(), "TC-01",
                List.of("a", "b"), List.of(Map.of("a", 1, "b", 2)));

        var datasets = repo.listDatasetsByGeneration(gen.generationId());
        assertThat(datasets).hasSize(1);
        assertThat(datasets.get(0).columns()).containsExactly("a", "b");
        assertThat(datasets.get(0).rows()).hasSize(1);
    }
}
