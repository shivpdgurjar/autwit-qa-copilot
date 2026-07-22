package com.autwit.copilot.web;

import java.util.List;
import java.util.UUID;

import com.autwit.copilot.analysis.AnalysisService;
import com.autwit.copilot.analysis.EvidenceRef;
import com.autwit.copilot.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The financial-analysis evidence-picker's submit target: create an analysis session and
 * assemble the tester's selected session evidence into its states.
 *
 * <p>Distinct from {@link AnalysisController}, which owns the Phase-2C snapshot
 * <em>comparison</em>. This is the OMS financial-integrity analysis
 * (message-to-qa-copilot/v1.0.16).
 *
 * <p>It does NOT run the analysis yet — it assembles and persists the states. The skill
 * call waits on the orchestrator confirming the request wire schema (v1.0.17 §4.1), so the
 * response reports what was assembled, not a verdict. Deliberate: the picker is usable and
 * the states are real before the two sides finish wiring the call.
 */
@RestController
public class FinancialAnalysisController {

    private final AnalysisService analyses;

    public FinancialAnalysisController(AnalysisService analyses) {
        this.analyses = analyses;
    }

    /**
     * One selected piece of evidence. {@code kind} is EVENT or ARTIFACT; {@code id} is the
     * event_record / artifact id. The overrides are the tester's corrections to the
     * inferred {@code stateType}/{@code source}/label/stage shown in the picker.
     */
    public record StateRef(
            @NotBlank String kind,
            UUID id,
            String stateType,
            String source,
            String label,
            String lifecycleStage) {
    }

    public record CreateAnalysisRequest(
            @NotBlank String analysisMode,
            @NotBlank String orderNumber,
            @NotEmpty List<StateRef> states,
            /** Optional — a prior analysis of this session whose ChatGPT conversation to continue. */
            String previousAnalysisId) {
    }

    /** One assembled state, echoed back so the picker can show what it produced. */
    public record StateView(int sequence, String label, String stateType, String source, String lifecycleStage) {
    }

    public record CreateAnalysisResponse(
            String analysisId,
            String analysisMode,
            String orderNumber,
            int persisted,
            int deduped,
            List<StateView> states,
            String runId,
            String stepId,
            /** The prior analysis this one continues, or null for a fresh conversation. */
            String chainedFrom,
            String note) {
    }

    /**
     * A prior analysis, as the follow-up chaining selector sees it. {@code chainable} is
     * true once the analysis has produced an OpenAI response to continue from — only those
     * can seed a follow-up.
     */
    public record AnalysisSummary(
            String analysisId,
            String analysisMode,
            String orderNumber,
            int stateCount,
            boolean chainable,
            String promptVersion,
            String ruleVersion,
            java.time.Instant updatedAt) {
    }

    public record AnalysisListResponse(List<AnalysisSummary> analyses) {
    }

    @org.springframework.web.bind.annotation.GetMapping("/sessions/{sessionId}/analyses")
    AnalysisListResponse list(@PathVariable UUID sessionId) {
        var out = analyses.listAnalyses(sessionId).stream()
                .map(s -> new AnalysisSummary(
                        s.analysisId(), s.analysisMode(), s.orderNumber(),
                        s.lastSequence(), s.latestResponseId() != null,
                        s.promptVersion(), s.ruleVersion(), s.updatedAt()))
                .toList();
        return new AnalysisListResponse(out);
    }

    @PostMapping("/sessions/{sessionId}/analyses")
    ResponseEntity<CreateAnalysisResponse> create(
            @PathVariable UUID sessionId,
            @Valid @RequestBody CreateAnalysisRequest req) {

        var refs = req.states().stream()
                .map(s -> new EvidenceRef(kind(s.kind()), s.id(), s.stateType(), s.source(),
                        s.label(), s.lifecycleStage()))
                .toList();

        var result = analyses.createAndAssemble(sessionId, req.analysisMode(), req.orderNumber(),
                refs, req.previousAnalysisId());

        var states = result.assembled().states().stream()
                .map(st -> new StateView(st.sequence(), st.label(), st.stateType().name(),
                        st.source().name(), st.lifecycleStage()))
                .toList();

        var run = result.run().run();
        var note = "Assembled and enqueued. The analysis runs asynchronously; watch the run "
                + "for the verdict (run_id " + run.runId() + ").";
        if (result.chainedFrom() != null) {
            note += " Continuing the conversation from " + result.chainedFrom() + ".";
        }
        var body = new CreateAnalysisResponse(
                result.session().analysisId(), result.session().analysisMode(),
                result.session().orderNumber(), result.assembled().persisted(),
                result.assembled().deduped(), states,
                run.runId().toString(), run.stepId().toString(),
                result.chainedFrom(), note);

        return ResponseEntity.accepted().body(body);
    }

    private static EvidenceRef.Kind kind(String raw) {
        try {
            return EvidenceRef.Kind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException.BadRequest("invalid_kind",
                    "state kind must be EVENT or ARTIFACT, got: " + raw);
        }
    }
}
