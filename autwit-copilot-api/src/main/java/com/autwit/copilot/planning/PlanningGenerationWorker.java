package com.autwit.copilot.planning;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.autwit.copilot.config.AutwitProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Dequeues and executes planning generations through Postgres SKIP LOCKED — the planning
 * analogue of {@code RunWorker}, but simpler: a generation belongs to a project, not a
 * session, so there is no per-session advisory lock to take (generations are independent and
 * safe to run concurrently).
 *
 * <p>Same {@code worker | all} profile and virtual-thread pool as {@code RunWorker}: the loops
 * spend their lives blocked on a ~60s OpenAI round trip, which is exactly what virtual threads
 * are for.
 */
@Component
@Profile("worker | all")
public class PlanningGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(PlanningGenerationWorker.class);
    private static final long IDLE_BACKOFF_MS = 500;

    private final PlanningRepository repo;
    private final PlanningGenerationRunner runner;
    private final AutwitProperties props;

    private final String workerId = "planner-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService pool;

    public PlanningGenerationWorker(PlanningRepository repo, PlanningGenerationRunner runner,
            AutwitProperties props) {
        this.repo = repo;
        this.runner = runner;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        int concurrency = Math.max(1, props.run().workerConcurrency());
        running.set(true);
        pool = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < concurrency; i++) {
            pool.submit(this::loop);
        }
        log.info("PlanningGenerationWorker {} started with concurrency {}", workerId, concurrency);
    }

    @PreDestroy
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("PlanningGenerationWorker {} draining…", workerId);
        pool.shutdown();
        try {
            var grace = lease().plusSeconds(30);
            if (!pool.awaitTermination(grace.toSeconds(), TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        log.info("PlanningGenerationWorker {} stopped", workerId);
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
                log.error("PlanningGenerationWorker {} loop error; continuing", workerId, e);
                sleepQuietly();
            }
        }
    }

    /**
     * Claims and executes at most one generation.
     *
     * <p>Public so tests can drive the queue a step at a time with worker-concurrency 0 — the
     * bean exists, no background loop runs, and the test executes exactly the generation it
     * enqueued. Returns true if a job was claimed, whatever its outcome.
     */
    public boolean pollOnce() {
        var claimed = repo.dequeueGeneration(workerId, lease());
        if (claimed.isEmpty()) {
            return false;
        }
        var gen = claimed.get();
        try {
            runner.execute(gen, workerId);
            log.info("Generation {} ({}) succeeded", gen.generationId(), gen.generationType().wire());
        } catch (Exception e) {
            log.error("Generation {} failed", gen.generationId(), e);
            var error = new LinkedHashMap<String, Object>();
            error.put("code", "generation_failed");
            error.put("title", "Planning generation failed");
            error.put("detail", String.valueOf(e.getMessage()));
            repo.failGeneration(gen.generationId(), workerId, error);
        }
        return true;
    }

    /**
     * The same lease the run queue uses — {@code ConfigAssertions} guarantees it exceeds
     * {@code orchestrator.timeout}, which is exactly the invariant a generation's ~60s call
     * needs so it is not reclaimed mid-flight. Reusing it keeps that one validated property
     * covering both queues instead of hand-approximating it here.
     */
    private Duration lease() {
        return props.run().lease();
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
