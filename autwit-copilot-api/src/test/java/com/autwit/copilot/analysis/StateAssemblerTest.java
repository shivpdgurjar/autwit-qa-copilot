package com.autwit.copilot.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.ArtifactFormat;
import com.autwit.copilot.artifact.ArtifactService;
import com.autwit.copilot.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assemble-from-evidence path end to end against Postgres: real captured evidence →
 * selected → projected → ordered → hashed → persisted. This is v1.0.17 §3 working.
 */
class StateAssemblerTest extends AbstractPostgresIT {

    @Autowired
    private StateAssembler assembler;
    @Autowired
    private ArtifactService artifactService;
    @Autowired
    private AnalysisRepository analysis;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID sessionId;
    private String analysisId;

    @BeforeEach
    void seed() {
        sessionId = UUID.randomUUID();
        analysisId = "analysis-" + UUID.randomUUID();
        jdbc.update("insert into autwit.session(session_id, correlation_id, tester_id, env) values (?,?,?,?)",
                sessionId, "corr-" + sessionId, "priya", "qa2");
        analysis.createSession(new AnalysisSession(analysisId, sessionId, "ord-1", "LIFECYCLE_COMPARISON",
                null, 0, "pv-1", "rv-1", 0, null, null));
    }

    /** Persist a real artifact the way the worker would, so the assembler resolves a genuine body. */
    private UUID captureArtifact(String type, String source, String name, Map<String, Object> body) {
        return artifactService.persist(sessionId, null, null, null, type, source, name,
                ArtifactFormat.JSON, body, null, null, Map.of()).artifactId();
    }

    @Test
    void selectedArtifactsAreProjectedOrderedByTimeAndPersistedAsStates() {
        // Two order pictures captured; the "later" one has an earlier captured_at to prove
        // ordering is by time, not by selection order.
        var later = captureArtifact("api_response", "oms", "order-v2", Map.of("orderId", "XXXX", "total", "13.00"));
        var earlier = captureArtifact("api_response", "oms", "order-v1", Map.of("orderId", "XXXX", "total", "12.00"));
        // Force distinct capture times.
        jdbc.update("update autwit.artifact set captured_at = ? where artifact_id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-07-16T09:00:00Z")), earlier);
        jdbc.update("update autwit.artifact set captured_at = ? where artifact_id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-07-16T10:00:00Z")), later);

        var result = assembler.assemble(analysisId, List.of(
                EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, later),
                EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, earlier)));

        assertThat(result.persisted()).isEqualTo(2);
        assertThat(result.deduped()).isZero();
        // Sequence follows capture time: earlier=1, later=2, despite selection order.
        assertThat(result.states()).extracting(StateEnvelope::label).containsExactly("order-v1", "order-v2");
        assertThat(result.states()).extracting(StateEnvelope::sequence).containsExactly(1, 2);
        assertThat(analysis.listStates(analysisId)).extracting(AnalysisRepository.StoredState::label)
                .containsExactly("order-v1", "order-v2");
    }

    @Test
    void reAssemblingTheSameEvidenceDedupesRatherThanDuplicating() {
        var a = captureArtifact("api_response", "oms", "order", Map.of("orderId", "XXXX", "total", "12.00"));

        var first = assembler.assemble(analysisId, List.of(EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, a)));
        assertThat(first.persisted()).isEqualTo(1);

        var again = assembler.assemble(analysisId, List.of(EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, a)));
        assertThat(again.persisted()).as("unchanged evidence → same §6.1 hash → deduped").isZero();
        assertThat(again.deduped()).isEqualTo(1);
        assertThat(analysis.stateCount(analysisId)).isEqualTo(1);
    }

    @Test
    void aTesterOverrideFlowsThroughToThePersistedStateType() {
        // Captured as 'other'/shipment_pg → projects to OTHER/UNKNOWN, which the tester's
        // override then replaces — the point being that the override reaches storage.
        var a = captureArtifact("other", "shipment_pg", "shipments", Map.of("k", "v"));
        var ref = new EvidenceRef(EvidenceRef.Kind.ARTIFACT, a, "INVOICE_SNAPSHOT", "INVOICE_DB", null, "billing");

        assembler.assemble(analysisId, List.of(ref));

        var stored = analysis.listStates(analysisId).get(0);
        assertThat(stored.stateType()).isEqualTo("INVOICE_SNAPSHOT");
        assertThat(stored.source()).isEqualTo("INVOICE_DB");
        assertThat(stored.lifecycleStage()).isEqualTo("billing");
    }

    @Test
    void anEmptySelectionIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assembler.assemble(analysisId, List.of()))
                .isInstanceOf(com.autwit.copilot.common.ApiException.BadRequest.class);
    }
}
