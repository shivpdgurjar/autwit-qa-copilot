package com.autwit.copilot.planning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.autwit.copilot.orchestrator.OrchestratorClient;
import com.autwit.copilot.orchestrator.dto.ArtifactDescriptor;
import com.autwit.copilot.orchestrator.dto.Envelope;
import com.autwit.copilot.orchestrator.dto.InvokeRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The artifact-body extraction, which had no test at all and is exactly what the v2 upgrade
 * rewrites. The old client read five keys from the body and four per scenario, so anything
 * richer the generator produced was dropped here silently — the regression this pins is
 * "a field arrives on the wire and never reaches the database".
 *
 * <p>Drives the client against a recording stub of {@link OrchestratorClient} rather than a
 * socket: the transport itself is already covered by HttpOrchestratorClientTest, and what
 * matters here is the mapping either side of it.
 */
class HttpPlanningClientTest {

    /** Captures the input map the client sends and returns a canned envelope. */
    private static final class RecordingOrchestrator implements OrchestratorClient {
        private final Object body;
        Map<String, Object> lastInput;
        String lastSkill;

        RecordingOrchestrator(Object body) {
            this.body = body;
        }

        @Override
        public Catalog skills() {
            throw new UnsupportedOperationException("not used by the planning path");
        }

        @Override
        public Envelope invoke(InvokeRequest.Invoke request) {
            throw new UnsupportedOperationException("planning always calls execute by skill name");
        }

        @Override
        public Envelope execute(String skillName, InvokeRequest.Execute request) {
            this.lastSkill = skillName;
            this.lastInput = request.input();
            var artifact = new ArtifactDescriptor("pl1", "planning_test_plan", "planning_studio",
                    "test_plan", "json", body, null, null, null, null, null);
            return new Envelope("run-1", "succeeded", null, null, null, null, List.of(artifact),
                    null, null, null, null, null, null, null, null);
        }
    }

    /** LinkedHashMap throughout: Map.of caps at 10 pairs and rejects the null values a
     *  realistic body carries (doc_version, an absent automation target). */
    private static Map<String, Object> map(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> richBody() {
        return map(
                "overview", "Partial cancellation overview",
                "scope", map("in_scope", List.of("Line cancellation"), "out_of_scope", List.of("Returns")),
                "architecture_context", map("summary", "An overlay on the order lifecycle."),
                "requirements", List.of(map("id", "REQ-01", "statement", "Eligible lines cancel.",
                        "category", "functional", "sources", List.of("CAN-1201"),
                        "evidence", "Lines may be cancelled.", "lifecycle_phase", "Order capture")),
                "test_data_requirements", List.of(map("id", "DATA-01", "name", "Multi-line order",
                        "description", "Two eligible lines", "attributes", List.of("two lines"),
                        "source_of_truth", null)),
                "capabilities", List.of(map(
                        "name", "Eligibility",
                        "description", "Which lines may be cancelled.",
                        "test_cases", List.of(
                                map("id", "TC-01", "title", "Cancel one eligible line",
                                        "priority", "High", "objective", "Proves a line cancels.",
                                        "lifecycle_phase", "Order capture",
                                        "requirement_ids", List.of("REQ-01"),
                                        "sources", List.of("CAN-1201"),
                                        "preconditions", List.of("An order in CREATED"),
                                        "steps", List.of("Cancel line 1"),
                                        "expected_results", List.of("Line 1 is CANCELLED"),
                                        "test_data_requirements", List.of("DATA-01"),
                                        "automation_mapping", map("type", "api",
                                                "target", "POST /cancel", "notes", "facade")),
                                map("id", "TC-02", "title", "Reject a released line",
                                        "priority", "Medium",
                                        "objective", "Proves ineligible lines are refused.",
                                        "lifecycle_phase", null,
                                        "requirement_ids", List.of("REQ-01"),
                                        "sources", List.of("CAN-1201"),
                                        "preconditions", List.of("A released line"),
                                        "steps", List.of("Cancel it"),
                                        "expected_results", List.of("The request is rejected"),
                                        "test_data_requirements", List.of(),
                                        "automation_mapping", null)))),
                "execution_strategy", "Eligibility first.",
                "provenance", map("sources", List.of("CAN-1201"), "doc_version", null));
    }

    private static HttpPlanningClient clientFor(OrchestratorClient orchestrator) {
        return new HttpPlanningClient(orchestrator);
    }

    @Test
    void everyRichFieldSurvivesTheArtifactBody() {
        var orchestrator = new RecordingOrchestrator(richBody());

        var result = clientFor(orchestrator).generateTestPlan(new PlanningClient.TestPlanRequest(
                "CAN-1201", "partial cancellation", List.of(), List.of(), "oes", null));

        assertThat(orchestrator.lastSkill).isEqualTo("planning.generate_test_plan");
        assertThat(result.overview()).isEqualTo("Partial cancellation overview");
        assertThat(result.scope().inScope()).containsExactly("Line cancellation");
        assertThat(result.scope().outOfScope()).containsExactly("Returns");
        assertThat(result.architectureContext()).containsEntry("summary", "An overlay on the order lifecycle.");
        assertThat(result.executionStrategy()).isEqualTo("Eligibility first.");
        assertThat(result.requirements()).singleElement()
                .satisfies(r -> {
                    assertThat(r.id()).isEqualTo("REQ-01");
                    assertThat(r.sources()).containsExactly("CAN-1201");
                    assertThat(r.evidence()).isEqualTo("Lines may be cancelled.");
                });
        assertThat(result.testDataRequirements()).singleElement()
                .satisfies(d -> assertThat(d.attributes()).containsExactly("two lines"));

        assertThat(result.capabilities()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("Eligibility");
            assertThat(c.description()).isEqualTo("Which lines may be cancelled.");
            assertThat(c.testCases()).hasSize(2);
        });

        var tc1 = result.capabilities().get(0).testCases().get(0);
        assertThat(tc1.objective()).isEqualTo("Proves a line cancels.");
        assertThat(tc1.lifecyclePhase()).isEqualTo("Order capture");
        assertThat(tc1.preconditions()).containsExactly("An order in CREATED");
        assertThat(tc1.steps()).containsExactly("Cancel line 1");
        assertThat(tc1.expectedResults()).containsExactly("Line 1 is CANCELLED");
        assertThat(tc1.testDataRequirements()).containsExactly("DATA-01");
        assertThat(tc1.requirementIds()).containsExactly("REQ-01");
        assertThat(tc1.automationMapping()).containsEntry("type", "api");
    }

    @Test
    void absentOptionalFieldsStayNullRatherThanBecomingEmptyObjects() {
        var orchestrator = new RecordingOrchestrator(richBody());

        var result = clientFor(orchestrator).generateTestPlan(new PlanningClient.TestPlanRequest(
                "CAN-1201", "partial cancellation", List.of(), List.of(), null, null));

        var tc2 = result.capabilities().get(0).testCases().get(1);
        assertThat(tc2.automationMapping()).isNull();
        assertThat(tc2.lifecyclePhase()).isNull();
        assertThat(tc2.testDataRequirements()).isEmpty();
    }

    @Test
    void theWholeBodyIsKeptAsPayloadSoUnreadFieldsAreNotLost() {
        // The v1 client dropped everything outside its five keys. Keeping the body verbatim is
        // what makes a future field addition non-destructive.
        var body = new LinkedHashMap<>(richBody());
        body.put("some_field_added_later", "must survive");
        var orchestrator = new RecordingOrchestrator(body);

        var result = clientFor(orchestrator).generateTestPlan(new PlanningClient.TestPlanRequest(
                "CAN-1201", "d", List.of(), List.of(), null, null));

        assertThat(result.payload()).containsEntry("some_field_added_later", "must survive");
    }

    @Test
    void documentRolesAndTheDomainKeyAreSentOnTheWire() {
        var orchestrator = new RecordingOrchestrator(richBody());

        clientFor(orchestrator).generateTestPlan(new PlanningClient.TestPlanRequest(
                "CAN-1201", "partial cancellation",
                List.of(new PlanningClient.Doc("upload", "requirement", "Spec", "text")),
                List.of(new PlanningClient.Doc("upload", "existing_tests", "Old cases", "text")),
                "oes", null));

        assertThat(orchestrator.lastInput).containsEntry("domain", "oes");

        @SuppressWarnings("unchecked")
        var docs = (List<Map<String, Object>>) orchestrator.lastInput.get("source_documents");
        assertThat(docs).singleElement().satisfies(d -> assertThat(d).containsEntry("role", "requirement"));

        // The separation is the point: existing tests must arrive as their own input so the
        // generator treats them as evidence rather than rows to reproduce.
        @SuppressWarnings("unchecked")
        var existing = (List<Map<String, Object>>) orchestrator.lastInput.get("existing_test_cases");
        assertThat(existing).singleElement().satisfies(d -> assertThat(d).containsEntry("role", "existing_tests"));
    }

    @Test
    void anEmptyArtifactBodyDegradesToEmptyCollectionsRatherThanThrowing() {
        var orchestrator = new RecordingOrchestrator(Map.of());

        var result = clientFor(orchestrator).generateTestPlan(new PlanningClient.TestPlanRequest(
                "CAN-1201", "d", List.of(), List.of(), null, null));

        assertThat(result.capabilities()).isEmpty();
        assertThat(result.requirements()).isEmpty();
        assertThat(result.scope().inScope()).isEmpty();
        assertThat(result.architectureContext()).isNull();
    }
}
