package com.autwit.copilot.analysis;

import org.springframework.stereotype.Component;

import com.autwit.copilot.artifact.Artifact;
import com.autwit.copilot.events.EventRecord;

/**
 * Projects one piece of persisted session evidence into a {@link StateEnvelope}.
 *
 * <p>This is the heart of the assemble-from-evidence design (v1.0.17 §3): the tester
 * selects evidence the session already captured, and this turns each selection into a
 * state the orchestrator's financial engine can read. Pure — a function of its inputs,
 * no repository, no clock — so it is exhaustively unit-testable and the assembler owns all
 * the I/O.
 *
 * <h2>Inference is deliberately conservative</h2>
 *
 * {@link StateType} and {@link SourceSystem} are inferred from what the artifact/event
 * records about itself, and the inference defaults to {@code OTHER} / {@code UNKNOWN}
 * rather than guessing. A wrong tag makes the financial rules mis-fire, so a confident
 * wrong guess is worse than an honest "don't know" the tester can correct. Every inferred
 * value is overridable via {@link EvidenceRef}; a supplied override always wins.
 *
 * <p>The exact tag→which-rules-apply mapping is the orchestrator's (requested in v1.0.17
 * §4.2). Until it lands these heuristics are a starting point, not a claim of correctness
 * — which is exactly why the tester can override.
 *
 * <p>{@code sequence} is left at 0 here. Ordering is a property of the whole selected set,
 * so the assembler sorts by {@link StateEnvelope#capturedAt()} and stamps the final
 * sequence.
 */
@Component
public class EvidenceProjector {

    private static final String DEFAULT_STAGE = "unspecified";

    /** A captured domain event → a state. {@code data} is the event payload. */
    public StateEnvelope projectEvent(EventRecord event, EvidenceRef ref) {
        return new StateEnvelope(
                0,
                label(ref, eventLabel(event)),
                stateType(ref, StateType.DOMAIN_EVENT),
                stage(ref),
                source(ref, inferEventSource(event.source())),
                // Business time first; the event happened when it happened, not when we
                // captured it. Fall back to capture time only if the source omitted it.
                event.occurredAt() != null ? event.occurredAt() : event.capturedAt(),
                null,
                event.payload());
    }

    /**
     * An artifact → a state. {@code data} is the artifact body, so the caller must have
     * loaded the artifact <em>with its body</em> — a metadata-only load projects a null
     * payload, which is a different (and wrong) claim about the evidence.
     */
    public StateEnvelope projectArtifact(Artifact artifact, EvidenceRef ref) {
        return new StateEnvelope(
                0,
                label(ref, artifactLabel(artifact)),
                stateType(ref, inferArtifactType(artifact.artifactType())),
                stage(ref),
                source(ref, inferArtifactSource(artifact.sourceSystem())),
                artifact.capturedAt(),
                null,
                artifact.body());
    }

    // ---- override-or-infer resolution -------------------------------------------------

    private static StateType stateType(EvidenceRef ref, StateType inferred) {
        return StateType.parse(ref.stateTypeOverride()).orElse(inferred);
    }

    private static SourceSystem source(EvidenceRef ref, SourceSystem inferred) {
        return SourceSystem.parse(ref.sourceOverride()).orElse(inferred);
    }

    private static String stage(EvidenceRef ref) {
        return blankToNull(ref.lifecycleStage()) != null ? ref.lifecycleStage().trim() : DEFAULT_STAGE;
    }

    private static String label(EvidenceRef ref, String derived) {
        var override = blankToNull(ref.labelOverride());
        return override != null ? override : derived;
    }

    // ---- inference heuristics (overridable; refine once §4.2 lands) --------------------

    private static StateType inferArtifactType(String artifactType) {
        if (artifactType == null) {
            return StateType.OTHER;
        }
        return switch (artifactType) {
            case "api_response" -> StateType.API_RESPONSE;
            case "event_batch" -> StateType.DOMAIN_EVENT;
            case "rdbms_table", "snapshot" -> StateType.ORDER_SNAPSHOT;
            default -> StateType.OTHER;
        };
    }

    private static SourceSystem inferArtifactSource(String sourceSystem) {
        if (sourceSystem == null) {
            return SourceSystem.UNKNOWN;
        }
        var s = sourceSystem.toLowerCase();
        if (s.contains("invoice")) {
            return SourceSystem.INVOICE_DB;
        }
        if (s.contains("payment")) {
            return SourceSystem.PAYMENT_DB;
        }
        if (s.contains("eventstore") || s.contains("kafka")) {
            return SourceSystem.KAFKA_EVENT;
        }
        if (s.contains("oms") || s.startsWith("order")) {
            return SourceSystem.ORDER_DB;
        }
        return SourceSystem.UNKNOWN;
    }

    private static SourceSystem inferEventSource(String source) {
        if (source == null) {
            return SourceSystem.UNKNOWN;
        }
        var s = source.toLowerCase();
        // A captured event stream is the closest named system to a domain event.
        return (s.contains("eventstore") || s.contains("kafka")) ? SourceSystem.KAFKA_EVENT : SourceSystem.UNKNOWN;
    }

    private static String eventLabel(EventRecord event) {
        var type = blankToNull(event.eventType());
        var key = blankToNull(event.eventKey());
        if (type != null && key != null) {
            return type + ":" + key;
        }
        if (type != null) {
            return type;
        }
        return "event:" + event.eventId();
    }

    private static String artifactLabel(Artifact artifact) {
        var name = blankToNull(artifact.logicalName());
        if (name != null) {
            return name;
        }
        var type = blankToNull(artifact.artifactType());
        return type != null ? type : "artifact:" + artifact.artifactId();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
