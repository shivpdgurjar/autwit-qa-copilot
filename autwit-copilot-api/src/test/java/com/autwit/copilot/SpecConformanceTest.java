package com.autwit.copilot;

import java.util.Map;
import java.util.Set;

import com.autwit.copilot.run.Run;
import com.autwit.copilot.session.Session;
import com.autwit.copilot.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API must not emit fields docs/openapi.yaml does not declare.
 *
 * <p>The UI generates its entire client from that spec (BUILD_BRIEF §3: "Do not
 * hand-write API types in the UI"), so a field the spec does not know about is a field
 * the UI cannot see. Undocumented output is drift, not a bonus — and the drift here
 * arrived by accident rather than intent: Jackson serialises any isFoo() helper on a
 * record as a "foo" property, so adding one convenience method to a DTO silently
 * changes the wire format.
 *
 * <p>This test is cheap and catches that the moment it happens.
 */
class SpecConformanceTest extends AbstractPostgresIT {

    @Autowired
    private ObjectMapper mapper;

    /** openapi.yaml Session.properties. */
    private static final Set<String> SESSION_FIELDS = Set.of(
            "session_id", "correlation_id", "tester_id", "env", "title", "status",
            "retention_class", "started_at", "ended_at", "expires_at", "tags", "subjects");

    /** openapi.yaml Run.properties. */
    private static final Set<String> RUN_FIELDS = Set.of(
            "run_id", "session_id", "step_id", "run_type", "status", "max_attempts", "request",
            "progress", "result_summary", "error", "attempts", "cancel_requested",
            "idempotency_key", "queued_at", "started_at", "ended_at", "elapsed_ms");

    @Test
    void sessionEmitsOnlyDocumentedFields() {
        var session = new Session(java.util.UUID.randomUUID(), "autwit-qa2-x", "priya", "qa2", "t",
                "active", "standard", java.time.Instant.now(), null, java.time.Instant.now(),
                Map.of(), Map.of("order_id", "XXXX"));

        // isActive() would otherwise appear as "active".
        assertThat(fieldsOf(session)).isSubsetOf(SESSION_FIELDS);
    }

    @Test
    void runEmitsOnlyDocumentedFieldsAndNoQueueInternals() {
        var run = new Run(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "invoke", "running", Map.of(), Map.of(), null, null,
                1, 1, false, null, java.time.Instant.now(), "worker-1", java.time.Instant.now(),
                java.time.Instant.now(), null, 42L);

        var fields = fieldsOf(run);

        assertThat(fields).isSubsetOf(RUN_FIELDS);
        // How the queue works is not the client's business: a UI that can see the lease
        // will eventually start doing arithmetic with it.
        assertThat(fields).doesNotContain("lease_until", "worker_id", "terminal");
    }

    @Test
    void theFieldsTheUiNeedsAreActuallyPresent() {
        var run = new Run(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "invoke", "running", Map.of(), Map.of(), null, null,
                1, 1, false, null, null, null, java.time.Instant.now(), null, null, null);

        // max_attempts=1 is how the UI knows a retry is the tester's call, not automatic.
        assertThat(fieldsOf(run)).contains("run_type", "max_attempts", "attempts");
    }

    @SuppressWarnings("unchecked")
    private Set<String> fieldsOf(Object value) {
        return mapper.convertValue(value, Map.class).keySet();
    }
}
