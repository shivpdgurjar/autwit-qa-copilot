package com.autwit.copilot.orchestrator;

import java.util.List;
import java.util.Map;

import com.autwit.copilot.orchestrator.dto.Envelope;
import com.autwit.copilot.orchestrator.dto.InvokeRequest;

/**
 * The port. BUILD_BRIEF §4: "OrchestratorClient as a port with @Profile("fake") is
 * what lets everything except step 8 be built and tested before the other session
 * ships anything."
 *
 * <p>The orchestrator returns results and never calls back (invariant 1), so this is
 * the entire outbound surface between the two services. There is no inbound one.
 */
public interface OrchestratorClient {

    /** §3. LLM-driven: picks and runs skills. */
    Envelope invoke(InvokeRequest.Invoke request);

    /** §4. Direct, no LLM — what the ⌘K palette and CI use. */
    Envelope execute(String skillName, InvokeRequest.Execute request);

    /** §2. Polled into autwit.skill as a read-only projection. */
    Catalog skills();

    record Catalog(String catalogVersion, List<Skill> skills) {
    }

    record Skill(
            String skillName,
            String version,
            String title,
            String description,
            String implType,
            String sideEffects,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            Boolean enabled) {

        /** Drives max_attempts at enqueue (ADR-001). */
        public boolean isMutating() {
            return "mutating".equals(sideEffects);
        }
    }
}
