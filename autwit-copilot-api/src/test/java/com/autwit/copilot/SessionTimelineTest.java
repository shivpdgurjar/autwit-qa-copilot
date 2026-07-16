package com.autwit.copilot;

import com.autwit.copilot.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Build order step 2's done-when: "can POST an artifact, GET a timeline".
 *
 * <p>No @Transactional. Each test makes its own session and isolates by data rather
 * than by rollback: a service method that throws would mark a test-managed
 * transaction rollback-only and poison every assertion after it, which is exactly
 * what the error-path tests here do.
 */
@AutoConfigureMockMvc
class SessionTimelineTest extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private String createSession() throws Exception {
        var body = mvc.perform(post("/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tester_id":"priya","env":"qa2","title":"order flow",
                                 "subjects":{"order_id":"XXXX"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.session_id", notNullValue()))
                .andExpect(jsonPath("$.status", is("active")))
                .andReturn().getResponse().getContentAsString();

        return json.readTree(body).get("session_id").asText();
    }

    @Test
    void createsASessionWithAGeneratedCorrelationId() throws Exception {
        mvc.perform(post("/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tester_id":"priya","env":"qa2"}
                                """))
                .andExpect(status().isCreated())
                // Propagated downstream as X-Autwit-Correlation-Id (SKILL_CONTRACT §3).
                .andExpect(jsonPath("$.correlation_id", startsWith("autwit-qa2-")))
                .andExpect(jsonPath("$.retention_class", is("standard")))
                .andExpect(jsonPath("$.expires_at", notNullValue()));
    }

    @Test
    void rejectsASessionWithoutATester() throws Exception {
        mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"env":"qa2"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("validation_failed")))
                .andExpect(jsonPath("$.errors[0].field", is("testerId")));
    }

    @Test
    void postsAnArtifactAndReadsItBack() throws Exception {
        var sessionId = createSession();

        var created = mvc.perform(post("/sessions/{id}/artifacts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "artifact_type": "rdbms_table",
                                  "source_system": "oms_pg",
                                  "logical_name": "orders",
                                  "format": "json",
                                  "row_count": 1,
                                  "body": [{"order_id":"XXXX","status":"CREATED","total_amount":1200.00}],
                                  "meta": {"pk_columns":["order_id"],"ignore_columns":["updated_at"]}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.artifact_id", notNullValue()))
                // Computed server-side when the client does not supply one.
                .andExpect(jsonPath("$.content_hash", startsWith("sha256:")))
                .andExpect(jsonPath("$.body_available", is(true)))
                .andExpect(jsonPath("$.meta.pk_columns[0]", is("order_id")))
                .andReturn().getResponse().getContentAsString();

        var artifactId = json.readTree(created).get("artifact_id").asText();

        mvc.perform(get("/artifacts/{id}", artifactId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logical_name", is("orders")))
                // Scale must survive the jsonb round trip, or every money diff is wrong.
                .andExpect(jsonPath("$.body[0].total_amount", is(1200.00)))
                .andExpect(jsonPath("$.body[0].order_id", is("XXXX")));
    }

    @Test
    void listingArtifactsReturnsMetadataButNeverBodies() throws Exception {
        var sessionId = createSession();
        postOrdersArtifact(sessionId);

        mvc.perform(get("/sessions/{id}/artifacts", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifacts", hasSize(1)))
                .andExpect(jsonPath("$.artifacts[0].content_hash", notNullValue()))
                .andExpect(jsonPath("$.artifacts[0].size_bytes", notNullValue()))
                // The whole point of the metadata projection: 9 artifacts must not
                // drag 9 bodies off the disk.
                .andExpect(jsonPath("$.artifacts[0].body").doesNotExist());
    }

    @Test
    void filtersArtifactsByLogicalName() throws Exception {
        var sessionId = createSession();
        postOrdersArtifact(sessionId);

        mvc.perform(get("/sessions/{id}/artifacts", sessionId).param("logical_name", "orders"))
                .andExpect(jsonPath("$.artifacts", hasSize(1)));
        mvc.perform(get("/sessions/{id}/artifacts", sessionId).param("logical_name", "nope"))
                .andExpect(jsonPath("$.artifacts", hasSize(0)));
    }

    @Test
    void servesTheRawBodyWithItsNativeContentType() throws Exception {
        var sessionId = createSession();

        var created = mvc.perform(post("/sessions/{id}/artifacts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"artifact_type":"xml_payload","source_system":"manual","logical_name":"order_xml",
                                 "format":"xml","body":"<order id=\\"XXXX\\"/>"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/artifacts/{id}/raw", json.readTree(created).get("artifact_id").asText()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string("<order id=\"XXXX\"/>"));
    }

    @Test
    void getSessionReturnsTheTimeline() throws Exception {
        var sessionId = createSession();
        postOrdersArtifact(sessionId);

        mvc.perform(get("/sessions/{id}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id", is(sessionId)))
                .andExpect(jsonPath("$.subjects.order_id", is("XXXX")))
                .andExpect(jsonPath("$.counts.artifacts", is(1)))
                .andExpect(jsonPath("$.counts.events", is(0)))
                // Present and empty: nothing writes these tables until steps 3 and 7.
                // openapi marks them required, and the generated client expects the keys.
                .andExpect(jsonPath("$.steps", hasSize(0)))
                .andExpect(jsonPath("$.milestones", hasSize(0)))
                .andExpect(jsonPath("$.snapshots", hasSize(0)))
                .andExpect(jsonPath("$.findings", hasSize(0)))
                .andExpect(jsonPath("$.active_runs", hasSize(0)));
    }

    @Test
    void patchMergesSubjectsRatherThanReplacingThem() throws Exception {
        var sessionId = createSession();

        mvc.perform(patch("/sessions/{id}", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"renamed","subjects":{"shipment_id":"SHP-99"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("renamed")))
                .andExpect(jsonPath("$.subjects.shipment_id", is("SHP-99")))
                // A shipment id discovered later must not erase the order id under test.
                .andExpect(jsonPath("$.subjects.order_id", is("XXXX")));
    }

    @Test
    void unknownSessionIs404WithAProblemBody() throws Exception {
        mvc.perform(get("/sessions/{id}", java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code", is("not_found")));
    }

    @Test
    void artifactOnAnUnknownSessionIs404() throws Exception {
        mvc.perform(post("/sessions/{id}/artifacts", java.util.UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"artifact_type":"log","source_system":"manual","logical_name":"x",
                                 "format":"text","body":"hi"}
                                """))
                .andExpect(status().isNotFound());
    }

    private void postOrdersArtifact(String sessionId) throws Exception {
        mvc.perform(post("/sessions/{id}/artifacts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"artifact_type":"rdbms_table","source_system":"oms_pg","logical_name":"orders",
                                 "format":"json","row_count":1,
                                 "body":[{"order_id":"XXXX","total_amount":1200.00}],
                                 "meta":{"pk_columns":["order_id"]}}
                                """))
                .andExpect(status().isCreated());
    }

    // Static imports for header()/content() clash with the jsonPath ones above if
    // imported wholesale; these keep the assertions above readable.
    private static org.springframework.test.web.servlet.result.HeaderResultMatchers header() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.header();
    }

    private static org.springframework.test.web.servlet.result.ContentResultMatchers content() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.content();
    }
}
