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
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls the orchestrator's financial-analysis API
 * (`POST /v1/financial-analysis/{snapshot,lifecycle}`).
 *
 * <p><b>A separate surface from the SKILL_CONTRACT client.</b> {@code HttpOrchestratorClient}
 * speaks snake_case (`run_id`, `session_context`) and returns the skill result envelope; the
 * financial API speaks <b>camelCase</b> (`analysisId`, `stateType`) and returns a
 * {@code MergedAnalysis}. Different naming, different shape, different route — so this is its
 * own client with its own mapper rather than an overload of the other.
 *
 * <h2>The mapper retains nulls — deliberately</h2>
 *
 * Inclusion is {@link JsonInclude.Include#ALWAYS}, not the application default of
 * {@code non_null}. A state's {@code data} is untyped evidence, and the orchestrator's rules
 * distinguish <em>absent</em> from <em>null</em> — a tax line with {@code taxName: null} is
 * "undecidable", a dropped key is a different claim. Dropping nulls here would be the same
 * defect that broke {@code ContentHasher}, one wire hop later. Null top-level optionals
 * (`previousResponseId`, `capturedAt`) serialise as JSON null, which their `AnalysisRequest`
 * accepts.
 */
@Component
public class FinancialAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(FinancialAnalysisClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient client;
    private final JsonMapper mapper;
    private final AutwitProperties props;

    public FinancialAnalysisClient(AutwitProperties props) {
        this.props = props;
        // camelCase (default naming), ISO-8601 timestamps, nulls retained.
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

        log.info("FinancialAnalysisClient targeting {} with a {} deadline",
                orchestrator.baseUrl(), orchestrator.timeout());
    }

    /** SNAPSHOT_SANCTITY — one order picture, internal-consistency verdict. */
    public FinancialAnalysisResult analyzeSnapshot(FinancialAnalysisRequest request) {
        return call("/v1/financial-analysis/snapshot", request);
    }

    /** LIFECYCLE_COMPARISON — a sequence of states, each transition validated. */
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
        try {
            return client.post()
                    .uri(path)
                    .body(body)
                    .exchange((req, response) -> {
                        var raw = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (response.getStatusCode().isError()) {
                            throw new OrchestratorException.Failed(
                                    "Financial analysis %s returned %s: %s"
                                            .formatted(path, response.getStatusCode().value(), truncate(raw)),
                                    null, null);
                        }
                        return mapper.readValue(raw, FinancialAnalysisResult.class);
                    });
        } catch (OrchestratorException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // A read timeout here is the analysis taking longer than the deadline. Unlike a
            // skill invoke there is nothing to reconcile — no side effect, no run to reap —
            // so it surfaces as a plain failure the caller can retry.
            throw new OrchestratorException.Failed(
                    "Financial analysis did not respond within %s".formatted(props.orchestrator().timeout()), null, e);
        } catch (Exception e) {
            throw new OrchestratorException.Failed("Financial analysis call failed: " + e.getMessage(), null, e);
        }
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
