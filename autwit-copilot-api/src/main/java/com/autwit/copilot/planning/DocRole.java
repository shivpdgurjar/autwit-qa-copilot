package com.autwit.copilot.planning;

/**
 * What a {@link SourceDocument} IS, as opposed to {@link SourceType}, which is where it came
 * from. The distinction matters because it changes how the generator must read the document:
 * requirements are the source of truth, architecture explains how the system is assembled,
 * and existing tests are evidence of intended coverage that must NOT be reproduced as new
 * cases. Without this the model can only guess from the title.
 *
 * <p>The wire form is the lowercase token stored in {@code source_document.doc_role} and sent
 * as {@code source_documents[].role} to the orchestrator.
 */
public enum DocRole {
    REQUIREMENT,
    ARCHITECTURE,
    EXISTING_TESTS,
    DOMAIN_RULES;

    public String wire() {
        return name().toLowerCase();
    }

    /** Unknown or absent falls back to REQUIREMENT — the same default the prompt applies. */
    public static DocRole fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return REQUIREMENT;
        }
        try {
            return valueOf(wire.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return REQUIREMENT;
        }
    }
}
