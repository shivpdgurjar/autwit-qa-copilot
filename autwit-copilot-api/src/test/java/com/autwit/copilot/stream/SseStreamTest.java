package com.autwit.copilot.stream;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.autwit.copilot.support.AbstractPostgresIT;
import com.autwit.copilot.support.RunFixtures;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build order step 4's done-when: "two tabs both see run.succeeded".
 *
 * <p>Deliberately end-to-end over real HTTP rather than against SseHub directly. The
 * thing worth proving is the whole chain — worker → pg_notify → the pinned LISTEN
 * connection → the hub → two open sockets — because every interesting way this breaks
 * lives between the components rather than inside one.
 *
 * <p>Runs with the worker on, so the run really executes and really NOTIFYs. That is
 * declared below rather than inherited: {@code AbstractPostgresIT} parks the worker for
 * everyone else, because a background thread claiming runs out from under a test reads
 * as a queue bug and is not one. This is the one suite that wants the opposite, so it
 * asks — nothing here drives {@code pollOnce()}, and with the worker parked these tests
 * wait 30 seconds for a lifecycle that never starts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("all")
@TestPropertySource(properties = "autwit.run.worker-concurrency=4")
class SseStreamTest extends AbstractPostgresIT {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @LocalServerPort
    private int port;

    @Autowired
    private RunFixtures fixtures;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Tab> tabs = new CopyOnWriteArrayList<>();

    private UUID sessionId;

    @BeforeEach
    void setUp() {
        fixtures.clearQueue();
        sessionId = fixtures.newSession();
    }

    @AfterEach
    void closeTabs() {
        tabs.forEach(Tab::close);
        tabs.clear();
    }

    @Test
    void twoTabsBothSeeRunSucceeded() {
        var tabA = openTab();
        var tabB = openTab();

        // Both EventSources are connected before anything happens -- otherwise the test
        // could pass by racing the open against the event.
        Awaitility.await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(tabA.sawEvent("stream.open")).isTrue();
            assertThat(tabB.sawEvent("stream.open")).isTrue();
        });

        postMessage("I created order XXXX");

        Awaitility.await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(tabA.sawEvent("run.succeeded")).as("tab A").isTrue();
            assertThat(tabB.sawEvent("run.succeeded")).as("tab B").isTrue();
        });
    }

    @Test
    void aTabSeesTheFullRunLifecycle() {
        var tab = openTab();
        Awaitility.await().atMost(PATIENCE).until(() -> tab.sawEvent("stream.open"));

        postMessage("I created order XXXX");

        Awaitility.await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(tab.sawEvent("run.queued")).isTrue();
            assertThat(tab.sawEvent("run.started")).isTrue();
            assertThat(tab.sawEvent("run.succeeded")).isTrue();
        });
    }

    @Test
    void eventsAreThinAndCarryTheIdsNeededToRefetch() {
        var tab = openTab();
        Awaitility.await().atMost(PATIENCE).until(() -> tab.sawEvent("stream.open"));

        postMessage("I created order XXXX");
        Awaitility.await().atMost(PATIENCE).until(() -> tab.sawEvent("run.succeeded"));

        // Invariant 4: the payload is a hint. It carries ids so the UI knows WHAT to
        // refetch, and no state the UI could mistake for truth.
        var payload = tab.dataFor("run.succeeded");
        assertThat(payload).contains(sessionId.toString()).contains("\"type\":\"run.succeeded\"");
        assertThat(payload).doesNotContain("artifacts").doesNotContain("body");
    }

    @Test
    void aTabOnOneSessionDoesNotSeeAnotherSessionsRuns() {
        var otherSession = fixtures.newSession();
        var tab = openTab(otherSession);
        Awaitility.await().atMost(PATIENCE).until(() -> tab.sawEvent("stream.open"));

        postMessage("I created order XXXX"); // on sessionId, not otherSession

        // Wait for the run to actually finish, then assert the other tab stayed quiet.
        Awaitility.await().atMost(PATIENCE).until(this::runsFinished);
        assertThat(tab.sawEvent("run.succeeded")).isFalse();
    }

    @Test
    void streamingAnUnknownSessionIs404() throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(uri("/api/v1/sessions/" + UUID.randomUUID() + "/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // Better than opening a stream that can never emit anything.
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void thePollFallbackReportsActiveRunsWhenTheStreamIsUnavailable() throws Exception {
        // Invariant 4's other half: "If the connection drops, the UI falls back to
        // polling GET /sessions/{id}/runs?active=true." No SSE subscriber here at all.
        postMessage("I created order XXXX");

        Awaitility.await().atMost(PATIENCE).until(this::runsFinished);

        var active = get("/api/v1/sessions/" + sessionId + "/runs?active=true");
        assertThat(active).isEqualTo("{\"runs\":[]}");

        var all = get("/api/v1/sessions/" + sessionId + "/runs");
        assertThat(all).contains("succeeded");
    }

    // ---------------------------------------------------------------- helpers

    private boolean runsFinished() throws Exception {
        return get("/api/v1/sessions/" + sessionId + "/runs?active=true").equals("{\"runs\":[]}");
    }

    private Tab openTab() {
        return openTab(sessionId);
    }

    private Tab openTab(UUID session) {
        var tab = new Tab(http, uri("/api/v1/sessions/" + session + "/stream"));
        tabs.add(tab);
        return tab;
    }

    private void postMessage(String message) {
        try {
            var response = http.send(HttpRequest.newBuilder(uri("/api/v1/sessions/" + sessionId + "/messages"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"%s\"}".formatted(message)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("submit-only returns 202").isEqualTo(202);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** A browser tab: reads text/event-stream on a background thread. */
    private static final class Tab {

        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final Thread reader;
        private volatile HttpResponse<java.util.stream.Stream<String>> response;

        Tab(HttpClient http, URI uri) {
            this.reader = new Thread(() -> {
                try {
                    response = http.send(
                            HttpRequest.newBuilder(uri).header("Accept", "text/event-stream")
                                    .timeout(Duration.ofMinutes(2)).GET().build(),
                            HttpResponse.BodyHandlers.ofLines());
                    response.body().forEach(lines::add);
                } catch (Exception e) {
                    // Expected on close(); the assertions read what arrived before then.
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        boolean sawEvent(String name) {
            return lines.stream().anyMatch(l -> l.equals("event:" + name));
        }

        /** The data line following the named event. */
        String dataFor(String name) {
            for (int i = 0; i < lines.size() - 1; i++) {
                if (lines.get(i).equals("event:" + name) && lines.get(i + 1).startsWith("data:")) {
                    return lines.get(i + 1).substring("data:".length());
                }
            }
            return "";
        }

        void close() {
            reader.interrupt();
            try {
                reader.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
