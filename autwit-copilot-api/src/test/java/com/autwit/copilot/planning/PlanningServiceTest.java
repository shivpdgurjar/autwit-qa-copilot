package com.autwit.copilot.planning;

import java.util.List;
import java.util.Map;

import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The service layer: text ingest, fetch persistence, and the generation guards. */
class PlanningServiceTest extends AbstractPostgresIT {

    @Autowired
    private PlanningService service;
    @Autowired
    private PlanningRepository repo;

    @Test
    void addTextDocumentNormalisesAndHashes() {
        var p = service.createProject("PAY-2481", "Payment retry", null, "m.alvarez", "qa2", null);
        var doc = service.addTextDocument(p.projectId(), SourceType.PASTE, "requirement", "spec", null, null,
                "line one\r\nline two\r\n");
        // CRLF normalised, trimmed; content_hash present.
        assertThat(doc.textContent()).isEqualTo("line one\nline two");
        assertThat(doc.contentHash()).startsWith("sha256:");
        assertThat(doc.selected()).isTrue();
    }

    @Test
    void binaryUploadsAreRejectedUntilPassTwo() {
        var p = service.createProject("PAY-1", null, null, null, "qa2", null);
        assertThatThrownBy(() -> service.addTextDocument(p.projectId(), SourceType.UPLOAD, "requirement",
                "design", "design.pdf", "application/pdf", "%PDF-1.7 ..."))
                .isInstanceOf(ApiException.BadRequest.class)
                .hasMessageContaining("PDF/DOCX");
    }

    @Test
    void fetchContextPersistsCandidatesAndReturnsAConsoleLog() {
        var p = service.createProject("PAY-2481", "Payment retry", null, null, "qa2", null);
        var outcome = service.fetchContext(p.projectId(), List.of("PAY-2481"), List.of("PAY-DESIGN"),
                List.of());

        assertThat(outcome.documents()).hasSize(2)
                .extracting(d -> d.sourceType().wire()).containsExactlyInAnyOrder("jira", "confluence");
        // The fetch console has a line per item plus the summary line.
        assertThat(outcome.log()).isNotEmpty();
        assertThat(repo.listDocuments(p.projectId())).hasSize(2);
    }

    @Test
    void testPlanGenerationRequiresAtLeastOneSelectedSource() {
        var p = service.createProject("PAY-1", null, null, null, "qa2", null);
        assertThatThrownBy(() -> service.generateTestPlan(p.projectId()))
                .isInstanceOf(ApiException.BadRequest.class)
                .hasMessageContaining("at least one document");
    }

    @Test
    void testDataGenerationRequiresScenarios() {
        var p = service.createProject("PAY-1", null, null, null, "qa2", null);
        assertThatThrownBy(() -> service.generateTestData(p.projectId(), List.of(), List.of(), 8, null))
                .isInstanceOf(ApiException.BadRequest.class)
                .hasMessageContaining("at least one scenario");
    }

    @Test
    void generatingATestPlanEnqueuesAPendingJob() {
        var p = service.createProject("PAY-2481", "Payment retry", null, null, "qa2", null);
        service.addTextDocument(p.projectId(), SourceType.PASTE, "requirement", "spec", null, null, "retry with backoff");
        var gen = service.generateTestPlan(p.projectId());
        assertThat(gen.status()).isEqualTo("pending");
        assertThat(gen.generationType()).isEqualTo(GenerationType.TEST_PLAN);
    }
}
