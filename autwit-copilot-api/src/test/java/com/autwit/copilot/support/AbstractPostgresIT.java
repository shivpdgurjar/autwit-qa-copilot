package com.autwit.copilot.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
@SpringBootTest
@ActiveProfiles("fake")
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
