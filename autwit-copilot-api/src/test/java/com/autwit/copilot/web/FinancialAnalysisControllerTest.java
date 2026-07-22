package com.autwit.copilot.web;

import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.ArtifactFormat;
import com.autwit.copilot.artifact.ArtifactService;
import com.autwit.copilot.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The evidence-picker's submit endpoint: POST /sessions/{id}/analyses assembles selected
 * evidence into analysis states (no skill call yet).
 */
@AutoConfigureMockMvc
class FinancialAnalysisControllerTest extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ArtifactService artifacts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private UUID sessionId;

    @BeforeEach
    void seed() {
        sessionId = UUID.randomUUID();
        jdbc.update("insert into autwit.session(session_id, correlation_id, tester_id, env) values (?,?,?,?)",
                sessionId, "corr-" + sessionId, "priya", "qa2");
    }

    private UUID captureArtifact(String logicalName, Map<String, Object> body) {
        return artifacts.persist(sessionId, null, null, null, "api_response", "oms", logicalName,
                ArtifactFormat.JSON, body, null, null, Map.of()).artifactId();
    }

    @Test
    void assemblesASnapshotFromOneSelectedArtifact() throws Exception {
        var a = captureArtifact("order", Map.of("orderId", "XXXX", "total", "12.00"));

        mvc.perform(post("/sessions/{id}/analyses", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analysis_mode":"SNAPSHOT_SANCTITY","order_number":"XXXX",
                                 "states":[{"kind":"ARTIFACT","id":"%s"}]}
                                """.formatted(a)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.analysis_id", notNullValue()))
                .andExpect(jsonPath("$.persisted", is(1)))
                .andExpect(jsonPath("$.states[0].sequence", is(1)))
                .andExpect(jsonPath("$.states[0].state_type", is("API_RESPONSE")))
                .andExpect(jsonPath("$.states[0].source", is("ORDER_DB")));
    }

    @Test
    void snapshotRejectsMoreThanOneState() throws Exception {
        var a = captureArtifact("order-v1", Map.of("orderId", "XXXX", "total", "12.00"));
        var b = captureArtifact("order-v2", Map.of("orderId", "XXXX", "total", "13.00"));

        mvc.perform(post("/sessions/{id}/analyses", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analysis_mode":"SNAPSHOT_SANCTITY","order_number":"XXXX",
                                 "states":[{"kind":"ARTIFACT","id":"%s"},{"kind":"ARTIFACT","id":"%s"}]}
                                """.formatted(a, b)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("too_many_states")));
    }

    @Test
    void lifecycleAssemblesMultipleStatesInCaptureOrder() throws Exception {
        var a = captureArtifact("order-v1", Map.of("orderId", "XXXX", "total", "12.00"));
        var b = captureArtifact("order-v2", Map.of("orderId", "XXXX", "total", "13.00"));

        var body = mvc.perform(post("/sessions/{id}/analyses", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analysis_mode":"LIFECYCLE_COMPARISON","order_number":"XXXX",
                                 "states":[{"kind":"ARTIFACT","id":"%s"},{"kind":"ARTIFACT","id":"%s"}]}
                                """.formatted(a, b)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.persisted", is(2)))
                .andReturn().getResponse().getContentAsString();

        // Both states landed, sequenced 1 and 2.
        var states = json.readTree(body).get("states");
        org.assertj.core.api.Assertions.assertThat(states.size()).isEqualTo(2);
    }

    @Test
    void aTesterOverrideIsHonoured() throws Exception {
        var a = captureArtifact("order", Map.of("orderId", "XXXX"));

        mvc.perform(post("/sessions/{id}/analyses", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analysis_mode":"SNAPSHOT_SANCTITY","order_number":"XXXX",
                                 "states":[{"kind":"ARTIFACT","id":"%s",
                                            "state_type":"INVOICE_SNAPSHOT","source":"INVOICE_DB",
                                            "lifecycle_stage":"billing"}]}
                                """.formatted(a)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.states[0].state_type", is("INVOICE_SNAPSHOT")))
                .andExpect(jsonPath("$.states[0].source", is("INVOICE_DB")))
                .andExpect(jsonPath("$.states[0].lifecycle_stage", is("billing")));
    }

    private String createSnapshot(UUID artifactId) throws Exception {
        var body = mvc.perform(post("/sessions/{id}/analyses", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analysis_mode":"SNAPSHOT_SANCTITY","order_number":"XXXX",
                                 "states":[{"kind":"ARTIFACT","id":"%s"}]}
                                """.formatted(artifactId)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("analysis_id").asText();
    }

    @Test
    void listsTheSessionsAnalyses() throws Exception {
        createSnapshot(captureArtifact("order", Map.of("orderId", "XXXX", "total", "12.00")));

        mvc.perform(get("/sessions/{id}/analyses", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyses", hasSize(1)))
                .andExpect(jsonPath("$.analyses[0].analysis_mode", is("SNAPSHOT_SANCTITY")))
                .andExpect(jsonPath("$.analyses[0].state_count", is(1)))
                // Never run in this test, so no OpenAI response yet — not chainable.
                .andExpect(jsonPath("$.analyses[0].chainable", is(false)));
    }

    @Test
    void chainingToANotYetRunAnalysisIs400() throws Exception {
        var prior = createSnapshot(captureArtifact("order-a", Map.of("orderId", "XXXX", "total", "12.00")));
        var b = captureArtifact("order-b", Map.of("orderId", "XXXX", "total", "13.00"));

        mvc.perform(post("/sessions/{id}/analyses", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analysis_mode":"SNAPSHOT_SANCTITY","order_number":"XXXX",
                                 "states":[{"kind":"ARTIFACT","id":"%s"}],
                                 "previous_analysis_id":"%s"}
                                """.formatted(b, prior)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("not_chainable")));
    }

    @Test
    void chainingToAnUnknownAnalysisIs400() throws Exception {
        var a = captureArtifact("order", Map.of("orderId", "XXXX", "total", "12.00"));

        mvc.perform(post("/sessions/{id}/analyses", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analysis_mode":"SNAPSHOT_SANCTITY","order_number":"XXXX",
                                 "states":[{"kind":"ARTIFACT","id":"%s"}],
                                 "previous_analysis_id":"analysis-does-not-exist"}
                                """.formatted(a)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("unknown_previous_analysis")));
    }

    @Test
    void anEmptySelectionIsRejected() throws Exception {
        mvc.perform(post("/sessions/{id}/analyses", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"analysis_mode":"SNAPSHOT_SANCTITY","order_number":"XXXX","states":[]}
                                """))
                .andExpect(status().isBadRequest());
    }
}
