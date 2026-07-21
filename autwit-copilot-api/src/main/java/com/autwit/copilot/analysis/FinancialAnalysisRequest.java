package com.autwit.copilot.analysis;

import java.util.List;

/**
 * The body of a call to the orchestrator's financial analysis API
 * (`POST /v1/financial-analysis/{snapshot,lifecycle}`), mirroring their
 * `AnalysisRequest` (`financial/domain/types.ts`).
 *
 * <p><b>This is a different wire surface from the SKILL_CONTRACT.</b> The skill routes
 * (`/invoke`, `/skills/.../execute`) are snake_case; the financial API is <b>camelCase</b>
 * (`analysisId`, `stateType`, `lifecycleStage`). So this DTO is serialised with a
 * default-naming mapper, never copilot-api's pinned snake_case one — see
 * {@code HttpFinancialAnalysisClient}.
 *
 * <p>{@code states} is our own {@link StateEnvelope} directly: its enum fields serialise to
 * their names (`ORDER_SNAPSHOT`, `ORDER_DB`) and {@code capturedAt} to an ISO string, which
 * is exactly the wire shape. Confirmed unchanged by the orchestrator in
 * `message-to-qa-copilot/v1.0.19` §1.
 *
 * @param previousResponseId OpenAI chaining token — a cache, never a dependency. Null is
 *                           valid and degrades to a full re-read (v1.0.16 §4).
 */
public record FinancialAnalysisRequest(
        String analysisId,
        String analysisMode,
        String orderNumber,
        String promptVersion,
        String ruleVersion,
        String previousResponseId,
        List<StateEnvelope> states) {
}
