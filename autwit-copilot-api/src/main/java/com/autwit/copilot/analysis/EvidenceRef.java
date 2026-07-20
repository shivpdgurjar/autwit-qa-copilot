package com.autwit.copilot.analysis;

import java.util.UUID;

/**
 * A tester's selection of one piece of session evidence to analyse, with optional
 * overrides. This is what the UI evidence-picker sends — a <em>reference</em> to persisted
 * evidence, not a hand-keyed state (v1.0.17 §3).
 *
 * <p>The overrides exist because the projection <em>infers</em> {@link StateType} and
 * {@link SourceSystem}, and the financial rules mis-fire on a wrong tag. So the picker
 * shows the inferred values and lets the tester correct them; a supplied override wins
 * over inference. {@code lifecycleStage} has no inference at all — it is the tester's
 * label for where in the order's life this state sits — so it is supplied here or
 * defaulted.
 *
 * @param kind             which table {@code id} lives in.
 * @param id               the {@code event_record.event_id} or {@code artifact.artifact_id}.
 * @param stateTypeOverride null to accept the inferred type.
 * @param sourceOverride    null to accept the inferred source.
 * @param labelOverride     null to accept a derived label.
 * @param lifecycleStage    the tester's stage tag; null defaults to "unspecified".
 */
public record EvidenceRef(
        Kind kind,
        UUID id,
        String stateTypeOverride,
        String sourceOverride,
        String labelOverride,
        String lifecycleStage) {

    public enum Kind {
        /** An {@code autwit.event_record} row — a captured domain event. */
        EVENT,
        /** An {@code autwit.artifact} — a fetch_order response, a snapshot part, an upload. */
        ARTIFACT
    }

    /** A bare reference with no overrides — accept every inferred value. */
    public static EvidenceRef of(Kind kind, UUID id) {
        return new EvidenceRef(kind, id, null, null, null, null);
    }
}
