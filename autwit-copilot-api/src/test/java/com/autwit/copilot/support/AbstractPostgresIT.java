package com.autwit.copilot.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for every integration test that needs the real schema.
 *
 * <p>No H2. The schema uses jsonb, GIN, SKIP LOCKED, advisory locks and partial
 * indexes — H2 either rejects those or, worse, accepts them and behaves
 * differently (BUILD_BRIEF §3).
 *
 * <p>The container is a JVM-wide singleton rather than a {@code @Container}-managed
 * per-class instance: the suite in BUILD_BRIEF §9 is entirely Postgres-backed, and
 * booting one container instead of one-per-test-class is the difference between a
 * fast feedback loop and a slow one. Testcontainers' Ryuk sidecar reaps it when the
 * JVM exits, so there is nothing to stop explicitly.
 *
 * <p>Postgres 16 matches the version V1__init.sql was verified against
 * (SCHEMA_VERIFICATION.md — PostgreSQL 16.14).
 */
/*
 * Worker parked by default. RunWorker's background threads poll this very database, so
 * any test that queues a run races the application claiming it first. Tests drive the
 * queue with pollOnce(), which works at concurrency 0 by design (see its javadoc).
 *
 * The symptom, before this: RunQueueTest.sixRunsAcrossFourWorkersAreEachClaimedOnce
 * claimed 4 of 6 while every row had left `queued` — the two missing runs taken by the
 * application's own worker. That reads as a SKIP LOCKED bug and is not one. It passed in
 * isolation and failed in the full suite, because classes that set worker-concurrency
 * get their own Spring context while the rest share the default one, whose worker has
 * been polling since the first test that touched it.
 *
 * This is @TestPropertySource rather than a registry.add in the @DynamicPropertySource
 * below, and that placement is load-bearing: @DynamicPropertySource wins over
 * @TestPropertySource, so a subclass could not opt back in. SseStreamTest needs a live
 * worker and overrides this — a subclass's own @TestPropertySource takes precedence over
 * the one it inherits.
 */
@SpringBootTest
@ActiveProfiles("fake")
@TestPropertySource(properties = "autwit.run.worker-concurrency=0")
public abstract class AbstractPostgresIT {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("autwit")
                    .withUsername("autwit")
                    .withPassword("autwit");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Park the schedulers. Tests drive the reaper and the catalog sync explicitly
        // via reapNow()/syncNow(): a sweep firing on its own mid-assertion would make
        // the queue tests flaky in a way that looks like a real race.
        registry.add("autwit.run.reaper-interval", () -> "1h");
        registry.add("autwit.orchestrator.catalog-sync-interval", () -> "1h");
    }
}
