package com.autwit.copilot.orchestrator;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs the copilot-api ↔ orchestrator HTTP exchange to the file log.
 *
 * <p>This exists for a specific situation: the orchestrator needs upstream APIs that are
 * not reachable from every machine, so an integration run happens on one laptop and is
 * diagnosed on another from a committed log. Whatever is not written here cannot be
 * recovered later — there is no attaching a debugger after the fact.
 *
 * <p>So the bias is toward recording the exchange rather than summarising it. The wire
 * body is what the two implementations actually agreed or disagreed about; a message
 * saying "invoke failed" is not enough to tell which side was wrong, and that question
 * has now come up repeatedly (the v0.1.3 topic string, the v0.1.5 audit, the
 * null-dropping {@code content_hash} defect).
 *
 * <h2>Two things are deliberately not literal</h2>
 *
 * <b>The token is never written.</b> {@code Authorization} is replaced with a shape
 * description — enough to tell "no token configured" from "a token was sent", which is a
 * real diagnostic given copilot-api currently defaults {@code ORCHESTRATOR_TOKEN} to
 * empty and the orchestrator now fails closed (SKILL_CONTRACT §1, v0.1.6). An empty
 * token produces {@code Bearer } and a 401 that looks like a network problem if you
 * cannot see which it was.
 *
 * <p><b>Bodies are truncated.</b> §6.1 allows an 8MB artifact body and a response may
 * carry several. A log that cannot be committed is a log nobody reads. The truncation is
 * always announced with the full length, so a body that mattered is visibly cut rather
 * than silently short — if a {@code content_hash} dispute needs the exact bytes, the
 * declared and computed hashes are logged by {@code ArtifactService} regardless of
 * truncation.
 */
public final class WireLog {

    /** Its own logger so an integration run can raise this to DEBUG without the rest. */
    private static final Logger log = LoggerFactory.getLogger("com.autwit.copilot.orchestrator.wire");

    /**
     * Generous enough for an envelope's structure — status, invocations, cursors, event
     * descriptors, findings — which is what diagnosis needs. Artifact bodies are what
     * blow past it, and those are exactly what hashes cover instead.
     */
    private static final int MAX_BODY = 16_384;

    private WireLog() {
    }

    public static boolean enabled() {
        return log.isDebugEnabled();
    }

    public static void request(String method, String baseUrl, String path, String correlationId,
            String runId, boolean tokenPresent, String body) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("--> {} {}{} correlation_id={} run_id={} authorization={}\n{}",
                method, baseUrl, path, correlationId, runId,
                tokenPresent ? "Bearer <redacted>" : "Bearer <EMPTY - no token configured>",
                truncate(body));
    }

    public static void response(String path, int status, String runId, String body) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("<-- {} HTTP {} run_id={}\n{}", path, status, runId, truncate(body));
    }

    /** Failures are worth recording whether or not DEBUG is on. */
    public static void failure(String path, String runId, String reason) {
        log.warn("<-- {} FAILED run_id={}: {}", path, runId, reason);
    }

    static String truncate(String body) {
        if (body == null) {
            return "<no body>";
        }
        var bytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (body.length() <= MAX_BODY) {
            return body;
        }
        return body.substring(0, MAX_BODY)
                + "\n... [truncated: %d chars / %d bytes total, showing first %d]"
                        .formatted(body.length(), bytes, MAX_BODY);
    }
}
