package com.autwit.copilot.stream;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans thin notifications out to the browsers watching a session.
 *
 * <p>Invariant 4: "SSE is a fire-and-forget hint. On any event, the UI refetches. A
 * dropped notification must be harmless." Everything here is written on that
 * assumption — there is no replay, no buffering, and no delivery guarantee. If a send
 * fails, the emitter is dropped and the UI's poll fallback takes over.
 *
 * <p>Which is why a failed send is logged at debug and not warn: a closed tab is the
 * common case, not an incident.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    /**
     * Long, because a QA session is long and a snapshot capture alone can take ten
     * minutes with nothing to say. The heartbeat is what actually keeps it open.
     */
    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);

    private final Map<UUID, Set<SseEmitter>> bySession = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID sessionId) {
        var emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());

        bySession.computeIfAbsent(sessionId, id -> new CopyOnWriteArraySet<>()).add(emitter);

        // All three paths must deregister, or the map grows for the life of the process.
        emitter.onCompletion(() -> remove(sessionId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(sessionId, emitter);
        });
        emitter.onError(e -> remove(sessionId, emitter));

        try {
            // An immediate event flushes headers, so the browser's EventSource fires
            // onopen rather than sitting in connecting until the first real notification.
            emitter.send(SseEmitter.event()
                    .name("stream.open")
                    .data(Map.of("session_id", sessionId.toString(), "at", java.time.Instant.now().toString())));
        } catch (IOException e) {
            remove(sessionId, emitter);
            emitter.completeWithError(e);
        }

        log.debug("SSE subscriber joined session {} ({} watching)", sessionId, count(sessionId));
        return emitter;
    }

    /**
     * @param type    the SSE event name — run.queued, run.succeeded, analysis.note, …
     * @param payload thin by design. The UI refetches GET /sessions/{id} on receipt
     *                rather than applying this, so it must never carry state the UI
     *                depends on.
     */
    public void publish(UUID sessionId, String type, Object payload) {
        var emitters = bySession.get(sessionId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (var emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(type).data(payload));
            } catch (Exception e) {
                // Almost always a closed tab. Drop it and move on: the next subscriber
                // must still get the event, and truth is the session endpoint anyway.
                log.debug("Dropping a dead SSE subscriber for session {}: {}", sessionId, e.toString());
                remove(sessionId, emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * Keeps idle connections alive. Proxies and load balancers cut a stream that says
     * nothing for 30–60s, and this stream is silent for minutes at a time while a
     * snapshot runs — precisely when the tester is watching hardest.
     *
     * <p>Sent as an SSE comment, so EventSource ignores it and no handler sees a
     * phantom event.
     */
    public void heartbeat() {
        bySession.forEach((sessionId, emitters) -> {
            for (var emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (Exception e) {
                    remove(sessionId, emitter);
                    emitter.completeWithError(e);
                }
            }
        });
    }

    public int count(UUID sessionId) {
        var emitters = bySession.get(sessionId);
        return emitters == null ? 0 : emitters.size();
    }

    public int sessionCount() {
        return bySession.size();
    }

    private void remove(UUID sessionId, SseEmitter emitter) {
        bySession.computeIfPresent(sessionId, (id, emitters) -> {
            emitters.remove(emitter);
            // Prune the empty set too -- otherwise every session ever watched leaks an
            // entry for the life of the process.
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
