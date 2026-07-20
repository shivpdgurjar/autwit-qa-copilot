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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
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
                .andExpect(status().isOk())
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
                .andExpect(status().isOk())
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.states[0].state_type", is("INVOICE_SNAPSHOT")))
                .andExpect(jsonPath("$.states[0].source", is("INVOICE_DB")))
                .andExpect(jsonPath("$.states[0].lifecycle_stage", is("billing")));
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
