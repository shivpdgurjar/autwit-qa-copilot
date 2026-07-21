package com.autwit.copilot.analysis;

/**
 * Calls the orchestrator's financial-analysis API
 * (`POST /v1/financial-analysis/{snapshot,lifecycle}`) — a separate surface from the
 * SKILL_CONTRACT client (camelCase, different shape; see {@link HttpFinancialAnalysisClient}).
 *
 * <p>An interface so the {@code fake} profile can replay a deterministic result without a
 * live orchestrator, the same split as {@code OrchestratorClient}.
 */
public interface FinancialAnalysisClient {

    /** SNAPSHOT_SANCTITY — one order picture, internal-consistency verdict. */
    FinancialAnalysisResult analyzeSnapshot(FinancialAnalysisRequest request);

    /** LIFECYCLE_COMPARISON — a sequence of states, each transition validated. */
    FinancialAnalysisResult analyzeLifecycle(FinancialAnalysisRequest request);
}
