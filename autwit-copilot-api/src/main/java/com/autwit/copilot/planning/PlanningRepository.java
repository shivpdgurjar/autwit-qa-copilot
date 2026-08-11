package com.autwit.copilot.planning;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.autwit.copilot.common.Columns;
import com.autwit.copilot.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The planning aggregate (V4): projects, their source-document corpus, the async generation
 * jobs, and the two deliverables (test plan + test data). copilot-api is the sole writer.
 *
 * <p>The {@code generation} table doubles as a job queue — its {@link #dequeueGeneration}
 * mirrors {@code RunRepository.dequeue} (SKIP LOCKED + lease) so a planning job is claimed by
 * exactly one worker even across a restart.
 */
@Repository
public class PlanningRepository {

    private final JdbcTemplate jdbc;
    private final Json json;
    private final RowMapper<PlanningProject> projectMapper;
    private final RowMapper<SourceDocument> documentMapper;
    private final RowMapper<Generation> generationMapper;
    private final RowMapper<PlanningSession> sessionMapper;
    private final RowMapper<PlanningReasoning> reasoningMapper;

    public PlanningRepository(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.sessionMapper = (rs, n) -> new PlanningSession(
                Columns.uuid(rs, "session_id"),
                rs.getString("tester_id"),
                rs.getString("env"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("latest_response_id"),
                rs.getInt("version"),
                Columns.instant(rs, "created_at"),
                Columns.instant(rs, "updated_at"),
                Columns.instant(rs, "last_active_at"));
        this.projectMapper = (rs, n) -> new PlanningProject(
                Columns.uuid(rs, "project_id"),
                Columns.uuid(rs, "session_id"),
                rs.getString("feature_key"),
                rs.getString("feature_description"),
                rs.getString("domain"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("created_by"),
                rs.getString("env"),
                rs.getString("latest_response_id"),
                rs.getInt("version"),
                Columns.instant(rs, "created_at"),
                Columns.instant(rs, "updated_at"));
        this.documentMapper = (rs, n) -> new SourceDocument(
                Columns.uuid(rs, "document_id"),
                Columns.uuid(rs, "project_id"),
                SourceType.fromWire(rs.getString("source_type")),
                DocRole.fromWire(rs.getString("doc_role")),
                rs.getString("external_ref"),
                rs.getString("title"),
                rs.getString("mime"),
                rs.getString("text_content"),
                rs.getBoolean("selected"),
                rs.getString("content_hash"),
                Columns.instant(rs, "created_at"));
        this.generationMapper = (rs, n) -> new Generation(
                Columns.uuid(rs, "generation_id"),
                Columns.uuid(rs, "project_id"),
                GenerationType.fromWire(rs.getString("generation_type")),
                rs.getString("status"),
                json.readObject(rs.getString("config")),
                rs.getString("response_id"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getString("worker_id"),
                Columns.instant(rs, "lease_expires_at"),
                json.read(rs.getString("error"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                }),
                Columns.instant(rs, "created_at"),
                Columns.instant(rs, "updated_at"));
        this.reasoningMapper = (rs, n) -> new PlanningReasoning(
                Columns.uuid(rs, "reasoning_id"),
                Columns.uuid(rs, "project_id"),
                rs.getString("status"),
                rs.getInt("round"),
                rs.getString("override_reason"),
                rs.getString("override_by"),
                Columns.instant(rs, "override_at"),
                rs.getInt("version"),
                Columns.instant(rs, "created_at"),
                Columns.instant(rs, "updated_at"));
    }

    // ---- session ---------------------------------------------------------------------

    public PlanningSession createSession(String testerId, String env, String title) {
        return jdbc.queryForObject(
                """
                insert into autwit.planning_session (tester_id, env, title)
                values (?, ?, ?)
                returning *
                """,
                sessionMapper, testerId, env, title);
    }

    public Optional<PlanningSession> findSession(UUID sessionId) {
        return jdbc.query("select * from autwit.planning_session where session_id = ?", sessionMapper, sessionId)
                .stream().findFirst();
    }

    /** A tester's resume list — most-recently-active first. */
    public List<PlanningSession> listRecentSessions(String testerId, int limit) {
        if (testerId == null || testerId.isBlank()) {
            return jdbc.query("select * from autwit.planning_session order by last_active_at desc limit ?",
                    sessionMapper, limit);
        }
        return jdbc.query(
                "select * from autwit.planning_session where tester_id = ? order by last_active_at desc limit ?",
                sessionMapper, testerId, limit);
    }

    public void touchSession(UUID sessionId) {
        jdbc.update("update autwit.planning_session set last_active_at = now(), updated_at = now() "
                + "where session_id = ?", sessionId);
    }

    /**
     * Optimistically pins the running OpenAI lineage on the session head after a generation, and
     * touches last_active_at. Same discipline as {@code AnalysisRepository.recordResult}.
     *
     * @return true if this writer won the version race
     */
    public boolean bumpSessionHead(UUID sessionId, int expectedVersion, String latestResponseId) {
        return jdbc.update(
                """
                update autwit.planning_session
                   set latest_response_id = ?, version = version + 1,
                       last_active_at = now(), updated_at = now()
                 where session_id = ? and version = ?
                """,
                latestResponseId, sessionId, expectedVersion) == 1;
    }

    // ---- activity --------------------------------------------------------------------

    /** Appends a history entry and touches the session's last_active_at in one write. */
    public void addActivity(UUID sessionId, String kind, String ref, String summary) {
        jdbc.update("insert into autwit.planning_activity (session_id, kind, ref, summary) values (?, ?, ?, ?)",
                sessionId, kind, ref, summary);
        touchSession(sessionId);
    }

    public List<PlanningActivity> listActivity(UUID sessionId) {
        return jdbc.query(
                "select * from autwit.planning_activity where session_id = ? order by id",
                (rs, n) -> new PlanningActivity(rs.getLong("id"), Columns.uuid(rs, "session_id"),
                        rs.getString("kind"), rs.getString("ref"), rs.getString("summary"),
                        Columns.instant(rs, "at")),
                sessionId);
    }

    // ---- project ---------------------------------------------------------------------

    public PlanningProject createProject(UUID sessionId, String featureKey, String featureDescription,
            String domain, String title, String createdBy, String env) {
        return jdbc.queryForObject(
                """
                insert into autwit.planning_project
                  (session_id, feature_key, feature_description, domain, title, created_by, env)
                values (?, ?, ?, ?, ?, ?, ?)
                returning *
                """,
                projectMapper, sessionId, featureKey, featureDescription, domain, title, createdBy, env);
    }

    /** The project(s) in a session — v1 has one; ordered oldest-first. */
    public List<PlanningProject> listProjectsBySession(UUID sessionId) {
        return jdbc.query("select * from autwit.planning_project where session_id = ? order by created_at",
                projectMapper, sessionId);
    }

    public Optional<PlanningProject> findProject(UUID projectId) {
        return jdbc.query("select * from autwit.planning_project where project_id = ?", projectMapper, projectId)
                .stream().findFirst();
    }

    public List<PlanningProject> listProjects(int limit) {
        return jdbc.query("select * from autwit.planning_project order by created_at desc limit ?",
                projectMapper, limit);
    }

    /**
     * Optimistic head update after a generation: pins the OpenAI chaining token, bumps
     * version. Same discipline as {@code AnalysisRepository.recordResult}.
     *
     * @return true if this writer won the version race
     */
    public boolean bumpHead(UUID projectId, int expectedVersion, String latestResponseId) {
        return jdbc.update(
                """
                update autwit.planning_project
                   set latest_response_id = ?, version = version + 1, updated_at = now()
                 where project_id = ? and version = ?
                """,
                latestResponseId, projectId, expectedVersion) == 1;
    }

    // ---- source documents ------------------------------------------------------------

    /**
     * Adds a document, or refreshes it if the same (source_type, external_ref) already exists
     * in the project — re-fetching PAY-2481 updates rather than doubles. A raw paste has a
     * null external_ref, so pastes never collide (Postgres treats nulls as distinct).
     */
    public SourceDocument upsertDocument(UUID projectId, SourceType sourceType, DocRole docRole,
            String externalRef, String title, String mime, String textContent, String contentHash) {
        return jdbc.queryForObject(
                """
                insert into autwit.source_document
                  (project_id, source_type, doc_role, external_ref, title, mime, text_content, content_hash)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (project_id, source_type, external_ref) do update
                  set title = excluded.title, mime = excluded.mime, doc_role = excluded.doc_role,
                      text_content = excluded.text_content, content_hash = excluded.content_hash,
                      selected = true
                returning *
                """,
                documentMapper, projectId, sourceType.wire(), docRole.wire(), externalRef, title, mime,
                textContent, contentHash);
    }

    /** The tester re-tagging a document after upload — the role drives how it is read. */
    public boolean setDocRole(UUID documentId, DocRole docRole) {
        return jdbc.update("update autwit.source_document set doc_role = ? where document_id = ?",
                docRole.wire(), documentId) == 1;
    }

    public List<SourceDocument> listDocuments(UUID projectId) {
        return jdbc.query("select * from autwit.source_document where project_id = ? order by created_at",
                documentMapper, projectId);
    }

    public List<SourceDocument> listSelectedDocuments(UUID projectId) {
        return jdbc.query(
                "select * from autwit.source_document where project_id = ? and selected order by created_at",
                documentMapper, projectId);
    }

    public Optional<SourceDocument> findDocument(UUID documentId) {
        return jdbc.query("select * from autwit.source_document where document_id = ?", documentMapper, documentId)
                .stream().findFirst();
    }

    public boolean setSelected(UUID documentId, boolean selected) {
        return jdbc.update("update autwit.source_document set selected = ? where document_id = ?",
                selected, documentId) == 1;
    }

    public boolean deleteDocument(UUID documentId) {
        return jdbc.update("delete from autwit.source_document where document_id = ?", documentId) == 1;
    }

    // ---- generation (job queue) ------------------------------------------------------

    public Generation createGeneration(UUID projectId, GenerationType type, Map<String, Object> config) {
        return jdbc.queryForObject(
                """
                insert into autwit.generation (project_id, generation_type, config)
                values (?, ?, ?::jsonb)
                returning *
                """,
                generationMapper, projectId, type.wire(), json.writeOrEmptyObject(config));
    }

    public Optional<Generation> findGeneration(UUID generationId) {
        return jdbc.query("select * from autwit.generation where generation_id = ?", generationMapper, generationId)
                .stream().findFirst();
    }

    public List<Generation> listGenerations(UUID projectId) {
        return jdbc.query("select * from autwit.generation where project_id = ? order by created_at desc",
                generationMapper, projectId);
    }

    /**
     * Claims one pending generation (or one whose worker died with attempts left). Mirrors
     * {@code RunRepository.dequeue}: SKIP LOCKED so concurrent workers never claim the same
     * job, a lease so a dead worker's job can be reclaimed — but since max_attempts is 1, a
     * generation is never auto-retried, matching the FINANCIAL_ANALYSIS posture.
     */
    public Optional<Generation> dequeueGeneration(String workerId, Duration lease) {
        return jdbc.query(
                """
                update autwit.generation set
                  status           = 'running',
                  worker_id        = ?,
                  attempts         = attempts + 1,
                  lease_expires_at = now() + make_interval(secs => ?),
                  updated_at       = now()
                where generation_id = (
                  select generation_id from autwit.generation
                  where status = 'pending'
                     or (status = 'running' and lease_expires_at < now() and attempts < max_attempts)
                  order by created_at
                  for update skip locked
                  limit 1
                )
                returning *
                """,
                generationMapper, workerId, (double) lease.toSeconds())
                .stream().findFirst();
    }

    public boolean succeedGeneration(UUID generationId, String workerId, String responseId) {
        return jdbc.update(
                """
                update autwit.generation
                   set status = 'succeeded', response_id = ?, lease_expires_at = null, updated_at = now()
                 where generation_id = ? and worker_id = ? and status = 'running'
                """,
                responseId, generationId, workerId) == 1;
    }

    public boolean failGeneration(UUID generationId, String workerId, Map<String, Object> error) {
        return jdbc.update(
                """
                update autwit.generation
                   set status = 'failed', error = ?::jsonb, lease_expires_at = null, updated_at = now()
                 where generation_id = ? and worker_id = ? and status = 'running'
                """,
                json.write(error), generationId, workerId) == 1;
    }

    /**
     * Buries generations whose worker died mid-flight. The exact mirror of
     * {@link #dequeueGeneration}'s guard (ADR-001): dequeue reclaims expired leases with
     * {@code attempts < max_attempts}; this takes the rest — a lease expired with no attempts
     * left, which {@code max_attempts = 1} makes every dead-worker job — and marks it
     * {@code failed} so the UI stops polling it forever. Without it, such a job would sit in
     * {@code running} indefinitely (there is no auto-retry, by design — same posture as
     * {@code RunReaper} for {@code autwit.run}).
     *
     * @return how many were reaped
     */
    public int reapExpiredGenerations() {
        return jdbc.update(
                """
                update autwit.generation
                   set status = 'failed',
                       lease_expires_at = null,
                       error = jsonb_build_object('code', 'lease_expired', 'worker_id', worker_id,
                                                  'title', 'Worker lease expired',
                                                  'detail', 'The worker running this generation stopped '
                                                            || 'renewing its lease; it will not be retried.'),
                       updated_at = now()
                 where status = 'running' and lease_expires_at < now() and attempts >= max_attempts
                """);
    }

    // ---- test plan -------------------------------------------------------------------

    /**
     * Writes a v2 plan: the canonical payload plus the rows Step 5 keys off.
     *
     * <p>The payload is the raw artifact body with the canonical keys overlaid from the typed
     * result. The overlay matters: the read path reconstructs every plan-level field from the
     * payload, so a client that has no raw wire body — the {@code fake} profile, which is the
     * only offline path through the whole stack — would otherwise persist a plan with no
     * scope, requirements, risks or gaps. Starting from the raw body keeps any field this
     * version does not yet read, which is the reason the column exists at all.
     */
    public TestPlan insertPlan(UUID projectId, UUID generationId, PlanningClient.TestPlanResult result,
            List<TestPlan.TestScenario> scenarios) {
        var scope = result.scope() == null ? new TestPlan.Scope(List.of(), List.of()) : result.scope();
        var planId = jdbc.queryForObject(
                """
                insert into autwit.test_plan (project_id, generation_id, plan_version, overview, scope,
                                              provenance, payload)
                values (?, ?, 2, ?, ?, ?::jsonb, ?::jsonb)
                returning test_plan_id
                """,
                UUID.class, projectId, generationId, result.overview(),
                // The legacy `scope` text column stays populated so a v1 reader still sees
                // something sensible; the structured form lives in payload.
                String.join("; ", scope.inScope()),
                json.writeOrEmptyObject(result.provenance()),
                json.writeOrEmptyObject(canonicalPayload(result, scope)));

        // Batched: the old per-row loop issued one round trip per case, and a rich plan has
        // far more cases than the v1 shape did.
        jdbc.batchUpdate(
                """
                insert into autwit.test_scenario (test_plan_id, scenario_key, seq, capability, title,
                        priority, objective, lifecycle_phase, source, sources, requirement_ids,
                        preconditions, steps, expected_results, test_data_requirements, automation_mapping)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                        ?::jsonb, ?::jsonb)
                """,
                scenarios.stream()
                        .map(s -> new Object[] {
                                planId, s.scenarioKey(), s.seq(), s.capability(), s.title(),
                                s.priority(), s.objective(), s.lifecyclePhase(), s.source(),
                                json.writeOrEmptyArray(s.sources()),
                                json.writeOrEmptyArray(s.requirementIds()),
                                json.writeOrEmptyArray(s.preconditions()),
                                json.writeOrEmptyArray(s.steps()),
                                json.writeOrEmptyArray(s.expectedResults()),
                                json.writeOrEmptyArray(s.testDataRequirements()),
                                s.automationMapping() == null ? null
                                        : json.writeOrEmptyObject(s.automationMapping()),
                        })
                        .toList());
        return findPlan(planId).orElseThrow();
    }

    /** Raw body first (so unread fields survive), typed fields overlaid (so they always read back). */
    private static Map<String, Object> canonicalPayload(PlanningClient.TestPlanResult result,
            TestPlan.Scope scope) {
        var payload = new LinkedHashMap<String, Object>(
                result.payload() == null ? Map.of() : result.payload());
        payload.put("overview", result.overview());
        payload.put("scope", Map.of("in_scope", scope.inScope(), "out_of_scope", scope.outOfScope()));
        payload.put("architecture_context", result.architectureContext());
        payload.put("requirements", result.requirements().stream()
                .map(r -> mapOfNullable("id", r.id(), "statement", r.statement(), "category", r.category(),
                        "sources", r.sources(), "evidence", r.evidence(),
                        "lifecycle_phase", r.lifecyclePhase()))
                .toList());
        payload.put("test_data_requirements", result.testDataRequirements().stream()
                .map(d -> mapOfNullable("id", d.id(), "name", d.name(), "description", d.description(),
                        "attributes", d.attributes(), "source_of_truth", d.sourceOfTruth()))
                .toList());
        payload.put("execution_strategy", result.executionStrategy());
        payload.put("risks", result.risks());
        payload.put("gaps", result.gaps());
        // Capability descriptions are read from here when building the grouped API view.
        payload.put("capabilities", result.capabilities().stream()
                .map(c -> mapOfNullable("name", c.name(), "description", c.description()))
                .toList());
        return payload;
    }

    /** Map.of rejects nulls, and several of these fields are legitimately null. */
    private static Map<String, Object> mapOfNullable(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public Optional<TestPlan> findPlan(UUID testPlanId) {
        return jdbc.query(
                "select * from autwit.test_plan where test_plan_id = ?",
                (rs, n) -> new PlanHeader(
                        Columns.uuid(rs, "test_plan_id"), Columns.uuid(rs, "project_id"),
                        Columns.uuid(rs, "generation_id"), rs.getInt("plan_version"),
                        rs.getString("overview"), rs.getString("scope"),
                        json.readObject(rs.getString("provenance")),
                        json.readObject(rs.getString("payload")), Columns.instant(rs, "created_at")),
                testPlanId).stream().findFirst().map(this::buildPlan);
    }

    /** The {@code test_plan} row on its own; scenarios are fetched separately in {@link #buildPlan}. */
    private record PlanHeader(UUID planId, UUID projectId, UUID generationId, int planVersion,
            String overview, String scope, Map<String, Object> provenance,
            Map<String, Object> payload, Instant createdAt) {
    }

    public Optional<TestPlan> findPlanByGeneration(UUID generationId) {
        var id = jdbc.query("select test_plan_id from autwit.test_plan where generation_id = ?",
                (rs, n) -> Columns.uuid(rs, "test_plan_id"), generationId).stream().findFirst();
        return id.flatMap(this::findPlan);
    }

    /** The most recent plan for a project — what Step 4 carries scenarios forward from. */
    public Optional<TestPlan> findLatestPlan(UUID projectId) {
        var id = jdbc.query(
                "select test_plan_id from autwit.test_plan where project_id = ? order by created_at desc limit 1",
                (rs, n) -> Columns.uuid(rs, "test_plan_id"), projectId).stream().findFirst();
        return id.flatMap(this::findPlan);
    }

    /**
     * Reads a plan back. A v1 row (written before the v2 upgrade) has an empty payload, prose
     * in the legacy {@code scope} column and no capability grouping; it is adapted here rather
     * than migrated, so old plans stay openable without touching their data.
     */
    private TestPlan buildPlan(PlanHeader h) {
        var scenarios = jdbc.query(
                """
                select scenario_key, seq, capability, title, priority, objective, lifecycle_phase,
                       source, sources, requirement_ids, preconditions, steps, expected_results,
                       test_data_requirements, automation_mapping
                  from autwit.test_scenario
                 where test_plan_id = ?
                 order by seq
                """,
                (rs, n) -> new TestPlan.TestScenario(
                        rs.getString("scenario_key"), rs.getInt("seq"), rs.getString("capability"),
                        rs.getString("title"), rs.getString("priority"), rs.getString("objective"),
                        rs.getString("lifecycle_phase"),
                        json.readStringArray(rs.getString("sources")),
                        json.readStringArray(rs.getString("requirement_ids")),
                        json.readStringArray(rs.getString("preconditions")),
                        json.readStringArray(rs.getString("steps")),
                        json.readStringArray(rs.getString("expected_results")),
                        json.readStringArray(rs.getString("test_data_requirements")),
                        rs.getString("automation_mapping") == null ? null
                                : json.readObject(rs.getString("automation_mapping")),
                        rs.getString("source")),
                h.planId());

        var payload = h.payload();
        return new TestPlan(
                h.planId(), h.projectId(), h.generationId(), h.planVersion(), h.overview(),
                scopeOf(h, payload),
                mapAt(payload, "architecture_context"),
                requirementsOf(payload),
                dataRequirementsOf(payload),
                stringAt(payload, "execution_strategy"),
                listAt(payload, "risks"),
                listAt(payload, "gaps"),
                h.provenance(), payload, scenarios, h.createdAt());
    }

    /** v2 reads the structured scope from payload; v1 folds its prose into in_scope. */
    private static TestPlan.Scope scopeOf(PlanHeader h, Map<String, Object> payload) {
        var scope = mapAt(payload, "scope");
        if (scope != null) {
            return new TestPlan.Scope(stringsAt(scope, "in_scope"), stringsAt(scope, "out_of_scope"));
        }
        var legacy = h.scope();
        return new TestPlan.Scope(
                legacy == null || legacy.isBlank() ? List.of() : List.of(legacy), List.of());
    }

    private static List<TestPlan.Requirement> requirementsOf(Map<String, Object> payload) {
        return listAt(payload, "requirements").stream()
                .map(r -> new TestPlan.Requirement(stringAt(r, "id"), stringAt(r, "statement"),
                        stringAt(r, "category"), stringsAt(r, "sources"), stringAt(r, "evidence"),
                        stringAt(r, "lifecycle_phase")))
                .toList();
    }

    private static List<TestPlan.TestDataRequirement> dataRequirementsOf(Map<String, Object> payload) {
        return listAt(payload, "test_data_requirements").stream()
                .map(d -> new TestPlan.TestDataRequirement(stringAt(d, "id"), stringAt(d, "name"),
                        stringAt(d, "description"), stringsAt(d, "attributes"),
                        stringAt(d, "source_of_truth")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<String, Object> m, String key) {
        return m != null && m.get(key) instanceof Map<?, ?> v ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listAt(Map<String, Object> m, String key) {
        if (m == null || !(m.get(key) instanceof List<?> l)) {
            return List.of();
        }
        var out = new ArrayList<Map<String, Object>>();
        for (var e : l) {
            if (e instanceof Map<?, ?> row) {
                out.add((Map<String, Object>) row);
            }
        }
        return out;
    }

    private static String stringAt(Map<String, Object> m, String key) {
        var v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static List<String> stringsAt(Map<String, Object> m, String key) {
        if (m == null || !(m.get(key) instanceof List<?> l)) {
            return List.of();
        }
        return l.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    // ---- test data -------------------------------------------------------------------

    public void insertDataset(UUID projectId, UUID generationId, String scenarioKey,
            List<String> columns, List<Map<String, Object>> rows) {
        jdbc.update(
                """
                insert into autwit.test_dataset (project_id, generation_id, scenario_key, columns, rows)
                values (?, ?, ?, ?::jsonb, ?::jsonb)
                on conflict (generation_id, scenario_key) do update
                  set columns = excluded.columns, rows = excluded.rows
                """,
                projectId, generationId, scenarioKey, json.write(columns), json.write(rows));
    }

    public List<TestDataset> listDatasetsByGeneration(UUID generationId) {
        return jdbc.query(
                "select * from autwit.test_dataset where generation_id = ? order by scenario_key",
                (rs, n) -> new TestDataset(
                        Columns.uuid(rs, "dataset_id"),
                        Columns.uuid(rs, "project_id"),
                        Columns.uuid(rs, "generation_id"),
                        rs.getString("scenario_key"),
                        json.read(rs.getString("columns"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                        }),
                        json.read(rs.getString("rows"), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                        }),
                        Columns.instant(rs, "created_at")),
                generationId);
    }

    // ---- reasoning (conflict/clarification loop) -------------------------------------

    /**
     * Get-or-create the project's reasoning thread and start a new round in one atomic upsert:
     * round 1 on first analysis, round + 1 on each re-analysis. Status returns to 'open' — a
     * fresh round is running, findings unknown, so generation stays gated until it settles.
     */
    public PlanningReasoning startReasoningRound(UUID projectId) {
        return jdbc.queryForObject(
                """
                insert into autwit.planning_reasoning (project_id, status, round)
                values (?, 'open', 1)
                on conflict (project_id) do update
                  set round = autwit.planning_reasoning.round + 1,
                      status = 'open',
                      version = autwit.planning_reasoning.version + 1,
                      updated_at = now()
                returning *
                """,
                reasoningMapper, projectId);
    }

    public Optional<PlanningReasoning> findReasoningByProject(UUID projectId) {
        return jdbc.query("select * from autwit.planning_reasoning where project_id = ?", reasoningMapper, projectId)
                .stream().findFirst();
    }

    /** Marks the thread clean (no findings) or open (findings remain). */
    public void setReasoningStatus(UUID reasoningId, String status) {
        jdbc.update("update autwit.planning_reasoning set status = ?, updated_at = now() where reasoning_id = ?",
                status, reasoningId);
    }

    /** Records the explicit "proceed anyway" override, unlocking generation. */
    public void overrideReasoning(UUID reasoningId, String reason, String by) {
        jdbc.update(
                """
                update autwit.planning_reasoning
                   set status = 'overridden', override_reason = ?, override_by = ?,
                       override_at = now(), updated_at = now()
                 where reasoning_id = ?
                """,
                reason, by, reasoningId);
    }

    /** Persists one analysis round (the deliverable) plus its findings. Returns the analysis id. */
    public UUID createAnalysisRound(UUID reasoningId, UUID generationId, int round,
            int conflictsTotal, int clarificationsTotal, List<AnalysisFinding> findings) {
        var analysisId = jdbc.queryForObject(
                """
                insert into autwit.planning_analysis
                  (reasoning_id, generation_id, round, conflicts_total, clarifications_total)
                values (?, ?, ?, ?, ?)
                returning analysis_id
                """,
                UUID.class, reasoningId, generationId, round, conflictsTotal, clarificationsTotal);
        int seq = 1;
        for (var f : findings) {
            jdbc.update(
                    """
                    insert into autwit.planning_analysis_finding
                      (analysis_id, kind, seq, title, detail, sources, options)
                    values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                    """,
                    analysisId, f.kind(), seq++, f.title(), f.detail(),
                    json.write(f.sources()), json.write(f.options()));
        }
        return analysisId;
    }

    /** The most recent round for a thread, with its findings — what the UI renders. */
    public Optional<PlanningAnalysis> findLatestAnalysis(UUID reasoningId) {
        var header = jdbc.query(
                "select * from autwit.planning_analysis where reasoning_id = ? order by round desc limit 1",
                (rs, n) -> new Object[] {
                        Columns.uuid(rs, "analysis_id"), Columns.uuid(rs, "generation_id"),
                        rs.getInt("round"), rs.getInt("conflicts_total"), rs.getInt("clarifications_total"),
                        Columns.instant(rs, "created_at") },
                reasoningId).stream().findFirst();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        var h = header.get();
        var analysisId = (UUID) h[0];
        var findings = jdbc.query(
                "select * from autwit.planning_analysis_finding where analysis_id = ? order by seq",
                (rs, n) -> new AnalysisFinding(
                        Columns.uuid(rs, "finding_id"),
                        rs.getString("kind"),
                        rs.getInt("seq"),
                        rs.getString("title"),
                        rs.getString("detail"),
                        json.read(rs.getString("sources"), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                        }),
                        json.read(rs.getString("options"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                        })),
                analysisId);
        return Optional.of(new PlanningAnalysis(analysisId, reasoningId, (UUID) h[1],
                (int) h[2], (int) h[3], (int) h[4], findings, (Instant) h[5]));
    }

    public void addResolution(UUID reasoningId, int round, UUID findingId, String kind,
            String prompt, String answer) {
        jdbc.update(
                """
                insert into autwit.planning_resolution (reasoning_id, round, finding_id, kind, prompt, answer)
                values (?, ?, ?, ?, ?, ?)
                """,
                reasoningId, round, findingId, kind, prompt, answer);
    }

    public List<Resolution> listResolutions(UUID reasoningId) {
        return jdbc.query(
                "select * from autwit.planning_resolution where reasoning_id = ? order by created_at",
                (rs, n) -> new Resolution(
                        Columns.uuid(rs, "resolution_id"),
                        Columns.uuid(rs, "reasoning_id"),
                        rs.getInt("round"),
                        Columns.uuid(rs, "finding_id"),
                        rs.getString("kind"),
                        rs.getString("prompt"),
                        rs.getString("answer"),
                        Columns.instant(rs, "created_at")),
                reasoningId);
    }
}
