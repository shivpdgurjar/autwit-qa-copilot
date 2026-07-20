package com.autwit.copilot.analysis;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import com.autwit.copilot.artifact.ArtifactFormat;
import com.autwit.copilot.artifact.ArtifactService;
import com.autwit.copilot.artifact.ContentHasher;
import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.events.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a tester's selection of session evidence into the ordered, hashed, persisted
 * states an analysis runs over. The centre of the assemble-from-evidence design
 * (v1.0.17 §3): resolve refs → project → order → hash → persist.
 *
 * <p>What it deliberately does NOT do: call the skill. Sending the assembled states to the
 * orchestrator and handling the response is the next seam, and it waits on the
 * orchestrator confirming the request wire schema (v1.0.17 §4.1). Assembly is complete and
 * verifiable on its own — the states exist, hashed and persisted, before any call.
 */
@Service
public class StateAssembler {

    private final EventRepository events;
    private final ArtifactService artifacts;
    private final ContentHasher hasher;
    private final AnalysisRepository analysis;
    private final EvidenceProjector projector;

    public StateAssembler(EventRepository events, ArtifactService artifacts,
            ContentHasher hasher, AnalysisRepository analysis, EvidenceProjector projector) {
        this.events = events;
        this.artifacts = artifacts;
        this.hasher = hasher;
        this.analysis = analysis;
        this.projector = projector;
    }

    /** The assembled states, in the order and with the sequence numbers they were persisted under. */
    public record Assembled(List<StateEnvelope> states, int persisted, int deduped) {
    }

    /**
     * Resolve and persist the selected evidence as {@code analysis_state} rows.
     *
     * <p>Ordering is by {@link StateEnvelope#capturedAt()} — the sequence a lifecycle
     * validates transitions in. Nulls sort last (an untimestamped state cannot claim a
     * position among timed ones), and label breaks ties for determinism. The final 1..N
     * sequence is stamped after ordering, then each payload is hashed under the §6.1
     * canonical form (nulls retained — the V7/V8 fix) so a re-selection of unchanged
     * evidence dedupes against {@code UNIQUE(analysis_id, payload_hash)}.
     *
     * <p>Transactional: the whole selection lands or none of it does. A partial analysis
     * would be evidence about nothing.
     */
    @Transactional
    public Assembled assemble(String analysisId, List<EvidenceRef> refs) {
        if (refs.isEmpty()) {
            throw new ApiException.BadRequest("no_states",
                    "An analysis needs at least one selected piece of evidence.");
        }

        var ordered = refs.stream()
                .map(this::project)
                .sorted(Comparator.comparing(StateEnvelope::capturedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(StateEnvelope::label))
                .toList();

        int persisted = 0;
        int deduped = 0;
        var stamped = IntStream.range(0, ordered.size())
                .mapToObj(i -> ordered.get(i).withSequence(i + 1))
                .toList();

        for (var state : stamped) {
            var canonicalJson = hasher.canonicalJson(state.data());
            var payloadHash = hasher.hashBytes(hasher.canonicalBytes(ArtifactFormat.JSON, state.data()));
            if (analysis.appendState(analysisId, state, payloadHash, canonicalJson)) {
                persisted++;
            } else {
                deduped++;
            }
        }
        return new Assembled(stamped, persisted, deduped);
    }

    /** Resolve one ref to its evidence and project it. 404 if the evidence is gone. */
    private StateEnvelope project(EvidenceRef ref) {
        return switch (ref.kind()) {
            case EVENT -> {
                var event = events.findById(ref.id())
                        .orElseThrow(() -> new ApiException.NotFound("event", ref.id()));
                yield projector.projectEvent(event, ref);
            }
            case ARTIFACT -> {
                // get() loads the body and resolves 404 vs 410 (a purged body cannot be
                // analysed — its evidence is gone, and that must be an error, not a null
                // state that silently weakens the verdict).
                var artifact = artifacts.get(ref.id());
                yield projector.projectArtifact(artifact, ref);
            }
        };
    }
}
