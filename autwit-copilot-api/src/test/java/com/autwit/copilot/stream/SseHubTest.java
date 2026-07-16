package com.autwit.copilot.stream;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/** Fan-out and cleanup, without a container. */
class SseHubTest {

    private final SseHub hub = new SseHub();

    @Test
    void subscribersAreTrackedPerSession() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();

        hub.subscribe(a);
        hub.subscribe(a);
        hub.subscribe(b);

        assertThat(hub.count(a)).isEqualTo(2);
        assertThat(hub.count(b)).isEqualTo(1);
    }

    @Test
    void publishingToASessionWithNoSubscribersIsHarmless() {
        // The common case once a tester closes their tab mid-run. A NOTIFY still
        // arrives; it must not blow up the listener thread.
        hub.publish(UUID.randomUUID(), "run.succeeded", java.util.Map.of("x", 1));
    }

    @Test
    void aDeadSubscriberIsDroppedAndDoesNotBlockTheOthers() throws Exception {
        var sessionId = UUID.randomUUID();
        hub.subscribe(sessionId);

        // An emitter whose client vanished: send throws.
        var broken = new FailingEmitter();
        injectInto(sessionId, broken);
        assertThat(hub.count(sessionId)).isEqualTo(2);

        hub.publish(sessionId, "run.succeeded", java.util.Map.of("type", "run.succeeded"));

        assertThat(hub.count(sessionId))
                .as("the broken subscriber is pruned, the healthy one stays")
                .isEqualTo(1);
    }

    @Test
    void heartbeatPrunesDeadSubscribersAndTheirSessionEntry() throws Exception {
        var sessionId = UUID.randomUUID();
        injectInto(sessionId, new FailingEmitter());

        hub.heartbeat();

        assertThat(hub.count(sessionId)).isZero();
        // The empty set must go too, or every session ever watched leaks a map entry for
        // the life of the process.
        assertThat(hub.sessionCount()).isZero();
    }

    // The onCompletion path -- a tab closing normally -- is not unit-testable: SseEmitter
    // only fires its completion callback when it has an async request behind it, so
    // complete() here would just flip a flag and prove nothing. SseStreamTest covers it
    // with real sockets that really close.

    /** Registers an emitter the hub did not create, so we control its send behaviour. */
    private void injectInto(UUID sessionId, SseEmitter emitter) throws Exception {
        var field = SseHub.class.getDeclaredField("bySession");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<UUID, java.util.Set<SseEmitter>>) field.get(hub);
        map.computeIfAbsent(sessionId, id -> new java.util.concurrent.CopyOnWriteArraySet<>()).add(emitter);
    }

    private static class FailingEmitter extends SseEmitter {
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("client went away");
        }

        @Override
        public void completeWithError(Throwable t) {
            // No-op: the real one needs an active request context.
        }
    }
}
