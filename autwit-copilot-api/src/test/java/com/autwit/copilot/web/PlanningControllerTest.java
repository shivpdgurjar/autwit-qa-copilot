package com.autwit.copilot.web;

import java.io.ByteArrayOutputStream;

import com.autwit.copilot.planning.PlanningGenerationWorker;
import com.autwit.copilot.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The planning wizard's HTTP surface (snake_case wire, same as the rest of the app).
 *
 * <p>{@code all} (merged with the parent's {@code fake}) so the generation worker bean exists
 * — the rich-plan body assertion needs a generation to actually run. The parent still parks
 * the worker, so it is driven with {@code pollOnce()} rather than on a timer.
 */
@AutoConfigureMockMvc
@ActiveProfiles("all")
class PlanningControllerTest extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private PlanningGenerationWorker worker;

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
    void createsASessionWithItsFirstProject() throws Exception {
        mvc.perform(post("/planning/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tester_id":"sess-tester","env":"qa2","title":"Retry",
                                 "feature_key":"PAY-2481","feature_description":"retry"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.session.session_id", notNullValue()))
                .andExpect(jsonPath("$.session.tester_id", is("sess-tester")))
                .andExpect(jsonPath("$.project.session_id", is(notNullValue())))
                .andExpect(jsonPath("$.project.feature_key", is("PAY-2481")));
    }

    @Test
    void listsAndResumesASessionWithHistory() throws Exception {
        var body = mvc.perform(post("/planning/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tester_id":"resume-tester","env":"qa2","title":"S","feature_key":"PAY-9"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var sid = json.readTree(body).get("session").get("session_id").asText();

        // The tester's resume list.
        mvc.perform(get("/planning/sessions").param("tester_id", "resume-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].session_id", is(sid)));

        // Resume detail: the session + its one project + a history timeline (created + project).
        mvc.perform(get("/planning/sessions/{id}", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.session_id", is(sid)))
                .andExpect(jsonPath("$.projects", hasSize(1)))
                .andExpect(jsonPath("$.activity[0].kind", is("session_created")));
    }

    @Test
    void uploadsADocxParsedServerSide() throws Exception {
        var id = createProject();
        var out = new ByteArrayOutputStream();
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("retry orchestrator design DOCX");
            doc.write(out);
        }
        var file = new MockMultipartFile("file", "design.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", out.toByteArray());

        mvc.perform(multipart("/planning/projects/{id}/documents/upload", id).file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source_type", is("upload")))
                .andExpect(jsonPath("$.external_ref", is("design.docx")))
                // Tika extracted the paragraph text server-side → non-zero length.
                .andExpect(jsonPath("$.text_length", notNullValue()));
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
    void theGeneratedPlanSerialisesEveryRichFieldOnTheWire() throws Exception {
        // No test asserted the GET .../test-plan body at all before v2, which is how a field
        // could be dropped between the runner and the UI without anything failing.
        var id = createProject();
        mvc.perform(post("/planning/projects/{id}/documents", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source_type":"paste","doc_role":"requirement","title":"spec",
                                 "text":"retry with backoff"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.doc_role", is("requirement")));

        var gen = json.readTree(mvc.perform(post("/planning/projects/{id}/test-plan", id))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        worker.pollOnce();

        mvc.perform(get("/planning/projects/{id}/test-plan", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan_version", is(2)))
                .andExpect(jsonPath("$.generation_id", is(gen.get("generation_id").asText())))
                .andExpect(jsonPath("$.scope.in_scope", hasSize(3)))
                .andExpect(jsonPath("$.scope.out_of_scope", hasSize(1)))
                .andExpect(jsonPath("$.architecture_context.summary", notNullValue()))
                .andExpect(jsonPath("$.requirements", hasSize(5)))
                .andExpect(jsonPath("$.requirements[0].id", is("REQ-01")))
                .andExpect(jsonPath("$.test_data_requirements", hasSize(2)))
                .andExpect(jsonPath("$.execution_strategy", notNullValue()))
                .andExpect(jsonPath("$.risks", hasSize(1)))
                .andExpect(jsonPath("$.gaps", hasSize(1)))
                // Grouped by capability — the shape the UI renders.
                .andExpect(jsonPath("$.capabilities", hasSize(3)))
                .andExpect(jsonPath("$.capabilities[0].name", is("Retry execution")))
                .andExpect(jsonPath("$.capabilities[0].description", notNullValue()))
                .andExpect(jsonPath("$.capabilities[0].test_cases", hasSize(2)))
                .andExpect(jsonPath("$.capabilities[0].test_cases[0].objective", notNullValue()))
                .andExpect(jsonPath("$.capabilities[0].test_cases[0].steps", hasSize(1)))
                .andExpect(jsonPath("$.capabilities[0].test_cases[0].expected_results", hasSize(2)))
                .andExpect(jsonPath("$.capabilities[0].test_cases[0].preconditions", hasSize(1)))
                .andExpect(jsonPath("$.capabilities[0].test_cases[0].lifecycle_phase",
                        is("Payment authorisation")))
                .andExpect(jsonPath("$.capabilities[0].test_cases[0].automation_mapping.type", is("api")))
                // An absent automation target stays absent rather than becoming {}.
                .andExpect(jsonPath("$.capabilities[0].test_cases[1].automation_mapping").doesNotExist())
                // The flat projection is still served for readers that want it.
                .andExpect(jsonPath("$.scenarios", hasSize(5)))
                .andExpect(jsonPath("$.scenarios[0].scenario_key", is("TC-01")));
    }

    @Test
    void aDocumentRoleCanBeChangedAfterUpload() throws Exception {
        var id = createProject();
        var doc = json.readTree(mvc.perform(post("/planning/projects/{id}/documents", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source_type":"paste","title":"old cases","text":"TC-99 legacy"}
                                """))
                .andExpect(status().isCreated())
                // Unspecified role defaults to requirement rather than failing.
                .andExpect(jsonPath("$.doc_role", is("requirement")))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(patch("/planning/projects/{id}/documents/{doc}", id, doc.get("document_id").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"doc_role":"existing_tests"}
                                """))
                .andExpect(status().isNoContent());

        mvc.perform(get("/planning/projects/{id}/documents", id))
                .andExpect(jsonPath("$[0].doc_role", is("existing_tests")))
                // A role-only PATCH must not disturb the include toggle.
                .andExpect(jsonPath("$[0].selected", is(true)));
    }

    @Test
    void fetchAcceptsPastedKeysAndLinksAlongsideSearchPicks() throws Exception {
        var id = createProject();

        mvc.perform(post("/planning/projects/{id}/fetch", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jira_keys":["PAY-2481"],
                                 "confluence_page_ids":[],
                                 "refs":["can-1201",
                                         "https://acuver.atlassian.net/wiki/spaces/OES/pages/123456789/Design",
                                         "https://acuver.atlassian.net/wiki/x/AbCdEf"]}
                                """))
                .andExpect(status().isOk())
                // The unresolvable short link is reported and skipped; everything else fetched.
                .andExpect(jsonPath("$.log[0].level", is("warn")))
                .andExpect(jsonPath("$.log[0].message", containsString("short link")))
                .andExpect(jsonPath("$.documents", hasSize(3)));
    }

    @Test
    void fetchWithNothingUsableIs400() throws Exception {
        var id = createProject();
        mvc.perform(post("/planning/projects/{id}/fetch", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jira_keys":[],"confluence_page_ids":[],"refs":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("no_refs")));
    }

    @Test
    void rejectsAnUnknownProject() throws Exception {
        mvc.perform(get("/planning/projects/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }
}
