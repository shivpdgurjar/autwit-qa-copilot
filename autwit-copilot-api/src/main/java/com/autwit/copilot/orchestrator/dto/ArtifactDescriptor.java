package com.autwit.copilot.orchestrator.dto;

import java.util.Map;

/**
 * SKILL_CONTRACT §6.1.
 *
 * @param clientRef    a within-response handle so snapshots[].parts[] can point at
 *                     artifacts before real UUIDs exist. copilot-api assigns the
 *                     artifact_id.
 * @param body         shape follows format: parsed JSON for json, a string for
 *                     xml/text/csv/html/md, base64 for binary.
 * @param externalUri  SKILL_CONTRACT §7, v0.2. Present and unused today, deliberately:
 *                     BUILD_BRIEF §10 asks for the field now so that adding pre-signed
 *                     URIs later is not a contract renegotiation. Mutually exclusive
 *                     with body, mirroring the DB's one_body check. copilot-api
 *                     rejects it for now rather than half-supporting it.
 * @param contentHash  sha256 over the canonical body. Recomputed and rejected on
 *                     mismatch — this catches truncation. See
 *                     CONTRACT_RATIFICATION_REQUEST.md Q1: "canonical" is not yet
 *                     defined by the contract.
 */
public record ArtifactDescriptor(
        String clientRef,
        String artifactType,
        String sourceSystem,
        String logicalName,
        String format,
        Object body,
        String externalUri,
        Long sizeBytes,
        Integer rowCount,
        String contentHash,
        Map<String, Object> meta) {
}
