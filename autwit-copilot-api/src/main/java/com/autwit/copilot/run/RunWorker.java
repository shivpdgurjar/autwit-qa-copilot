package com.autwit.copilot.run;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.autwit.copilot.config.AutwitProperties;
import com.autwit.copilot.orchestrator.OrchestratorClient;
import com.autwit.copilot.orchestrator.OrchestratorException;
import com.autwit.copilot.orchestrator.dto.Envelope;
import com.autwit.copilot.orchestrator.dto.InvokeRequest;
import com.autwit.copilot.session.StepRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Dequeues through Postgres and executes runs.
 *
 * <p>BUILD_BRIEF §5: the worker is in-process for now and separates later, and that
 * split must be a config change rather than a rewrite — so this ALWAYS dequeues
 * through Postgres SKIP LOCKED even in mode=all. There is no in-memory shortcut, and
 * adding one would quietly make the separation a rewrite.
 *
 * <p>Concurrency is 4. The advisory lock serializes per session anyway, so that only
 * buys cross-session parallelism.
 */
@Component
@Profile("worker | all")
public class RunWorker {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

    /** How long to wait before re-polling an empty queue. */
    private static final long IDLE_BACKOFF_MS = 250;

    private final RunRepository runs;
    private final StepRepository steps;
    private final SessionLocks locks;
    private final OrchestratorClient orchestrator;
    private final SessionContextBuilder contexts;
    private final EnvelopePersister persister;
    private final AutwitProperties props;
    private final com.autwit.copilot.session.SessionRepository sessions;
    private final LocalRunExecutor localRuns;

    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService pool;

    public RunWorker(RunRepository runs, StepRepository steps, SessionLocks locks,
            OrchestratorClient orchestrator, SessionContextBuilder contexts, EnvelopePersister persister,
            AutwitProperties props, com.autwit.copilot.session.SessionRepository sessions,
            LocalRunExecutor localRuns) {
        this.runs = runs;
        this.steps = steps;
        this.locks = locks;
        this.orchestrator = orchestrator;
        this.contexts = contexts;
        this.persister = persister;
        this.props = props;
        this.sessions = sessions;
        this.localRuns = localRuns;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        int concurrency = props.run().workerConcurrency();
        running.set(true);
        // Virtual threads: these loops spend their lives blocked on a socket for up to
        // ten minutes, which is exactly the workload platform threads waste memory on.
        pool = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < concurrency; i++) {
            pool.submit(this::loop);
        }
        log.info("RunWorker {} started with concurrency {}", workerId, concurrency);
    }

    /**
     * On SIGTERM: stop dequeuing, let in-flight runs drain, exit (BUILD_BRIEF §5).
     *
     * <p>This is why spring.lifecycle.timeout-per-shutdown-phase is 660s. If the
     * platform's terminationGracePeriodSeconds is shorter than that, deploys will kill
     * live snapshots and they will land as timed_out.
     */
    @PreDestroy
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("RunWorker {} draining in-flight runs…", workerId);
        pool.shutdown();
        try {
            var grace = props.orchestrator().timeout().plusSeconds(30);
            if (!pool.awaitTermination(grace.toSeconds(), TimeUnit.SECONDS)) {
                // Whatever is left will have its lease expire and be reaped as timed_out.
                log.warn("RunWorker {} did not drain within {}; forcing shutdown", workerId, grace);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        log.info("RunWorker {} stopped", workerId);
    }

    private void loop() {
        while (running.get()) {
            try {
                if (!pollOnce()) {
                    Thread.sleep(IDLE_BACKOFF_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Worker {} loop error; continuing", workerId, e);
                sleepQuietly();
            }
        }
    }

    /**
     * Claims and executes at most one run.
     *
     * <p>Public so tests can drive the queue a step at a time with worker-concurrency=0
     * — the bean exists, no background loops run, and each test executes exactly the run
     * it enqueued instead of racing four pollers.
     *
     * @return true if a run was claimed, whatever its outcome
     */
    public boolean pollOnce() {
        var claimed = runs.dequeue(workerId, props.run().lease());
        if (claimed.isEmpty()) {
            return false;
        }

        var run = claimed.get();

        // Invariant 6: runs are serialized per session. If another worker holds this
        // session's lock, release the run and take the next one -- never block, or one
        // busy session stalls the whole worker.
        var lock = locks.tryAcquire(run.sessionId());
        if (lock.isEmpty()) {
            log.debug("Session {} is locked by another worker; releasing run {} back to the queue",
                    run.sessionId(), run.runId());
            runs.releaseToQueue(run.runId());
            return true;
        }

        try (var held = lock.get()) {
            execute(run);
        } finally {
            // The lock is released by close() above, before the NOTIFY below, so a UI
            // that refetches on the hint cannot observe a lock we have not dropped.
        }
        return true;
    }

    private void execute(Run run) {
        runs.notifyRun(run.sessionId(), run.runId(), run.stepId(), "running", "run.started");
        steps.updateStatus(run.stepId(), "running");

        // Cancellation is cooperative: a queued run is cancelled outright by the API,
        // and a running one is only flagged. This is the first of the checkpoints.
        if (isCancelled(run)) {
            return;
        }

        try {
            // Comparison and report are local: they read snapshots we already hold and
            // never touch the orchestrator. They are still runs (invariant 2 —
            // "Everything that touches the orchestrator OR takes >1s is a run. Uniform.
            // Even a local diff"), so they go through the same queue, lock and lease.
            // Only the execution differs.
            if (localRuns.handles(run.runType())) {
                localRuns.execute(run);
                runs.notifyRun(run.sessionId(), run.runId(), run.stepId(), "succeeded", "run.succeeded");
                return;
            }

            var envelope = call(run);

            if (envelope.isFailed()) {
                failRun(run, Map.of("code", "skill_execution_failed", "title", "Skill execution failed",
                        "detail", envelope.error() != null ? envelope.error().detail() : "unknown"));
                return;
            }

            var milestoneId = milestoneOf(run);
            var result = persister.persist(run, envelope, milestoneId);

            log.info("Run {} succeeded: {} artifacts, {} new events{}", run.runId(), result.artifacts(),
                    result.newEvents(), result.snapshotId() != null ? ", snapshot " + result.snapshotId() : "");
            runs.notifyRun(run.sessionId(), run.runId(), run.stepId(), "succeeded", "run.succeeded");

        } catch (EnvelopePersister.LateResultException e) {
            // The run went terminal while the orchestrator was working. Nothing was
            // persisted; the transaction unwound. Not an error.
            log.debug("{}", e.getMessage());

        } catch (OrchestratorException.Timeout e) {
            timeOutRun(run, e);

        } catch (OrchestratorException.Failed e) {
            var p = e.problem();
            failRun(run, problemMap(p));

        } catch (Exception e) {
            log.error("Run {} failed unexpectedly", run.runId(), e);
            failRun(run, Map.of("code", "internal_error", "title", "Internal error",
                    "detail", String.valueOf(e.getMessage())));
        }
    }

    private Envelope call(Run run) {
        var context = contexts.build(run.sessionId());
        var session = sessions.find(run.sessionId()).orElseThrow();
        long deadlineMs = props.orchestrator().timeout().toMillis();

        var skillName = (String) run.request().get("skill_name");
        if (skillName != null) {
            return orchestrator.execute(skillName, new InvokeRequest.Execute(
                    run.sessionId().toString(), session.correlationId(), run.runId().toString(),
                    asMap(run.request().get("input")), context, deadlineMs));
        }

        return orchestrator.invoke(new InvokeRequest.Invoke(
                run.sessionId().toString(), session.correlationId(), run.runId().toString(),
                (String) run.request().get("message"), (String) run.request().get("skill_hint"),
                context, deadlineMs));
    }

    /**
     * timed_out is not failed. The outcome is UNKNOWN — the orchestrator may still be
     * working, and the skill may have completed. Never auto-retried (invariant 8); the
     * UI says "may have partially completed; verify before retrying".
     */
    private void timeOutRun(Run run, OrchestratorException.Timeout e) {
        log.warn("Run {} timed out after {}: outcome is UNKNOWN", run.runId(), props.orchestrator().timeout());
        var error = new LinkedHashMap<String, Object>(problemMap(e.problem()));
        error.put("detail", "The orchestrator did not respond within %s. The outcome is UNKNOWN: the skill "
                .formatted(props.orchestrator().timeout())
                + "may have completed. Verify before retrying.");
        if (runs.timeOut(run.runId(), workerId, error)) {
            steps.updateStatus(run.stepId(), "failed");
            runs.notifyRun(run.sessionId(), run.runId(), run.stepId(), "timed_out", "run.timed_out");
        }
    }

    private void failRun(Run run, Map<String, Object> error) {
        if (runs.fail(run.runId(), workerId, error)) {
            steps.updateStatus(run.stepId(), "failed");
            runs.notifyRun(run.sessionId(), run.runId(), run.stepId(), "failed", "run.failed");
        }
    }

    private boolean isCancelled(Run run) {
        var current = runs.find(run.runId()).orElse(null);
        if (current == null || !current.cancelRequested()) {
            return false;
        }
        if (runs.cancel(run.runId(), workerId)) {
            steps.updateStatus(run.stepId(), "skipped");
            runs.notifyRun(run.sessionId(), run.runId(), run.stepId(), "cancelled", "run.cancelled");
        }
        return true;
    }

    private static UUID milestoneOf(Run run) {
        var raw = run.request().get("milestone_id");
        return raw == null ? null : UUID.fromString(String.valueOf(raw));
    }

    private static Map<String, Object> problemMap(com.autwit.copilot.orchestrator.dto.Problem p) {
        if (p == null) {
            return Map.of("code", "unknown", "title", "Unknown orchestrator error");
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("type", p.type());
        m.put("title", p.title());
        m.put("status", p.status());
        m.put("code", p.code());
        m.put("detail", p.detail());
        m.put("skill_name", p.skillName());
        // Advisory only. We never act on it automatically for a mutating skill (ADR-001).
        m.put("retryable", p.retryable());
        m.values().removeIf(java.util.Objects::isNull);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(IDLE_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    String workerId() {
        return workerId;
    }
}
