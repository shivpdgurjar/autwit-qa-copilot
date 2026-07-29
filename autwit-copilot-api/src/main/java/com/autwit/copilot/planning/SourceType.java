package com.autwit.copilot.planning;

/**
 * Where a {@link SourceDocument} came from. Uploads and pastes are tester-provided; jira and
 * confluence are fetched over MCP by the orchestrator (v1.0.26 §4.3) and handed to us as text.
 * The wire form is the lowercase token stored in {@code source_document.source_type}.
 */
public enum SourceType {
    UPLOAD,
    PASTE,
    JIRA,
    CONFLUENCE;

    public String wire() {
        return name().toLowerCase();
    }

    public static SourceType fromWire(String wire) {
        return valueOf(wire.trim().toUpperCase());
    }
}
