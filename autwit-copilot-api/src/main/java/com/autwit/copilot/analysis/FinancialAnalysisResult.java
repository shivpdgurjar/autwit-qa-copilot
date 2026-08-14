package com.autwit.copilot.analysis;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The orchestrator's financial analysis result, mirroring their {@code MergedAnalysis}
 * (`financial/application/merge.ts`). camelCase wire, like the request.
 *
 * <p>Deterministic findings are authoritative; the model explains but never overrides one,
 * and any contradiction it makes is preserved in {@link #conflicts} rather than resolved.
 * {@link #aiAnalysisStatus} is {@code OK | UNAVAILABLE | INVALID_OUTPUT} — an OpenAI outage
 * degrades to the deterministic verdict, it never fails the analysis.
 *
 * <p>{@code @JsonIgnoreProperties} because we take the fields we persist and let the
 * mode-specific extras (stateComparisons, snapshotSummary, unresolvedTransitions) pass —
 * a shape we don't consume must not break deserialization.
 *
 * @param overallStatus the verdict: PASS | PASS_WITH_WARNINGS | FAIL | NOT_VERIFIABLE.
 * @param responseId    the OpenAI chaining token to store as {@code latest_response_id} for
 *                      a follow-up; null when chaining is off or the model was unavailable.
 *
 * <p>The financial-forensics structures ({@link #financialNarrative}, {@link
 * #derivedFinancialFacts}) are carried as raw {@link JsonNode}: they are open, extensible
 * shapes (component/delta arrays, causal chains) whose whole point is to admit new financial
 * components with no code change here, so we keep them generic rather than modelling deep
 * Java records. Any of the five may be null — an older stored analysis, or a Luna run that
 * produced no narrative, deserializes fine.
 *
 * @param modelTier            LUNA | TERRA — which tier the complexity router chose.
 * @param routingScore         the complexity score behind that choice.
 * @param routingSignals       the human-readable signals that contributed to the score.
 * @param financialNarrative   the model's generic state/transition/causal-chain narrative.
 * @param derivedFinancialFacts the deterministic derived-facts layer sent to the model.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinancialAnalysisResult(
        String analysisId,
        String analysisMode,
        String orderNumber,
        String overallStatus,
        String confidence,
        String executiveSummary,
        List<Finding> findings,
        List<String> missingInformation,
        String aiAnalysisStatus,
        String aiUnavailableReason,
        String responseId,
        String ruleVersion,
        String promptVersion,
        String model,
        String modelTier,
        Double routingScore,
        List<String> routingSignals,
        JsonNode financialNarrative,
        JsonNode derivedFinancialFacts) {

    /** One finding, mirroring their {@code Finding}. AI findings carry an {@code AI/} rule-id prefix. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Finding(
            String ruleId,
            String category,
            String status,
            String severity,
            String stateLabel,
            String lineNumber,
            String itemId,
            String expected,
            String actual,
            String difference,
            String formula,
            String explanation,
            String recommendedAction) {
    }
}
