package com.autwit.copilot.web;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The planning wizard's HTTP surface (snake_case wire, same as the rest of the app). */
@AutoConfigureMockMvc
class PlanningControllerTest extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    private String createProject() throws Exception {
        var body = mvc.perform(post("/planning/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"feature_key":"PAY-2481","feature_description":"Payment retry",
                                 "title":"Retry plan","created_by":"m.alvarez","env":"qa2"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.project_id", notNullValue()))
                .andExpect(jsonPath("$.feature_key", is("PAY-2481")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("project_id").asText();
    }

    @Test
    void addsATextDocumentAndListsIt() throws Exception {
        var id = createProject();

        mvc.perform(post("/planning/projects/{id}/documents", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source_type":"paste","title":"spec","text":"retry with backoff"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source_type", is("paste")))
                .andExpect(jsonPath("$.selected", is(true)));

        mvc.perform(get("/planning/projects/{id}/documents", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void searchesJiraAndFetchesContext() throws Exception {
        var id = createProject();

        mvc.perform(get("/planning/projects/{id}/jira-search", id).param("query", "retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind", is("jira")))
                .andExpect(jsonPath("$[0].ref", notNullValue()));

        mvc.perform(post("/planning/projects/{id}/fetch", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jira_keys":["PAY-2481"],"confluence_page_ids":["PAY-DESIGN"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents", hasSize(2)))
                .andExpect(jsonPath("$.log", notNullValue()));
    }

    @Test
    void testPlanWithoutSourcesIs400() throws Exception {
        var id = createProject();
        mvc.perform(post("/planning/projects/{id}/test-plan", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("no_sources")));
    }

    @Test
    void generatingATestPlanReturns202WithAGenerationId() throws Exception {
        var id = createProject();
        mvc.perform(post("/planning/projects/{id}/documents", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source_type":"paste","title":"spec","text":"retry with backoff"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(post("/planning/projects/{id}/test-plan", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.generation_id", notNullValue()))
                .andExpect(jsonPath("$.generation_type", is("test_plan")))
                .andExpect(jsonPath("$.status", is("pending")));
    }

    @Test
    void rejectsAnUnknownProject() throws Exception {
        mvc.perform(get("/planning/projects/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }
}
