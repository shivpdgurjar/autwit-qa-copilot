package com.autwit.copilot.run;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-session serialization (invariant 6).
 *
 * <p>{@code pg_try_advisory_lock(hashtext('autwit:session:' || id))}, held for the
 * run's duration. Runs in one session must not interleave — a snapshot at step 5 must
 * not race one at step 2 — while runs in different sessions stay parallel.
 *
 * <h2>Why this holds its own connection</h2>
 *
 * Session-level advisory locks belong to a <em>database session</em>, i.e. one
 * connection. Taking the lock on a pooled connection and returning it to the pool
 * would either release the lock immediately or, worse, leak it onto an unrelated
 * caller who happens to be handed the same connection next. So this checks a
 * connection out of the pool and holds it for as long as the lock is held, which for
 * a snapshot capture is up to the 10 minute orchestrator timeout.
 *
 * <p>That is a real cost: worker-concurrency of 4 means up to 4 connections doing
 * nothing but holding locks, on top of the connections their work needs. It is why
 * the Hikari pool is sized above worker-concurrency rather than at it.
 *
 * <p>The transaction-scoped variant ({@code pg_try_advisory_xact_lock}) would avoid
 * the held connection and is the wrong tool: a run spans several transactions with a
 * ten-minute HTTP call between them, and an xact lock would evaporate at the first
 * commit — exactly when we still need it.
 *
 * <p>If the worker dies outright, the connection drops and Postgres releases the lock
 * for us. That is the backstop the reaper depends on.
 */
@Component
public class SessionLocks {

    private static final Logger log = LoggerFactory.getLogger(SessionLocks.class);

    private final DataSource dataSource;

    public SessionLocks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * @return empty when another worker holds this session's lock. The caller must
     *         then release the run back to the queue and pick up a different one —
     *         never block, or one busy session would stall the whole worker.
     */
    public Optional<SessionLock> tryAcquire(UUID sessionId) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            // Deliberately outside any Spring-managed transaction: this connection is
            // ours for the duration, not the current transaction's.
            connection.setAutoCommit(true);

            boolean acquired;
            try (var ps = connection.prepareStatement(
                    "select pg_try_advisory_lock(hashtext('autwit:session:' || ?))")) {
                ps.setString(1, sessionId.toString());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    acquired = rs.getBoolean(1);
                }
            }

            if (!acquired) {
                connection.close();
                return Optional.empty();
            }
            return Optional.of(new SessionLock(connection, sessionId));

        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("Failed to acquire the advisory lock for session " + sessionId, e);
        }
    }

    /** Releases the lock and returns the connection to the pool. */
    public static final class SessionLock implements AutoCloseable {

        private final Connection connection;
        private final UUID sessionId;

        private SessionLock(Connection connection, UUID sessionId) {
            this.connection = connection;
            this.sessionId = sessionId;
        }

        public UUID sessionId() {
            return sessionId;
        }

        @Override
        public void close() {
            try (connection) {
                try (var ps = connection.prepareStatement(
                        "select pg_advisory_unlock(hashtext('autwit:session:' || ?))")) {
                    ps.setString(1, sessionId.toString());
                    ps.execute();
                }
            } catch (SQLException e) {
                // Not fatal: closing the connection drops the lock anyway. Worth a warn
                // because it means the pool just lost a connection for no good reason.
                log.warn("Failed to release the advisory lock for session {}; "
                        + "the lock will be released when the connection closes", sessionId, e);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Nothing useful to do; the original failure is what matters.
            }
        }
    }
}
