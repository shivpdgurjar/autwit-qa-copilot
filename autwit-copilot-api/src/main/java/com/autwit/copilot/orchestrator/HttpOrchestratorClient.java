package com.autwit.copilot.orchestrator;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import com.autwit.copilot.config.AutwitProperties;
import com.autwit.copilot.orchestrator.dto.Envelope;
import com.autwit.copilot.orchestrator.dto.InvokeRequest;
import com.autwit.copilot.orchestrator.dto.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The real orchestrator (SKILL_CONTRACT 0.1.1).
 *
 * <p>Build order step 8 — the swap. Everything else in the system was built and tested
 * against {@link FakeOrchestratorClient} replaying the §10 fixtures, which is the
 * entire point of the port: this class is the only thing that had to wait for the
 * other session.
 *
 * <h2>The deadline</h2>
 *
 * 10 minutes, hard, on the client itself (invariant 5, §9). It must stay below
 * {@code run.lease} (12m) or a slow-but-alive run gets reclaimed and re-executed while
 * still running — {@code ConfigAssertions} fails startup if anyone edits one without
 * the other.
 *
 * <p>A timeout is not a failure. It raises {@link OrchestratorException.Timeout}, the
 * run lands {@code timed_out} rather than {@code failed}, and it is never auto-retried:
 * the outcome is UNKNOWN, because the orchestrator may well still be working. There is
 * no {@code DELETE /invoke} — cancellation is cooperative — so a late result is
 * discarded by the persister instead.
 */
@Component
@Profile("!fake")
public class HttpOrchestratorClient implements OrchestratorClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOrchestratorClient.class);

    /**
     * Short: this is a TCP handshake to a known host, not the work. The 10m deadline is
     * for the response, and conflating the two would make a dead orchestrator take ten
     * minutes to report itself.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** The catalog is polled every 60s and must never hold a worker up. */
    private static final Duration CATALOG_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient invokeClient;
    private final RestClient catalogClient;
    private final ObjectMapper mapper;
    private final AutwitProperties props;

    public HttpOrchestratorClient(RestClient.Builder builder, ObjectMapper mapper, AutwitProperties props) {
        this.mapper = mapper;
        this.props = props;

        var orchestrator = props.orchestrator();
        this.invokeClient = build(builder.clone(), mapper, orchestrator, orchestrator.timeout());
        this.catalogClient = build(builder.clone(), mapper, orchestrator, CATALOG_TIMEOUT);

        log.info("HttpOrchestratorClient targeting {} with a {} deadline",
                orchestrator.baseUrl(), orchestrator.timeout());
    }

    /**
     * @param mapper pinned onto the converter deliberately, rather than trusting
     *               whatever ObjectMapper the injected builder happens to carry.
     *               <p>
     *               This is not defensive tidying. The contract is snake_case
     *               throughout; our ObjectMapper is configured for that, and a plain
     *               {@code RestClient.builder()} is not — so without this the client
     *               serialises {@code runId} and {@code sessionContext} rather than
     *               {@code run_id} and {@code session_context}, the orchestrator sees
     *               none of the fields it requires, and every single call fails. Spring Boot's
     *               auto-configured builder would supply the right converter today, so
     *               this would work by luck and break the moment someone constructed
     *               the client with a bare builder. The contract is too important to
     *               leave to the ambient configuration of a bean we did not create.
     */
    private static RestClient build(RestClient.Builder builder, ObjectMapper mapper,
            AutwitProperties.Orchestrator props, Duration readTimeout) {

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());

        return builder
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(new MappingJackson2HttpMessageConverter(mapper));
                })
                .defaultHeader("Authorization", "Bearer " + props.token())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public Envelope invoke(InvokeRequest.Invoke request) {
        return call("/invoke", request, request.runId());
    }

    @Override
    public Envelope execute(String skillName, InvokeRequest.Execute request) {
        return call("/skills/%s/execute".formatted(skillName), request, request.runId());
    }

    /**
     * Serialises the outgoing request for the wire log.
     *
     * <p>Wrapped so a logging failure can never fail a run: a body that will not
     * serialise here would fail in the client a moment later with a better message, and
     * losing the run to the diagnostic rather than the defect would be its own bug.
     */
    private void logRequest(String path, String correlationId, String runId, Object body) {
        if (!WireLog.enabled()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            json = "<could not serialise request for logging: " + e.getMessage() + ">";
        }
        WireLog.request("POST", props.orchestrator().baseUrl(), path, correlationId, runId,
                !props.orchestrator().token().isBlank(), json);
    }

    @Override
    public Catalog skills() {
        try {
            return catalogClient.get().uri("/skills").retrieve().body(Catalog.class);
        } catch (ResourceAccessException e) {
            // A stale catalog degrades the palette; it must not take the app down.
            // SkillCatalogSync keeps the previous projection.
            throw new OrchestratorException.Failed(
                    "Could not reach the orchestrator catalog: " + e.getMessage(),
                    problem("catalog_unreachable", "Catalog unreachable", e.getMessage()), e);
        }
    }

    private Envelope call(String path, Object body, String runId) {
        var correlationId = correlationOf(body);
        logRequest(path, correlationId, runId, body);
        try {
            return invokeClient.post()
                    .uri(path)
                    // §3: MUST be propagated to every downstream service the skills touch.
                    .header("X-Autwit-Correlation-Id", correlationId)
                    .body(body)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw toException(response, path, runId);
                        }
                        // Read once into a string: the stream cannot be consumed twice,
                        // and the raw bytes are what a cross-machine diagnosis needs.
                        var raw = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        WireLog.response(path, response.getStatusCode().value(), runId, raw);
                        return mapper.readValue(raw, Envelope.class);
                    });

        } catch (OrchestratorException e) {
            throw e;

        } catch (ResourceAccessException e) {
            // A read timeout surfaces here rather than as a status code. It is the one
            // failure that must NOT become `failed`.
            if (isTimeout(e)) {
                throw new OrchestratorException.Timeout(
                        "Orchestrator did not respond within %s".formatted(props.orchestrator().timeout()), e);
            }
            throw new OrchestratorException.Failed(
                    "Could not reach the orchestrator: " + e.getMessage(),
                    problem("upstream_unavailable", "Upstream unavailable", e.getMessage()), e);

        } catch (Exception e) {
            throw new OrchestratorException.Failed(
                    "Orchestrator call failed: " + e.getMessage(),
                    problem("internal_error", "Orchestrator call failed", String.valueOf(e.getMessage())), e);
        }
    }

    /**
     * §8's RFC 7807 body, or a synthesised one when the orchestrator returns something
     * that is not Problem-shaped.
     *
     * <p>{@code deadline_exceeded} is mapped to Timeout even when it arrives as a
     * status code: §8's table says that code means timed_out, and a server that
     * self-reports a deadline breach is telling us the same thing our own clock would
     * have — outcome unknown.
     */
    private OrchestratorException toException(org.springframework.http.client.ClientHttpResponse response,
            String path, String runId) throws IOException {

        Problem problem;
        try {
            problem = mapper.readValue(response.getBody(), Problem.class);
        } catch (Exception e) {
            problem = problem("upstream_error",
                    "Orchestrator returned " + response.getStatusCode().value(),
                    "The response was not RFC 7807. copilot-api cannot tell what went wrong.");
        }

        if (problem.code() == null) {
            problem = new Problem(problem.type(), problem.title(), problem.status(), "upstream_error",
                    problem.detail(), path, runId, problem.skillName(), problem.retryable());
        }

        if (problem.isDeadlineExceeded()) {
            return new OrchestratorException.Timeout(
                    "Orchestrator reported deadline_exceeded: " + problem.detail(), null);
        }

        log.warn("Orchestrator {} returned {} ({})", path, response.getStatusCode().value(), problem.code());
        return new OrchestratorException.Failed(
                "Orchestrator returned %s: %s".formatted(problem.code(), problem.detail()), problem, null);
    }

    /**
     * A read timeout arrives wrapped, and the wrapper differs by request factory —
     * SocketTimeoutException here, HttpTimeoutException under the JDK client. Getting
     * this wrong would land a timed-out run as `failed`, which asserts the work did not
     * happen when nobody knows whether it did.
     */
    private static boolean isTimeout(Throwable e) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static String correlationOf(Object body) {
        return switch (body) {
            case InvokeRequest.Invoke i -> i.correlationId();
            case InvokeRequest.Execute e -> e.correlationId();
            default -> null;
        };
    }

    private static Problem problem(String code, String title, String detail) {
        return new Problem("https://autwit/errors/" + code.replace('_', '-'), title, 502, code, detail,
                null, null, null, false);
    }
}
