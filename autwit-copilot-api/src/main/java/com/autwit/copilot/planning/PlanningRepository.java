package com.autwit.copilot.planning;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
            String title, String createdBy, String env) {
        return jdbc.queryForObject(
                """
                insert into autwit.planning_project
                  (session_id, feature_key, feature_description, title, created_by, env)
                values (?, ?, ?, ?, ?, ?)
                returning *
                """,
                projectMapper, sessionId, featureKey, featureDescription, title, createdBy, env);
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
    public SourceDocument upsertDocument(UUID projectId, SourceType sourceType, String externalRef,
            String title, String mime, String textContent, String contentHash) {
        return jdbc.queryForObject(
                """
                insert into autwit.source_document
                  (project_id, source_type, external_ref, title, mime, text_content, content_hash)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (project_id, source_type, external_ref) do update
                  set title = excluded.title, mime = excluded.mime,
                      text_content = excluded.text_content, content_hash = excluded.content_hash,
                      selected = true
                returning *
                """,
                documentMapper, projectId, sourceType.wire(), externalRef, title, mime, textContent, contentHash);
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

    public TestPlan insertPlan(UUID projectId, UUID generationId, String overview, String scope,
            Map<String, Object> provenance, List<TestPlan.TestScenario> scenarios) {
        var planId = jdbc.queryForObject(
                """
                insert into autwit.test_plan (project_id, generation_id, overview, scope, provenance)
                values (?, ?, ?, ?, ?::jsonb)
                returning test_plan_id
                """,
                UUID.class, projectId, generationId, overview, scope, json.writeOrEmptyObject(provenance));
        for (var s : scenarios) {
            jdbc.update(
                    """
                    insert into autwit.test_scenario (test_plan_id, scenario_key, seq, title, priority, source)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    planId, s.scenarioKey(), s.seq(), s.title(), s.priority(), s.source());
        }
        return findPlan(planId).orElseThrow();
    }

    public Optional<TestPlan> findPlan(UUID testPlanId) {
        return jdbc.query(
                "select * from autwit.test_plan where test_plan_id = ?",
                (rs, n) -> new PlanHeader(
                        Columns.uuid(rs, "test_plan_id"), Columns.uuid(rs, "project_id"),
                        Columns.uuid(rs, "generation_id"), rs.getString("overview"), rs.getString("scope"),
                        json.readObject(rs.getString("provenance")), Columns.instant(rs, "created_at")),
                testPlanId).stream().findFirst().map(this::buildPlan);
    }

    /** The {@code test_plan} row on its own; scenarios are fetched separately in {@link #buildPlan}. */
    private record PlanHeader(UUID planId, UUID projectId, UUID generationId, String overview,
            String scope, Map<String, Object> provenance, Instant createdAt) {
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

    private TestPlan buildPlan(PlanHeader h) {
        var scenarios = jdbc.query(
                "select scenario_key, seq, title, priority, source from autwit.test_scenario "
                        + "where test_plan_id = ? order by seq",
                (rs, n) -> new TestPlan.TestScenario(rs.getString("scenario_key"), rs.getInt("seq"),
                        rs.getString("title"), rs.getString("priority"), rs.getString("source")),
                h.planId());
        return new TestPlan(h.planId(), h.projectId(), h.generationId(), h.overview(), h.scope(),
                h.provenance(), scenarios, h.createdAt());
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
}
