package com.autwit.copilot.session;

import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.ArtifactRepository;
import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.config.AutwitProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionRepository sessions;
    private final StepRepository steps;
    private final MilestoneRepository milestones;
    private final ArtifactRepository artifacts;
    private final AutwitProperties props;

    public SessionService(SessionRepository sessions, StepRepository steps, MilestoneRepository milestones,
            ArtifactRepository artifacts, AutwitProperties props) {
        this.sessions = sessions;
        this.steps = steps;
        this.milestones = milestones;
        this.artifacts = artifacts;
        this.props = props;
    }

    @Transactional
    public Session create(CreateSessionRequest req) {
        int ttlDays = req.ttlDays() != null
                ? req.ttlDays()
                : (int) props.session().defaultTtl().toDays();

        return sessions.insert(
                newCorrelationId(req.env()),
                req.testerId(),
                req.env(),
                req.title(),
                req.retentionClass() != null ? req.retentionClass() : "standard",
                ttlDays,
                req.tags(),
                req.subjects());
    }

    public Session get(UUID sessionId) {
        return sessions.find(sessionId).orElseThrow(() -> new ApiException.NotFound("session", sessionId));
    }

    /** GET /sessions/{id} — the timeline. */
    public SessionDetail detail(UUID sessionId) {
        var session = get(sessionId);
        return SessionDetail.of(
                session,
                steps.listBySession(sessionId, null),
                milestones.listBySession(sessionId),
                new SessionDetail.Counts(
                        artifacts.countBySession(sessionId),
                        sessions.countEvents(sessionId),
                        Map.of()));
    }

    public List<Session> list(String status, String testerId, String env, int limit) {
        return sessions.list(status, testerId, env, limit);
    }

    @Transactional
    public Session update(UUID sessionId, UpdateSessionRequest req) {
        return sessions.update(sessionId, req.title(), req.tags(), req.subjects(), req.retentionClass())
                .orElseThrow(() -> new ApiException.NotFound("session", sessionId));
    }

    /**
     * Propagated downstream as X-Autwit-Correlation-Id and the join key for all event
     * analysis, so it has to be unique — the column is UNIQUE NOT NULL. 32 bits of
     * randomness inside an env+day bucket is not enough to rely on alone; the
     * constraint is the guarantee, and this loop just makes a collision a non-event
     * rather than a failed request.
     */
    private String newCorrelationId(String env) {
        for (int attempt = 0; attempt < 5; attempt++) {
            var bytes = new byte[4];
            RANDOM.nextBytes(bytes);
            var candidate = "autwit-%s-%s-%s".formatted(
                    env, DAY.format(java.time.Instant.now()), HexFormat.of().formatHex(bytes));
            if (!sessions.existsByCorrelationId(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique correlation_id for env=" + env);
    }
}
