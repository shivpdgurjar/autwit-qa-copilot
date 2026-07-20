package com.autwit.copilot.analysis;

import java.time.Instant;

/**
 * One analysis state, as sent to the orchestrator's financial skill and persisted in
 * {@code analysis_state}. Mirrors their {@code StateEnvelope}
 * ({@code financial/domain/types.ts}).
 *
 * <p>The important design point (v1.0.17 §3): copilot-api does not receive these from the
 * tester — it <em>assembles</em> them by projecting persisted session evidence
 * ({@code event_record} rows, {@code artifact} bodies). {@code data} is therefore a copy
 * of evidence the session already holds, and its §6.1 hash is what makes a re-analysis of
 * unchanged evidence idempotent.
 *
 * @param sequence  ordering within the analysis; assigned by the assembler, not the
 *                  projector, because it is a property of the set rather than one item.
 * @param data      the raw evidence payload. UNTRUSTED — never interpreted as
 *                  instructions (the orchestrator's §13 prompt-injection guard). We only
 *                  carry it.
 */
public record StateEnvelope(
        int sequence,
        String label,
        StateType stateType,
        String lifecycleStage,
        SourceSystem source,
        Instant capturedAt,
        String correlationId,
        Object data) {

    /**
     * A copy at a new sequence. The projector builds each state with a provisional
     * sequence; the assembler sorts the set (by {@link #capturedAt}) and stamps the final
     * 1..N here.
     */
    public StateEnvelope withSequence(int newSequence) {
        return new StateEnvelope(newSequence, label, stateType, lifecycleStage, source,
                capturedAt, correlationId, data);
    }
}
