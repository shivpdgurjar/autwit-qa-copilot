package com.autwit.copilot.stream;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * LISTEN autwit_run, on a connection pinned outside the pool (BUILD_BRIEF §3:
 * "HikariCP + one pinned connection outside the pool for LISTEN").
 *
 * <h2>Why not a pooled connection</h2>
 *
 * LISTEN registers against a database session and lasts as long as that connection
 * does. Borrowing one from Hikari and holding it forever removes it from the pool
 * permanently — the pool would be one connection smaller than configured, for the
 * life of the process, with no indication why. Returning it instead would silently
 * cancel the LISTEN and leak the registration onto whichever caller got that
 * connection next. So this opens its own, straight from the driver.
 *
 * <p>One connection, one platform thread. Not a virtual thread: this thread exists to
 * sit blocked on a socket forever, which is the one shape where a platform thread
 * costs nothing and rules out any question of pinning the carrier.
 *
 * <p>Everything published here is a hint. Dropping a notification — because the
 * connection died, because we were mid-reconnect — is harmless by design (invariant
 * 4): the UI refetches GET /sessions/{id} on any event and falls back to polling
 * GET /sessions/{id}/runs?active=true if the stream is gone. No replay, deliberately.
 */
@Component
@Profile("api | all")
public class PgNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(PgNotificationListener.class);

    static final String CHANNEL = "autwit_run";

    /** How long getNotifications blocks before we re-check the running flag. */
    private static final int POLL_MS = 500;

    /** Backoff after a connection failure. Long enough not to spin, short enough to matter. */
    private static final long RECONNECT_BACKOFF_MS = 2000;

    private final DataSourceProperties dataSourceProperties;
    private final SseHub hub;
    private final ObjectMapper mapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;
    private volatile Connection connection;

    public PgNotificationListener(DataSourceProperties dataSourceProperties, SseHub hub, ObjectMapper mapper) {
        this.dataSourceProperties = dataSourceProperties;
        this.hub = hub;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::listen, "pg-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        // Closing the connection is what unblocks getNotifications; interrupting the
        // thread alone would not, since it is parked in a socket read.
        closeQuietly();
        if (thread != null) {
            thread.interrupt();
        }
        log.info("PgNotificationListener stopped");
    }

    private void listen() {
        while (running.get()) {
            try (var conn = open()) {
                this.connection = conn;
                try (var statement = conn.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                log.info("Listening on {}", CHANNEL);

                var pg = conn.unwrap(PGConnection.class);
                while (running.get()) {
                    var notifications = pg.getNotifications(POLL_MS);
                    if (notifications != null) {
                        for (var n : notifications) {
                            dispatch(n.getParameter());
                        }
                    }
                }
            } catch (SQLException e) {
                if (running.get()) {
                    // A dropped LISTEN is degraded, not broken: the UI polls. Reconnect
                    // and carry on rather than taking the process down.
                    log.warn("LISTEN connection failed; reconnecting in {}ms", RECONNECT_BACKOFF_MS, e);
                    sleep(RECONNECT_BACKOFF_MS);
                }
            } catch (Exception e) {
                log.error("Unexpected error in the notification listener", e);
                sleep(RECONNECT_BACKOFF_MS);
            }
        }
    }

    /** Straight from the driver — deliberately not from the pool. See the class note. */
    private Connection open() throws SQLException {
        var conn = DriverManager.getConnection(
                dataSourceProperties.determineUrl(),
                dataSourceProperties.determineUsername(),
                dataSourceProperties.determinePassword());
        conn.setAutoCommit(true);
        return conn;
    }

    private void dispatch(String payload) {
        try {
            Map<String, Object> event = mapper.readValue(payload, Map.class);
            var sessionId = UUID.fromString((String) event.get("session_id"));
            var type = (String) event.getOrDefault("type", "run.progress");
            hub.publish(sessionId, type, event);
        } catch (Exception e) {
            // A malformed payload must not kill the listener and stop every other
            // session's stream.
            log.warn("Ignoring an unparseable notification payload: {}", payload, e);
        }
    }

    private void closeQuietly() {
        var conn = this.connection;
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // Shutting down; nothing useful to do.
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean isRunning() {
        return running.get();
    }
}
