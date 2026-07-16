package com.autwit.copilot.orchestrator.dto;

import java.util.List;
import java.util.Map;

/** SKILL_CONTRACT §6.2. */
public record SnapshotDescriptor(
        String clientRef,
        String label,
        String scope,
        Map<String, Object> scopeDef,
        String status,
        List<Part> parts) {

    /**
     * @param partKey     MUST be stable across snapshots of the same scope. Comparison
     *                    is a key-wise join on it, so drift does not throw — it reports
     *                    one part removed and one added, as two high findings, and a
     *                    tester reads that as a product bug. See
     *                    CONTRACT_RATIFICATION_REQUEST.md Q3.
     * @param artifactRef the client_ref of an artifact in the same envelope.
     */
    public record Part(String partKey, String artifactRef) {
    }

    public List<Part> partsOrEmpty() {
        return parts == null ? List.of() : parts;
    }
}
