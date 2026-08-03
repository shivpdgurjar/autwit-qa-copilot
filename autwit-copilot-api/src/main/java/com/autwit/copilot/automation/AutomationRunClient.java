package com.autwit.copilot.automation;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Talks to the AUTWIT run service.
 *
 * <p>Responses are passed through as {@link JsonNode} rather than remapped into copilot
 * types. The run registry's shape is owned on the other side of this boundary, and
 * mirroring it here would mean a copilot release every time autwit added a field — for a
 * proxy that adds no meaning of its own.</p>
 *
 * <p>The one status that carries meaning here is <b>409</b>: the per-environment soft gate.
 * It must reach the browser intact, body and all, so the UI can name the run already in
 * flight and let the tester decide. Flattening it into a generic error would turn a
 * deliberate question into an unexplained failure.</p>
 */
@Component
public class AutomationRunClient {

    private static final Logger log = LoggerFactory.getLogger(AutomationRunClient.class);

    private final RestClient http;
    private final AutomationProperties props;

    public AutomationRunClient(RestClient.Builder builder, AutomationProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(props.getTimeout());

        RestClient.Builder configured = builder.clone()
                .requestFactory(factory)
                .baseUrl(props.getBaseUrl() == null ? "" : props.getBaseUrl());
        if (props.getToken() != null && !props.getToken().isBlank()) {
            configured = configured.defaultHeader("Authorization", "Bearer " + props.getToken());
        }
        this.http = configured.build();
    }

    /** Result of a proxied call: the upstream status and its body, both preserved. */
    public record UpstreamResponse(HttpStatus status, JsonNode body) {

        public boolean isConfirmationRequired() {
            return status == HttpStatus.CONFLICT;
        }
    }

    public UpstreamResponse submit(Map<String, Object> request) {
        return exchange(() -> http.post()
                .uri("/api/autwit/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(status -> true, (req, res) -> { /* statuses are data here, not errors */ })
                .toEntity(JsonNode.class));
    }

    public UpstreamResponse list(String env, String status, String startedBy, int limit) {
        String uri = UriComponentsBuilder.fromPath("/api/autwit/runs")
                .queryParamIfPresent("env", java.util.Optional.ofNullable(blankToNull(env)))
                .queryParamIfPresent("status", java.util.Optional.ofNullable(blankToNull(status)))
                .queryParamIfPresent("startedBy", java.util.Optional.ofNullable(blankToNull(startedBy)))
                .queryParam("limit", limit)
                .build()
                .toUriString();
        return get(uri);
    }

    public UpstreamResponse get(UUID runId) {
        return get("/api/autwit/runs/" + runId);
    }

    public UpstreamResponse log(UUID runId) {
        return get("/api/autwit/runs/" + runId + "/log");
    }

    public UpstreamResponse cancel(UUID runId) {
        return exchange(() -> http.post()
                .uri("/api/autwit/runs/" + runId + "/cancel")
                .retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(JsonNode.class));
    }

    /**
     * The single-file Allure report, fetched as text so it can be served same-origin.
     *
     * <p>Proxying rather than redirecting is what lets the browser embed it: a report on
     * another origin cannot be framed without a CSP arrangement, and reports can carry the
     * member and card data {@code api.fetch_order} persists, so they should not be
     * reachable without copilot's authentication anyway.</p>
     */
    public String report(UUID runId) {
        try {
            return http.get()
                    .uri("/api/autwit/runs/" + runId + "/report")
                    .accept(MediaType.TEXT_HTML)
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException e) {
            throw new AutomationUnavailableException("The AUTWIT run service is unreachable", e);
        }
    }

    private UpstreamResponse get(String uri) {
        return exchange(() -> http.get()
                .uri(uri)
                .retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(JsonNode.class));
    }

    private UpstreamResponse exchange(java.util.function.Supplier<org.springframework.http.ResponseEntity<JsonNode>> call) {
        if (!props.isEnabled()) {
            throw new AutomationUnavailableException(
                    "No AUTWIT run service is configured (autwit.automation.base-url)", null);
        }
        try {
            var response = call.get();
            return new UpstreamResponse(HttpStatus.valueOf(response.getStatusCode().value()), response.getBody());
        } catch (ResourceAccessException e) {
            // A dead run service is an unreachable upstream, not a copilot bug.
            log.warn("AUTWIT run service unreachable at {}: {}", props.getBaseUrl(), e.getMessage());
            throw new AutomationUnavailableException("The AUTWIT run service is unreachable", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
