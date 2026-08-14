package com.autwit.copilot.analysis;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
                "oms-financial-rules-v1.1", "oms-financial-validator-v1.0", "fake",
                // Financial-forensics sample: a 2-state lifecycle whose narrative and derived
                // facts include an unknown REGULATORY_SURCHARGE component, so the fake profile
                // demonstrates the extensible schema/UI without any operation-specific code.
                "TERRA", 8.0,
                List.of("unexplained residual", "cross-system mismatch", "later-state correction"),
                json(FINANCIAL_NARRATIVE), json(DERIVED_FACTS));
    }

    /** Reads a canned JSON literal into a JsonNode; a literal that fails to parse is a bug here. */
    private static JsonNode json(String literal) {
        try {
            return MAPPER.readTree(literal);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("canned financial JSON is malformed", e);
        }
    }

    private static final String FINANCIAL_NARRATIVE = """
        {
          "states": [
            { "stateLabel": "before", "status": "PASS",
              "financialEquation": { "expression": "26.00 - 2.00 + 0.11 = 24.11", "expected": "24.11", "actual": "24.11", "difference": "0.00" },
              "componentSummary": [
                { "component": "EXTENDED_PRICE", "subType": null, "amount": "26.00" },
                { "component": "PROMOTION", "subType": "InstantSavings", "amount": "2.00" },
                { "component": "TAX", "subType": "SalesTax", "amount": "0.11" }
              ],
              "observations": ["State is internally consistent."],
              "evidence": [ { "stateLabel": "before", "jsonPath": "$.overallTotals", "description": "order totals" } ] },
            { "stateLabel": "after", "status": "WARNING",
              "financialEquation": { "expression": "26.00 - 4.00 + 0.11 = 22.11", "expected": "22.11", "actual": "24.11", "difference": "2.00" },
              "componentSummary": [
                { "component": "EXTENDED_PRICE", "subType": null, "amount": "26.00" },
                { "component": "PROMOTION", "subType": "InstantSavings", "amount": "4.00" },
                { "component": "REGULATORY_SURCHARGE", "subType": null, "amount": "0.00" }
              ],
              "observations": ["Payment coverage lags order liability by 2.00."],
              "evidence": [] }
          ],
          "transitions": [
            { "fromState": "before", "toState": "after", "operation": "PROMOTION_UPDATE", "status": "WARNING",
              "beforeGrandTotal": "24.11", "afterGrandTotal": "22.11",
              "componentDeltas": [
                { "component": "PROMOTION", "subType": "InstantSavings", "amount": "-2.00" },
                { "component": "TAX", "subType": "SalesTax", "amount": "0.00" },
                { "component": "REGULATORY_SURCHARGE", "subType": null, "amount": "0.00" },
                { "component": "GRAND_TOTAL", "subType": null, "amount": "-2.00" }
              ],
              "equations": ["-2.00 promotion increment = -2.00 grand total movement"],
              "residuals": [
                { "type": "PAYMENT_VS_ORDER", "amount": "2.00", "status": "EXPLAINED",
                  "possibleExplanations": [
                    { "componentType": "PROMOTION", "componentName": "InstantSavings", "amount": "2.00", "confidence": "HIGH",
                      "reason": "Residual equals one per-unit promotion increment." }
                  ] }
              ],
              "operationImpact": { "requestedValue": "2.00", "appliedAdjustment": "2.00", "orderLiabilityChange": "-2.00", "paymentLiabilityChange": "0.00", "executedCustomerTransaction": null },
              "explanation": "An added InstantSavings promotion lowered order liability by 2.00 while payment coverage had not yet been recalculated.",
              "evidence": [ { "stateLabel": "after", "jsonPath": "$.orderLines[0].charges.promotions[0]", "description": "InstantSavings promotion" } ] }
          ],
          "causalChains": [
            { "classification": "PROMOTION_RECALCULATION", "confidence": "HIGH",
              "steps": ["Promotion increased.", "Payment coverage did not scale.", "Order liability diverged from payment coverage by 2.00."],
              "affectedStates": ["before", "after"], "financialImpact": "2.00", "evidence": [] }
          ]
        }
        """;

    private static final String DERIVED_FACTS = """
        {
          "states": [
            { "stateLabel": "before",
              "lineReconciliations": [
                { "lineNumber": "1", "itemId": "XXXX", "quantity": "2", "unitPrice": "13.00", "calculatedExtendedPrice": "26.00",
                  "storedExtendedPrice": "26.00", "extendedPriceDifference": "0.00", "promotions": "2.00", "rewards": null,
                  "discretionaryCredits": null, "taxes": "0.11", "fees": "0.00", "calculatedLineTotal": "24.11",
                  "storedLineTotal": "24.11", "lineTotalDifference": "0.00", "ebtAllocation": null, "otherLinePayments": null }
              ],
              "aggregateReconciliation": { "sumLineExtendedPrice": "26.00", "ordinaryPromotions": "2.00", "rewards": null,
                "discretionaryCredits": null, "taxes": "0.11", "fees": "0.00", "calculatedGrandTotal": "24.11",
                "storedGrandTotal": "24.11", "grandTotalDifference": "0.00", "activeNonRewardPaymentCoverage": "24.11" },
              "rewardSummary": { "totalRewards": null, "conservationDifference": null },
              "ebtSummary": { "totalEbt": null }, "taxSummary": { "totalTax": "0.11" },
              "paymentCoverage": { "byTender": [ { "tenderType": "CREDIT_CARD", "authorized": "24.11", "charged": "24.11", "reversed": null, "refunded": null, "activeCoverage": "24.11" } ], "totalActiveCoverage": "24.11" },
              "residuals": [] },
            { "stateLabel": "after", "lineReconciliations": [],
              "aggregateReconciliation": { "sumLineExtendedPrice": "26.00", "ordinaryPromotions": "4.00", "rewards": null,
                "discretionaryCredits": null, "taxes": "0.11", "fees": "0.00", "calculatedGrandTotal": "22.11",
                "storedGrandTotal": "24.11", "grandTotalDifference": "2.00", "activeNonRewardPaymentCoverage": "24.11" },
              "rewardSummary": { "totalRewards": null, "conservationDifference": null },
              "ebtSummary": { "totalEbt": null }, "taxSummary": { "totalTax": "0.11" },
              "paymentCoverage": { "byTender": [], "totalActiveCoverage": "24.11" },
              "residuals": [ { "type": "PAYMENT_VS_ORDER", "expected": "22.11", "actual": "24.11", "amount": "2.00" } ] }
          ],
          "transitions": [
            { "fromState": "before", "toState": "after",
              "componentDeltas": [
                { "component": "ORDINARY_PROMOTIONS", "subType": null, "amount": "2.00" },
                { "component": "GRAND_TOTAL", "subType": null, "amount": "-2.00" }
              ],
              "storedDeltaComparison": { "calculatedGrandTotalDelta": "-2.00", "storedGrandTotalDelta": "0.00", "difference": "-2.00" },
              "paymentCoverageDelta": "0.00",
              "residuals": [ { "type": "TRANSITION_DELTA", "expected": "-2.00", "actual": "0.00", "amount": "-2.00" } ] }
          ]
        }
        """;
}
