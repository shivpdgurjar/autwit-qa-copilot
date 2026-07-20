package com.autwit.copilot.analysis;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.Artifact;
import com.autwit.copilot.artifact.ArtifactFormat;
import com.autwit.copilot.events.EventRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assemble-from-evidence projection (v1.0.17 §3). Pure, so no Postgres.
 *
 * <p>The load-bearing behaviours: inference defaults to OTHER/UNKNOWN rather than
 * guessing, a tester override always wins, and the payload is carried verbatim (it is
 * evidence — the §6.1 hash later depends on it being untouched).
 */
class EvidenceProjectorTest {

    private final EvidenceProjector projector = new EvidenceProjector();

    private static EventRecord event(String source, String type, String key,
            Instant occurredAt, Map<String, Object> payload) {
        return new EventRecord(UUID.randomUUID(), UUID.randomUUID(), null, source, "order.events",
                type, key, "1703000000000", occurredAt, Instant.parse("2026-07-16T09:15:03Z"),
                null, payload);
    }

    private static Artifact artifact(String artifactType, String sourceSystem, String logicalName,
            Object body) {
        return new Artifact(UUID.randomUUID(), UUID.randomUUID(), null, null, null,
                artifactType, sourceSystem, logicalName, ArtifactFormat.JSON, 0, null,
                "sha256:x", Instant.parse("2026-07-16T09:14:00Z"), null, true, Map.of(), body);
    }

    // ---- events ----------------------------------------------------------------------

    @Test
    void anEventProjectsToADomainEventCarryingItsPayload() {
        var payload = Map.<String, Object>of("orderId", "XXXX", "amount", "12.00");
        var state = projector.projectEvent(
                event("eventstore", "PaymentCaptured", "XXXX", Instant.parse("2026-07-16T09:10:00Z"), payload),
                EvidenceRef.of(EvidenceRef.Kind.EVENT, UUID.randomUUID()));

        assertThat(state.stateType()).isEqualTo(StateType.DOMAIN_EVENT);
        assertThat(state.source()).isEqualTo(SourceSystem.KAFKA_EVENT);
        assertThat(state.label()).isEqualTo("PaymentCaptured:XXXX");
        assertThat(state.data()).isEqualTo(payload);
        assertThat(state.capturedAt()).as("business time, not capture time")
                .isEqualTo(Instant.parse("2026-07-16T09:10:00Z"));
        assertThat(state.lifecycleStage()).isEqualTo("unspecified");
        assertThat(state.sequence()).as("provisional; the assembler stamps the real one").isZero();
    }

    @Test
    void anEventWithoutBusinessTimeFallsBackToCaptureTime() {
        var state = projector.projectEvent(
                event("eventstore", "OrderCreated", "XXXX", null, Map.of()),
                EvidenceRef.of(EvidenceRef.Kind.EVENT, UUID.randomUUID()));
        assertThat(state.capturedAt()).isEqualTo(Instant.parse("2026-07-16T09:15:03Z"));
    }

    @Test
    void anUnknownEventSourceIsUnknownNotGuessed() {
        var state = projector.projectEvent(
                event("some_bus", "Thing", "k", Instant.now(), Map.of()),
                EvidenceRef.of(EvidenceRef.Kind.EVENT, UUID.randomUUID()));
        assertThat(state.source()).isEqualTo(SourceSystem.UNKNOWN);
    }

    // ---- artifacts -------------------------------------------------------------------

    @Test
    void anApiResponseArtifactProjectsToApiResponseFromOrderDb() {
        var body = Map.<String, Object>of("orderId", "XXXX", "status", "CREATED");
        var state = projector.projectArtifact(
                artifact("api_response", "oms", "order", body),
                EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, UUID.randomUUID()));

        assertThat(state.stateType()).isEqualTo(StateType.API_RESPONSE);
        assertThat(state.source()).isEqualTo(SourceSystem.ORDER_DB);
        assertThat(state.label()).isEqualTo("order");
        assertThat(state.data()).isEqualTo(body);
    }

    @Test
    void anrdbmsTableProjectsToAnOrderSnapshot() {
        var state = projector.projectArtifact(
                artifact("rdbms_table", "invoice_pg", "invoices", Map.of()),
                EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, UUID.randomUUID()));
        assertThat(state.stateType()).isEqualTo(StateType.ORDER_SNAPSHOT);
        assertThat(state.source()).isEqualTo(SourceSystem.INVOICE_DB);
    }

    @Test
    void anUnmappedArtifactIsOtherAndUnknown() {
        var state = projector.projectArtifact(
                artifact("mystery", "shipment_pg", "shipments", Map.of()),
                EvidenceRef.of(EvidenceRef.Kind.ARTIFACT, UUID.randomUUID()));
        assertThat(state.stateType()).isEqualTo(StateType.OTHER);
        assertThat(state.source()).isEqualTo(SourceSystem.UNKNOWN);
    }

    // ---- overrides always win --------------------------------------------------------

    @Test
    void aTesterOverrideBeatsInference() {
        var ref = new EvidenceRef(EvidenceRef.Kind.ARTIFACT, UUID.randomUUID(),
                "PAYMENT_SNAPSHOT", "PAYMENT_DB", "my label", "post-payment");
        var state = projector.projectArtifact(artifact("mystery", "shipment_pg", "shipments", Map.of()), ref);

        assertThat(state.stateType()).isEqualTo(StateType.PAYMENT_SNAPSHOT);
        assertThat(state.source()).isEqualTo(SourceSystem.PAYMENT_DB);
        assertThat(state.label()).isEqualTo("my label");
        assertThat(state.lifecycleStage()).isEqualTo("post-payment");
    }

    @Test
    void aGarbageOverrideFallsBackToInferenceRatherThanBreaking() {
        var ref = new EvidenceRef(EvidenceRef.Kind.ARTIFACT, UUID.randomUUID(),
                "NONSENSE", "ALSO_NONSENSE", null, null);
        var state = projector.projectArtifact(artifact("api_response", "oms", "order", Map.of()), ref);

        assertThat(state.stateType()).as("unparseable override → inferred").isEqualTo(StateType.API_RESPONSE);
        assertThat(state.source()).isEqualTo(SourceSystem.ORDER_DB);
    }
}
