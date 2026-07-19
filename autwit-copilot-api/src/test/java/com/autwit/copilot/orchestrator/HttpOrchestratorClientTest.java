package com.autwit.copilot.orchestrator;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.autwit.copilot.config.AutwitProperties;
import com.autwit.copilot.orchestrator.dto.InvokeRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Build order step 8: the swap to the real client.
 *
 * <p>Tested over real HTTP against a real socket, serving the same §10 fixtures the
 * fake replays. That is the point: everything else in the system was proven against
 * those fixtures in-process, and this asserts the same bytes survive a network — the
 * serialisation, the headers, the deadline, and the §8 error mapping.
 *
 * <p>A stub rather than the orchestrator itself because their `/skills` + `/invoke`
 * surface does not exist yet (RATIFICATION_RESPONSE §0 — it is an adapter layer over
 * capabilities they already have). When it lands, this client points at a URL instead
 * of being written.
 */
class HttpOrchestratorClientTest {

    private static HttpServer server;
    private static String baseUrl;

    /** path -> what the stub should do next. */
    private static final Map<String, Handler> ROUTES = new ConcurrentHashMap<>();
    private static final AtomicReference<Map<String, List<String>>> LAST_HEADERS = new AtomicReference<>();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();

    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** Mirrors the app's Jackson config — snake_case in, snake_case out. */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            LAST_HEADERS.set(Map.copyOf(exchange.getRequestHeaders()));
            LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            var handler = ROUTES.get(exchange.getRequestURI().getPath());
            if (handler == null) {
                respond(exchange, 404, "{}");
                return;
            }
            handler.handle(exchange);
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    private HttpOrchestratorClient client(Duration timeout) {
        var props = new AutwitProperties(
                new AutwitProperties.Orchestrator(baseUrl, "test-token", timeout, Duration.ofSeconds(60)),
                new AutwitProperties.Run(Duration.ofMinutes(12), Duration.ofSeconds(60), 4, 1),
                new AutwitProperties.Artifact(8388608L, 33554432L),
                new AutwitProperties.Session(Duration.ofDays(7)));
        return new HttpOrchestratorClient(RestClient.builder(), MAPPER, props);
    }

    private HttpOrchestratorClient client() {
        return client(Duration.ofSeconds(10));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Serves a real §10 fixture, exactly as the orchestrator would. */
    private static void serveFixture(String path, String fixture) {
        ROUTES.put(path, exchange -> {
            var body = new String(
                    new ClassPathResource("fixtures/orchestrator/" + fixture).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            respond(exchange, 200, body);
        });
    }

    private static InvokeRequest.Invoke anInvoke(String runId) {
        return new InvokeRequest.Invoke(
                UUID.randomUUID().toString(), "autwit-qa2-20260717-abcd1234", runId,
                "I created order XXXX", null,
                new InvokeRequest.SessionContext("qa2", "priya", Map.of("order_id", "XXXX"),
                        List.of(), null, Map.of(), List.of()));
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    void invokeParsesTheEnvelopeOverRealHttp() {
        serveFixture("/invoke", "invoke_order_created.json");
        var runId = UUID.randomUUID().toString();

        var envelope = client().invoke(anInvoke(runId));

        assertThat(envelope.status()).isEqualTo("succeeded");
        assertThat(envelope.artifactsOrEmpty()).hasSize(9);
        assertThat(envelope.snapshotsOrEmpty()).singleElement()
                .satisfies(s -> assertThat(s.partsOrEmpty()).hasSize(9));
        assertThat(envelope.subjectsDiscoveredOrEmpty()).containsEntry("shipment_id", "SHP-99");
    }

    @Test
    void artifactBodiesSurviveTheWireWithTheirScaleIntact() {
        serveFixture("/invoke", "invoke_order_created.json");

        var envelope = client().invoke(anInvoke(UUID.randomUUID().toString()));
        var orders = envelope.artifactsOrEmpty().stream()
                .filter(a -> "orders".equals(a.logicalName())).findFirst().orElseThrow();

        // §6.1's own example carries money as a string, and the ratified canonical form
        // depends on scale surviving. If HTTP or Jackson mangled it, the content_hash
        // check downstream would reject every artifact.
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) orders.body();
        assertThat(rows.get(0)).containsEntry("total_amount", "1200.00");
        assertThat(orders.contentHash()).startsWith("sha256:");
        assertThat(orders.meta()).containsEntry("pk_columns", List.of("order_id"));
    }

    @Test
    void executeCallsTheSkillPath() {
        serveFixture("/skills/snapshot.capture/execute", "invoke_order_created.json");

        var envelope = client().execute("snapshot.capture", new InvokeRequest.Execute(
                UUID.randomUUID().toString(), "autwit-qa2-x", UUID.randomUUID().toString(),
                Map.of("scope", "order_flow"),
                new InvokeRequest.SessionContext("qa2", "priya", Map.of(), List.of(), null, Map.of(), List.of())));

        assertThat(envelope.artifactsOrEmpty()).hasSize(9);
    }

    @Test
    void skillsParsesTheCatalog() {
        serveFixture("/skills", "skills_catalog.json");

        var catalog = client().skills();

        // §2: the format is `v1/<12-hex>`, a content hash over the skill list — not a
        // timestamp. This assertion held the literal `2026-07-16T09:12:00Z/a3f9c1` until
        // v0.1.5, a shape the orchestrator's generator has never emitted; it was an
        // example value that reached both the contract and our fixture by hand. Do not
        // parse a date out of this.
        assertThat(catalog.catalogVersion()).isEqualTo("v1/279960341625");
        assertThat(catalog.skills()).hasSize(5);
        // The field ADR-001 trusts to decide whether a dead worker's run may be re-run.
        assertThat(catalog.skills()).filteredOn(OrchestratorClient.Skill::isMutating)
                .extracting(OrchestratorClient.Skill::skillName)
                .containsExactlyInAnyOrder("order.place", "order.fulfil");
    }

    // ---------------------------------------------------------------- the contract's headers

    @Test
    void everyCallCarriesTheBearerTokenAndTheCorrelationId() {
        serveFixture("/invoke", "invoke_order_created.json");

        client().invoke(anInvoke(UUID.randomUUID().toString()));

        var headers = LAST_HEADERS.get();
        assertThat(headers.get("Authorization")).containsExactly("Bearer test-token");
        // §3: MUST be propagated to every downstream service the skills touch. If it
        // stops here, every event this run produces is unattributable.
        assertThat(headers.get("X-autwit-correlation-id"))
                .containsExactly("autwit-qa2-20260717-abcd1234");
    }

    @Test
    void theRequestIsSnakeCasedAndCarriesTheSessionContext() {
        serveFixture("/invoke", "invoke_order_created.json");
        var runId = UUID.randomUUID().toString();

        client().invoke(anInvoke(runId));

        // §3's shape. The orchestrator is stateless: if session_context does not arrive
        // intact, "capture events since the last milestone" is unanswerable.
        assertThat(LAST_BODY.get())
                .contains("\"run_id\":\"" + runId + "\"")
                .contains("\"session_context\"")
                .contains("\"correlation_id\":\"autwit-qa2-20260717-abcd1234\"")
                .contains("\"subjects\":{\"order_id\":\"XXXX\"}")
                .doesNotContain("runId")
                // v0.1.5 removed deadline_ms — the orchestrator accepted it and never
                // read it. §9: it enforces no deadline, so ours is the only one.
                .doesNotContain("deadline_ms");
    }

    // ---------------------------------------------------------------- §8 errors

    @Test
    void anRfc7807FailureBecomesAFailedNotATimeout() {
        ROUTES.put("/invoke", exchange -> respond(exchange, 503, """
                {"type":"https://autwit/errors/upstream-unavailable","title":"Upstream unavailable",
                 "status":503,"code":"upstream_unavailable",
                 "detail":"snapshot.capture exited 3: connection refused to shipment_pg",
                 "retryable":true}
                """));

        assertThatThrownBy(() -> client().invoke(anInvoke(UUID.randomUUID().toString())))
                .isInstanceOf(OrchestratorException.Failed.class)
                .satisfies(e -> {
                    var problem = ((OrchestratorException) e).problem();
                    assertThat(problem.code()).isEqualTo("upstream_unavailable");
                    assertThat(problem.retryable()).isTrue();
                    assertThat(problem.detail()).contains("shipment_pg");
                });
    }

    @Test
    void aServerReportedDeadlineExceededIsATimeoutNotAFailure() {
        // §8's table: deadline_exceeded -> timed_out. A server that self-reports a
        // deadline breach is telling us what our own clock would have: outcome UNKNOWN.
        // Mapping this to `failed` would assert the work did not happen.
        ROUTES.put("/invoke", exchange -> respond(exchange, 504, """
                {"code":"deadline_exceeded","title":"Deadline exceeded","status":504,
                 "detail":"order.place exceeded 600000ms"}
                """));

        assertThatThrownBy(() -> client().invoke(anInvoke(UUID.randomUUID().toString())))
                .isInstanceOf(OrchestratorException.Timeout.class);
    }

    @Test
    void inputSchemaViolationSurfacesItsCode() {
        ROUTES.put("/skills/snapshot.capture/execute", exchange -> respond(exchange, 400, """
                {"code":"input_schema_violation","title":"Invalid input","status":400,
                 "detail":"scope: must be one of [order_flow, shipment_only]"}
                """));

        assertThatThrownBy(() -> client().execute("snapshot.capture", new InvokeRequest.Execute(
                UUID.randomUUID().toString(), "c", UUID.randomUUID().toString(), Map.of("scope", "nope"),
                new InvokeRequest.SessionContext("qa2", "p", Map.of(), List.of(), null, Map.of(), List.of()))))
                .isInstanceOf(OrchestratorException.Failed.class)
                .satisfies(e -> assertThat(((OrchestratorException) e).problem().code())
                        .isEqualTo("input_schema_violation"));
    }

    @Test
    void aNonProblemErrorBodyStillProducesAUsableProblem() {
        // An orchestrator behind a proxy can return an HTML 502 that no amount of
        // contract compliance prevents. It must not surface as a JSON parse error.
        ROUTES.put("/invoke", exchange -> {
            var bytes = "<html><body>502 Bad Gateway</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(502, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        assertThatThrownBy(() -> client().invoke(anInvoke(UUID.randomUUID().toString())))
                .isInstanceOf(OrchestratorException.Failed.class)
                .satisfies(e -> {
                    var problem = ((OrchestratorException) e).problem();
                    assertThat(problem.code()).isEqualTo("upstream_error");
                    assertThat(problem.detail()).contains("not RFC 7807");
                });
    }

    // ---------------------------------------------------------------- the deadline

    @Test
    void aSlowOrchestratorRaisesTimeoutNotFailed() {
        ROUTES.put("/invoke", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });

        // The distinction the whole design rests on. Timeout -> the run lands timed_out,
        // never auto-retried, because the orchestrator may still be working.
        assertThatThrownBy(() -> client(Duration.ofMillis(500)).invoke(anInvoke(UUID.randomUUID().toString())))
                .isInstanceOf(OrchestratorException.Timeout.class)
                .hasMessageContaining("did not respond within");
    }

    @Test
    void anUnreachableOrchestratorIsFailedNotTimedOut() {
        // Connection refused is knowledge: nothing ran. That is `failed`, and it must not
        // be confused with a timeout, which means nobody knows.
        var props = new AutwitProperties(
                new AutwitProperties.Orchestrator("http://localhost:1", "t", Duration.ofSeconds(5),
                        Duration.ofSeconds(60)),
                new AutwitProperties.Run(Duration.ofMinutes(12), Duration.ofSeconds(60), 4, 1),
                new AutwitProperties.Artifact(8388608L, 33554432L),
                new AutwitProperties.Session(Duration.ofDays(7)));

        assertThatThrownBy(() -> new HttpOrchestratorClient(RestClient.builder(), MAPPER, props)
                .invoke(anInvoke(UUID.randomUUID().toString())))
                .isInstanceOf(OrchestratorException.Failed.class)
                .satisfies(e -> assertThat(((OrchestratorException) e).problem().code())
                        .isEqualTo("upstream_unavailable"));
    }
}
