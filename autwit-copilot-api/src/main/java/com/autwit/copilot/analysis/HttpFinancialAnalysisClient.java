package com.autwit.copilot.analysis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.autwit.copilot.config.AutwitProperties;
import com.autwit.copilot.orchestrator.OrchestratorException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The real financial-analysis client (`POST /v1/financial-analysis/{snapshot,lifecycle}`).
 *
 * <p><b>A separate surface from the SKILL_CONTRACT client.</b> {@code HttpOrchestratorClient}
 * speaks snake_case (`run_id`, `session_context`) and returns the skill result envelope; the
 * financial API speaks <b>camelCase</b> (`analysisId`, `stateType`) and returns a
 * {@code MergedAnalysis}. Different naming, different shape, different route — so its own
 * client with its own mapper.
 *
 * <h2>The mapper retains nulls — deliberately</h2>
 *
 * Inclusion is {@link JsonInclude.Include#ALWAYS}, not the application default of
 * {@code non_null}. A state's {@code data} is untyped evidence, and the orchestrator's rules
 * distinguish <em>absent</em> from <em>null</em> — a tax line with {@code taxName: null} is
 * "undecidable", a dropped key is a different claim. Dropping nulls here would be the same
 * defect that broke {@code ContentHasher}, one wire hop later.
 */
@Component
@Profile("!fake")
public class HttpFinancialAnalysisClient implements FinancialAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(HttpFinancialAnalysisClient.class);

    /**
     * Its own logger so the exchange lands in the committable integration log for
     * cross-machine diagnosis, alongside the skill wire log. INFO, not DEBUG: it is one
     * PII-safe line per call (metadata + verdict), low volume, and useful without the
     * {@code integration} profile — a live financial failure on another machine is
     * diagnosed from this.
     */
    private static final Logger wire = LoggerFactory.getLogger("com.autwit.copilot.analysis.financial.wire");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient client;
    private final JsonMapper mapper;
    private final AutwitProperties props;

    public HttpFinancialAnalysisClient(AutwitProperties props) {
        this.props = props;
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.ALWAYS)
                .build();

        var orchestrator = props.orchestrator();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        // Same hard deadline as a skill call — the analysis is a ~40-57s OpenAI round trip.
        factory.setReadTimeout((int) orchestrator.timeout().toMillis());

        this.client = RestClient.builder()
                .baseUrl(orchestrator.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + orchestrator.token())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("HttpFinancialAnalysisClient targeting {} with a {} deadline",
                orchestrator.baseUrl(), orchestrator.timeout());
    }

    @Override
    public FinancialAnalysisResult analyzeSnapshot(FinancialAnalysisRequest request) {
        return call("/v1/financial-analysis/snapshot", request);
    }

    @Override
    public FinancialAnalysisResult analyzeLifecycle(FinancialAnalysisRequest request) {
        return call("/v1/financial-analysis/lifecycle", request);
    }

    private FinancialAnalysisResult call(String path, FinancialAnalysisRequest request) {
        String body;
        try {
            body = mapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialise the analysis request: " + e.getMessage(), e);
        }
        // Metadata only — NEVER the request body. The states carry the raw order picture
        // including member PII and card data (docs/KNOWN_ISSUES.md PII-1); a committable
        // integration log must not become a PII sink. This is enough to diagnose a live
        // failure cross-machine: which analysis, which mode, how many states, was a token
        // sent (an empty token is a 401 that looks like a network fault).
        wire.info("--> POST {}{} analysisId={} mode={} order={} states={} authorization={}",
                props.orchestrator().baseUrl(), path, request.analysisId(), request.analysisMode(),
                request.orderNumber(), request.states() == null ? 0 : request.states().size(),
                props.orchestrator().token().isBlank() ? "Bearer <EMPTY - no token configured>" : "Bearer <redacted>");
        try {
            var result = client.post()
                    .uri(path)
                    // §3: the correlation id is propagated to every downstream. analysisId
                    // is our stable per-analysis identifier and rides through here.
                    .header("X-Autwit-Correlation-Id", request.analysisId())
                    .body(body)
                    .exchange((req, response) -> {
                        var raw = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (response.getStatusCode().isError()) {
                            // The error body IS logged (truncated): a 400 is the validation
                            // message, not order data, and it is exactly what a cross-machine
                            // diagnosis needs.
                            wire.warn("<-- {} FAILED analysisId={}: HTTP {} {}",
                                    path, request.analysisId(), response.getStatusCode().value(), truncate(raw));
                            throw new OrchestratorException.Failed(
                                    "Financial analysis %s returned %s: %s"
                                            .formatted(path, response.getStatusCode().value(), truncate(raw)),
                                    null, null);
                        }
                        return mapper.readValue(raw, FinancialAnalysisResult.class);
                    });
            // Response summary — verdict and shape, not the finding bodies (which can echo
            // order values). Enough to see what happened.
            wire.info("<-- 200 analysisId={} verdict={} findings={} ai={} responseId={}",
                    result.analysisId(), result.overallStatus(),
                    result.findings() == null ? 0 : result.findings().size(),
                    result.aiAnalysisStatus(), result.responseId() != null ? "present" : "none");
            return result;
        } catch (OrchestratorException e) {
            throw e;
        } catch (ResourceAccessException e) {
            wire.warn("<-- {} TIMEOUT analysisId={} after {}", path, request.analysisId(),
                    props.orchestrator().timeout());
            throw new OrchestratorException.Failed(
                    "Financial analysis did not respond within %s".formatted(props.orchestrator().timeout()), null, e);
        } catch (Exception e) {
            wire.warn("<-- {} ERROR analysisId={}: {}", path, request.analysisId(), e.getMessage());
            throw new OrchestratorException.Failed("Financial analysis call failed: " + e.getMessage(), null, e);
        }
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
