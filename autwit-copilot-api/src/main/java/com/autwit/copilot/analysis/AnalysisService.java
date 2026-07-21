package com.autwit.copilot.analysis;

import java.util.List;
import java.util.UUID;

import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.run.RunEnqueuer;
import com.autwit.copilot.session.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a financial-analysis session and assembles the tester's selected evidence into
 * its states. This is the entry point the UI evidence-picker calls.
 *
 * <p>It deliberately stops at assembly — it does not call the orchestrator's financial
 * skill. That seam waits on the confirmed request wire schema (v1.0.17 §4.1), and building
 * it against a guessed contract is the failure the whole contract phase existed to
 * prevent. What this delivers is real and verifiable on its own: an analysis session with
 * its states hashed and persisted, ready to send the moment the wire shape is confirmed.
 */
@Service
public class AnalysisService {

    /**
     * Until the skill call is wired, no verdict has been produced, so there is no real
     * prompt/rule version to pin. This placeholder keeps the NOT NULL columns honest —
     * "unpinned" is visibly not a real version — and is overwritten when a run actually
     * executes under known versions.
     */
    static final String UNPINNED = "unpinned";

    private final SessionRepository sessions;
    private final AnalysisRepository analysis;
    private final StateAssembler assembler;
    private final RunEnqueuer enqueuer;

    public AnalysisService(SessionRepository sessions, AnalysisRepository analysis,
            StateAssembler assembler, RunEnqueuer enqueuer) {
        this.sessions = sessions;
        this.analysis = analysis;
        this.assembler = assembler;
        this.enqueuer = enqueuer;
    }

    public record Result(AnalysisSession session, StateAssembler.Assembled assembled, RunEnqueuer.Accepted run) {
    }

    /**
     * @param analysisMode  SNAPSHOT_SANCTITY (exactly one state) or LIFECYCLE_COMPARISON.
     * @param orderNumber   the order the evidence is about.
     * @param refs          the tester's selection from the picker.
     */
    @Transactional
    public Result createAndAssemble(UUID sessionId, String analysisMode, String orderNumber,
            List<EvidenceRef> refs) {

        if (sessions.find(sessionId).isEmpty()) {
            throw new ApiException.NotFound("session", sessionId);
        }
        if (analysisMode == null
                || !(analysisMode.equals("SNAPSHOT_SANCTITY") || analysisMode.equals("LIFECYCLE_COMPARISON"))) {
            throw new ApiException.BadRequest("invalid_analysis_mode",
                    "analysis_mode must be SNAPSHOT_SANCTITY or LIFECYCLE_COMPARISON.");
        }
        if (refs == null || refs.isEmpty()) {
            throw new ApiException.BadRequest("no_states",
                    "An analysis needs at least one selected piece of evidence.");
        }
        // Mirror the orchestrator's structural rule (their FinancialAnalysisService): a
        // snapshot is one order picture. Enforced here so "analyze this" cannot smuggle in
        // a second state, and so the tester is told at the boundary rather than by a 400
        // from the orchestrator later.
        if (analysisMode.equals("SNAPSHOT_SANCTITY") && refs.size() > 1) {
            throw new ApiException.BadRequest("too_many_states",
                    "SNAPSHOT_SANCTITY analyses exactly one state; received " + refs.size()
                            + ". Use LIFECYCLE_COMPARISON to compare a sequence.");
        }

        var analysisId = "analysis-" + UUID.randomUUID();
        var session = new AnalysisSession(analysisId, sessionId, orderNumber, analysisMode,
                null, 0, UNPINNED, UNPINNED, 0, null, null);
        analysis.createSession(session);

        var assembled = assembler.assemble(analysisId, refs);
        // last_sequence reflects what actually landed, so a follow-up appends after it.
        analysis.bumpSession(analysisId, 0, assembled.states().size(), null);

        // Enqueue the analysis run in the same transaction. The worker dequeues via
        // SKIP LOCKED and cannot see the run until this commits — by which point the
        // states are committed too — so it never reads a half-assembled analysis.
        var run = enqueuer.enqueueFinancialAnalysis(sessionId, analysisId, orderNumber, null);

        return new Result(analysis.findSession(analysisId).orElseThrow(), assembled, run);
    }
}
