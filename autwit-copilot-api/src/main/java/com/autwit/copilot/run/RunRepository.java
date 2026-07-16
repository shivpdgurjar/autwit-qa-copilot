package com.autwit.copilot.run;

import java.time.Duration;
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
 * autwit.run is the queue (invariant 5). Postgres SKIP LOCKED. No Redis, no SQS.
 *
 * <p>The dequeue and reap predicates here are disjoint by construction — see
 * docs/ADR-001-reclaim-vs-reap.md. Do not "simplify" either by dropping its
 * attempts/max_attempts guard: they are what stop a dead worker's run being
 * simultaneously eligible for reclaim and for burial, and the pair is the only
 * reason invariant 8 holds without depending on which statement fires first.
 */
@Repository
public class RunRepository {

    private final JdbcTemplate jdbc;
    private final Json json;
    private final RowMapper<Run> mapper;

    public RunRepository(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, n) -> new Run(
                Columns.uuid(rs, "run_id"),
                Columns.uuid(rs, "session_id"),
                Columns.uuid(rs, "step_id"),
                rs.getString("run_type"),
                rs.getString("status"),
                json.readObject(rs.getString("request")),
                json.readObject(rs.getString("progress")),
                json.readObject(rs.getString("result_summary")),
                json.readObject(rs.getString("error")),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getBoolean("cancel_requested"),
                rs.getString("idempotency_key"),
                Columns.instant(rs, "lease_until"),
                rs.getString("worker_id"),
                Columns.instant(rs, "queued_at"),
                Columns.instant(rs, "started_at"),
                Columns.instant(rs, "ended_at"),
                elapsedMs(rs));
    }

    private static Long elapsedMs(java.sql.ResultSet rs) throws java.sql.SQLException {
        var started = Columns.instant(rs, "started_at");
        if (started == null) {
            return null;
        }
        var ended = Columns.instant(rs, "ended_at");
        return Duration.between(started, ended != null ? ended : java.time.Instant.now()).toMillis();
    }

    // ---------------------------------------------------------------- enqueue

    public Run insert(UUID sessionId, UUID stepId, RunType runType, Map<String, Object> request,
            int maxAttempts, String idempotencyKey) {
        return jdbc.queryForObject(
                """
                insert into autwit.run (session_id, step_id, run_type, status, request, max_attempts, idempotency_key)
                values (?, ?, ?, 'queued', ?::jsonb, ?, ?)
                returning *
                """,
                mapper, sessionId, stepId, runType.wire(), json.writeOrEmptyObject(request),
                maxAttempts, idempotencyKey);
    }

    /** Backs the Idempotency-Key replay: uq_run_idempotency is the actual guarantee. */
    public Optional<Run> findByIdempotencyKey(UUID sessionId, String key) {
        if (key == null) {
            return Optional.empty();
        }
        return jdbc.query(
                "select * from autwit.run where session_id = ? and idempotency_key = ?", mapper, sessionId, key)
                .stream().findFirst();
    }

    // ---------------------------------------------------------------- dequeue

    /**
     * Claims one run: fresh work, or a dead worker's run that is still allowed a retry.
     *
     * <p>{@code attempts < max_attempts} is load-bearing. Without it this statement
     * and {@link #reapExpired} both match an expired lease, and whichever runs first
     * decides whether the run is retried or buried. With it, a run whose max_attempts
     * is 1 becomes invisible here the moment it is attempted, and can only ever end
     * timed_out — which is what invariant 8 demands of work that may have placed an
     * order.
     */
    public Optional<Run> dequeue(String workerId, Duration lease) {
        return jdbc.query(
                """
                update autwit.run set
                  status      = 'running',
                  worker_id   = ?,
                  attempts    = attempts + 1,
                  started_at  = coalesce(started_at, now()),
                  lease_until = now() + make_interval(secs => ?)
                where run_id = (
                  select run_id from autwit.run
                  where status = 'queued'
                     or (status = 'running' and lease_until < now() and attempts < max_attempts)
                  order by queued_at
                  for update skip locked
                  limit 1
                )
                returning *
                """,
                mapper, workerId, (double) lease.toSeconds())
                .stream().findFirst();
    }

    /** Puts a run back when its session's advisory lock is held elsewhere. */
    public void releaseToQueue(UUID runId) {
        // attempts is decremented: this run was never executed, it was only claimed.
        // Leaving it incremented would burn a mutating run's single allowed attempt on
        // nothing more than lock contention.
        jdbc.update(
                """
                update autwit.run
                set status = 'queued', worker_id = null, lease_until = null, attempts = greatest(attempts - 1, 0)
                where run_id = ? and status = 'running'
                """,
                runId);
    }

    public void renewLease(UUID runId, String workerId, Duration lease) {
        jdbc.update(
                "update autwit.run set lease_until = now() + make_interval(secs => ?) "
                        + "where run_id = ? and worker_id = ? and status = 'running'",
                (double) lease.toSeconds(), runId, workerId);
    }

    // ---------------------------------------------------------------- terminal transitions

    /**
     * @return false when the run is no longer ours to finish — it went terminal while
     *         we were working (cancelled, or reaped). The caller must then discard the
     *         orchestrator's result rather than persist it (BUILD_BRIEF §6, "On late
     *         result").
     */
    public boolean succeed(UUID runId, String workerId, Map<String, Object> resultSummary) {
        return jdbc.update(
                """
                update autwit.run
                set status = 'succeeded', ended_at = now(), result_summary = ?::jsonb, lease_until = null
                where run_id = ? and worker_id = ? and status = 'running'
                """,
                json.write(resultSummary), runId, workerId) > 0;
    }

    public boolean fail(UUID runId, String workerId, Map<String, Object> error) {
        return jdbc.update(
                """
                update autwit.run
                set status = 'failed', ended_at = now(), error = ?::jsonb, lease_until = null
                where run_id = ? and worker_id = ? and status = 'running'
                """,
                json.write(error), runId, workerId) > 0;
    }

    /**
     * timed_out is NOT failed: the outcome is unknown, so this is terminal and is
     * never auto-retried (invariant 8).
     */
    public boolean timeOut(UUID runId, String workerId, Map<String, Object> error) {
        return jdbc.update(
                """
                update autwit.run
                set status = 'timed_out', ended_at = now(), error = ?::jsonb, lease_until = null
                where run_id = ? and worker_id = ? and status = 'running'
                """,
                json.write(error), runId, workerId) > 0;
    }

    public boolean cancel(UUID runId, String workerId) {
        return jdbc.update(
                """
                update autwit.run
                set status = 'cancelled', ended_at = now(), lease_until = null
                where run_id = ? and worker_id = ? and status = 'running'
                """,
                runId, workerId) > 0;
    }

    /** A queued run is cancelled outright; a running one is only flagged. */
    public Optional<Run> requestCancel(UUID runId) {
        jdbc.update(
                """
                update autwit.run set
                  cancel_requested = true,
                  status   = case when status = 'queued' then 'cancelled' else status end,
                  ended_at = case when status = 'queued' then now() else ended_at end
                where run_id = ? and status in ('queued', 'running')
                """,
                runId);
        return find(runId);
    }

    public void updateProgress(UUID runId, Map<String, Object> progress) {
        jdbc.update("update autwit.run set progress = ?::jsonb where run_id = ?", json.write(progress), runId);
    }

    // ---------------------------------------------------------------- reaper

    /**
     * Buries what must never be retried. The exact mirror of {@link #dequeue}'s guard
     * (ADR-001): dequeue takes {@code attempts < max_attempts}, this takes the rest.
     *
     * <p>Safe under READ COMMITTED with no extra locking. If a worker reclaims a row
     * while this sweep is in flight, this UPDATE blocks on the row lock, re-evaluates
     * its WHERE against the committed row, sees the renewed lease_until, and skips it.
     *
     * @return how many runs were reaped
     */
    public int reapExpired() {
        return jdbc.update(
                """
                update autwit.run
                set status = 'timed_out',
                    ended_at = now(),
                    lease_until = null,
                    error = jsonb_build_object('code', 'lease_expired', 'worker_id', worker_id,
                                               'title', 'Worker lease expired',
                                               'detail', 'The worker holding this run stopped renewing its lease. '
                                                      || 'The outcome is UNKNOWN: the run may have partially '
                                                      || 'completed. Verify before retrying.')
                where status = 'running' and lease_until < now() and attempts >= max_attempts
                """);
    }

    // ---------------------------------------------------------------- reads

    public Optional<Run> find(UUID runId) {
        return jdbc.query("select * from autwit.run where run_id = ?", mapper, runId).stream().findFirst();
    }

    public List<Run> listBySession(UUID sessionId, String status, Boolean active) {
        return jdbc.query(
                """
                select * from autwit.run
                where session_id = ?
                  and (?::text is null or status = ?)
                  and (?::boolean is null or (? = (status in ('queued','running'))))
                order by queued_at desc
                """,
                mapper, sessionId, status, status, active, active);
    }

    public List<Run> listActive(UUID sessionId) {
        return listBySession(sessionId, null, true);
    }

    /**
     * Thin fire-and-forget hint. Truth is always GET /sessions/{id} (invariant 4), so a
     * dropped notification is harmless — the UI just refetches a moment later.
     *
     * <p>query() rather than update(): pg_notify is a function call, so this is a SELECT
     * that returns a row, and update() rejects it with "A result was returned when none
     * was expected".
     */
    public void notifyRun(UUID sessionId, UUID runId, UUID stepId, String status, String type) {
        jdbc.query(
                """
                select pg_notify('autwit_run', json_build_object(
                  'session_id', ?::text, 'run_id', ?::text, 'step_id', ?::text,
                  'status', ?::text, 'type', ?::text, 'at', now())::text)
                """,
                rs -> null,
                sessionId, runId, stepId, status, type);
    }
}
