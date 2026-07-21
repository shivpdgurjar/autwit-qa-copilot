package com.autwit.copilot.analysis;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
        String model) {

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
