package com.autwit.copilot.analysis;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Replays a deterministic financial-analysis result under the {@code fake} profile — the
 * same role {@code FakeOrchestratorClient} plays for the skill surface, so the UI and the
 * tests can drive the whole assemble → run → persist path without a live orchestrator or an
 * OpenAI key.
 *
 * <p>The canned result is a small but real {@code MergedAnalysis}: one FAIL finding and the
 * AI layer marked UNAVAILABLE (the honest state with no key), so tests exercise the
 * degrade path and the finding-persistence mapping rather than an all-green no-op.
 */
@Component
@Profile("fake")
public class FakeFinancialAnalysisClient implements FinancialAnalysisClient {

    @Override
    public FinancialAnalysisResult analyzeSnapshot(FinancialAnalysisRequest request) {
        return canned(request, "SNAPSHOT_SANCTITY");
    }

    @Override
    public FinancialAnalysisResult analyzeLifecycle(FinancialAnalysisRequest request) {
        return canned(request, "LIFECYCLE_COMPARISON");
    }

    private static FinancialAnalysisResult canned(FinancialAnalysisRequest request, String mode) {
        var finding = new FinancialAnalysisResult.Finding(
                "ARITH-LINE-EXTENDED-PRICE-001", "ARITHMETIC", "FAIL", "ERROR",
                "order snapshot", "1", null, "24.00", "26.00", "2.00",
                "unitPrice × activeQuantity", "Line extended price does not match unit price times quantity.",
                "Recompute the line extended price.");
        return new FinancialAnalysisResult(
                request.analysisId(), mode, request.orderNumber(),
                "FAIL", "HIGH", "One arithmetic inconsistency on line 1 (fake profile).",
                List.of(finding), List.of(),
                "UNAVAILABLE", "No OPENAI_API_KEY configured (fake profile).",
                // echoed chaining token + the versions the fake "ran under"
                "resp-fake-" + request.analysisId(),
                "oms-financial-rules-v1.1", "oms-financial-validator-v1.0", "fake");
    }
}
