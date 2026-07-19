package com.autwit.copilot.artifact;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import java.nio.charset.StandardCharsets;

import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.config.AutwitProperties;
import com.autwit.copilot.session.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single write path for artifacts, whichever direction they arrive from: the
 * orchestrator's result envelope (SKILL_CONTRACT §6.1, persisted by the worker) or
 * a tester attaching evidence via POST.
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    /** Enough canonical JSON to see key order, whitespace and null handling. */
    private static final int HEAD_CHARS = 1_200;

    private final ArtifactRepository artifacts;
    private final SessionRepository sessions;
    private final ContentHasher hasher;
    private final AutwitProperties props;

    public ArtifactService(ArtifactRepository artifacts, SessionRepository sessions,
            ContentHasher hasher, AutwitProperties props) {
        this.artifacts = artifacts;
        this.sessions = sessions;
        this.hasher = hasher;
        this.props = props;
    }

    /**
     * The tester-facing path (POST /sessions/{id}/artifacts).
     *
     * <p>Rejects an rdbms_table without meta.pk_columns outright. Note the worker's
     * path deliberately does NOT do this: for an orchestrator-supplied artifact,
     * BUILD_BRIEF §7 requires the diff engine raise a high finding and mark the part
     * inconclusive instead. The difference is intentional. A tester typing into a
     * form should be told at the boundary; the orchestrator returning a bad artifact
     * is evidence of a contract violation, and evidence must be recorded rather than
     * dropped on the floor.
     */
    @Transactional
    public Artifact create(UUID sessionId, CreateArtifactRequest req) {
        var session = sessions.find(sessionId)
                .orElseThrow(() -> new ApiException.NotFound("session", sessionId));

        if (!session.isActive()) {
            throw new ApiException.Conflict("session_not_active",
                    "Session %s is %s; artifacts can only be attached to an active session"
                            .formatted(sessionId, session.status()));
        }

        if ("rdbms_table".equals(req.artifactType()) && !hasPkColumns(req.meta())) {
            throw new ApiException.BadRequest("pk_columns_required",
                    "meta.pk_columns is required for artifact_type=rdbms_table. The diff engine joins rows "
                            + "on it and will not guess a key (BUILD_BRIEF §7).");
        }

        return persist(sessionId, req.stepId(), req.milestoneId(), null,
                req.artifactType(), req.sourceSystem(), req.logicalName(), req.format(),
                req.body(), req.contentHash(), req.rowCount(), req.meta());
    }

    /**
     * Shared persist. Verifies integrity and size, then writes.
     *
     * @param expectedHash the sender's content_hash. Verified when present; computed
     *                     when absent. A mismatch is rejected — that is the check
     *                     that catches a truncated body, which would otherwise still
     *                     parse as valid JSON and land as a real snapshot.
     */
    @Transactional
    public Artifact persist(
            UUID sessionId, UUID stepId, UUID milestoneId, UUID runId,
            String artifactType, String sourceSystem, String logicalName, ArtifactFormat format,
            Object body, String expectedHash, Integer rowCount, Map<String, Object> meta) {

        byte[] canonical;
        try {
            canonical = hasher.canonicalBytes(format, body);
        } catch (IllegalArgumentException e) {
            throw new ApiException.BadRequest("invalid_body", e.getMessage());
        }

        long max = props.artifact().maxInlineBytes();
        if (canonical.length > max) {
            throw new ApiException.PayloadTooLarge(
                    "Artifact body is %d bytes, exceeding the %d byte inline limit. v0.1 is inline-only; "
                            .formatted(canonical.length, max)
                            + "return status=partial with a finding rather than a truncated body "
                            + "(SKILL_CONTRACT §6.1).");
        }

        var actualHash = hasher.hashBytes(canonical);
        if (expectedHash != null && !hasher.matches(expectedHash, actualHash)) {
            // Logged as well as thrown. A cross-implementation canonical-form
            // disagreement is diagnosed from the canonical bytes we produced, and by the
            // time this reaches an operator the body is gone. The prefix is the most
            // useful slice: two canonicalisations of the same body diverge at the first
            // structural difference, so the head shows key order, whitespace and — the
            // defect that actually happened — whether null-valued keys survived.
            log.error("content_hash MISMATCH for {}/{} ({}): declared={} computed={} canonical_bytes={}\n"
                            + "canonical head: {}",
                    sourceSystem, logicalName, format.wire(), expectedHash, actualHash, canonical.length,
                    head(canonical));
            throw new ApiException.BadRequest("content_hash_mismatch",
                    "content_hash does not match the body: declared %s, computed %s. The body was truncated "
                            .formatted(expectedHash, actualHash)
                            + "or altered in transit, or the sender canonicalises differently (SKILL_CONTRACT §6.1).");
        }

        log.debug("artifact ok {}/{} ({}) hash={} bytes={} declared={}",
                sourceSystem, logicalName, format.wire(), actualHash, canonical.length,
                expectedHash == null ? "<none, computed>" : "matched");

        return artifacts.insert(sessionId, stepId, milestoneId, runId, artifactType, sourceSystem,
                logicalName, format, canonical, actualHash, rowCount, meta);
    }

    /** GET /artifacts/{id} — 404 when absent, 410 when the body was purged. */
    public Artifact get(UUID artifactId) {
        var artifact = artifacts.find(artifactId)
                .orElseThrow(() -> new ApiException.NotFound("artifact", artifactId));
        if (artifact.purgedAt() != null) {
            throw new ApiException.Gone(
                    "Artifact %s body was purged by retention on %s. Metadata remains available."
                            .formatted(artifactId, artifact.purgedAt()));
        }
        return artifact;
    }

    public byte[] rawBody(UUID artifactId) {
        get(artifactId); // resolves 404 vs 410 before we touch the body columns
        return artifacts.findRawBody(artifactId)
                .orElseThrow(() -> new ApiException.NotFound("artifact body", artifactId));
    }

    public List<Artifact> list(UUID sessionId, String artifactType, UUID milestoneId, String logicalName) {
        return artifacts.listBySession(sessionId, artifactType, milestoneId, logicalName);
    }

    public ArtifactFormat formatOf(UUID artifactId) {
        return artifacts.findRef(artifactId)
                .orElseThrow(() -> new ApiException.NotFound("artifact", artifactId))
                .format();
    }

    private static boolean hasPkColumns(Map<String, Object> meta) {
        return meta != null
                && meta.get("pk_columns") instanceof List<?> pk
                && !pk.isEmpty();
    }

    /**
     * The leading canonical bytes, for a hash-mismatch report read on another machine.
     * Decoded as UTF-8 and cut by characters, so a multi-byte character at the boundary
     * cannot produce mojibake in the log.
     */
    private static String head(byte[] canonical) {
        var s = new String(canonical, StandardCharsets.UTF_8);
        return s.length() <= HEAD_CHARS ? s : s.substring(0, HEAD_CHARS) + "… [+" + (s.length() - HEAD_CHARS) + " chars]";
    }
}
