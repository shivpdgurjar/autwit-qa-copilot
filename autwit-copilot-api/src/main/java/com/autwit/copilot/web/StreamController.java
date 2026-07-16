package com.autwit.copilot.web;

import java.util.UUID;

import com.autwit.copilot.session.SessionService;
import com.autwit.copilot.stream.SseHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * GET /sessions/{id}/stream.
 *
 * <p>Fire-and-forget hints sourced from Postgres LISTEN/NOTIFY. Carries no payload
 * the UI depends on: on any event the UI refetches GET /sessions/{id}, and if this
 * connection drops it falls back to polling GET /sessions/{id}/runs?active=true.
 *
 * <p>No replay. There is deliberately no stream_event table — the orchestrator
 * returns one payload at the end, so there is nothing mid-stream to replay, and the
 * timeline is queried from step/artifact/finding directly.
 *
 * <p>Requires virtual threads (invariant 12): every open tab holds a request thread
 * for up to 30 minutes.
 */
@RestController
public class StreamController {

    private final SseHub hub;
    private final SessionService sessions;

    public StreamController(SseHub hub, SessionService sessions) {
        this.hub = hub;
        this.sessions = sessions;
    }

    @GetMapping(path = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter streamSession(@PathVariable UUID sessionId) {
        // 404 an unknown session rather than opening a stream that can never emit.
        sessions.get(sessionId);
        return hub.subscribe(sessionId);
    }
}
