package com.autwit.copilot.analysis;

import java.util.LinkedHashMap;
import java.util.Map;

import com.autwit.copilot.compare.FindingRepository;
import com.autwit.copilot.run.EnvelopePersister;
import com.autwit.copilot.run.Run;
import com.autwit.copilot.run.RunRepository;
import com.autwit.copilot.run.RunType;
import com.autwit.copilot.session.StepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes a {@code financial_analysis} run: read the assembled states, call the
 * orchestrator's financial API, persist the verdict.
 *
 * <p>Mirrors {@code LocalRunExecutor} — a run that does not go through the SKILL_CONTRACT
 * skill surface. The states were already assembled and persisted when the analysis was
 * created ({@link StateAssembler}); this reads them back, sends them, and records what came
 * back: findings into the findings feed, the verdict on the step and the run summary, and
 * the OpenAI chaining token + the versions the verdict ran under onto the session head.
 */
@Service
public class FinancialAnalysisRunner {

    private final AnalysisRepository analysis;
    private final FinancialAnalysisClient client;
    private final FindingRepository findings;
    private final RunRepository runs;
    private final StepRepository steps;

    public FinancialAnalysisRunner(AnalysisRepository analysis, FinancialAnalysisClient client,
            FindingRepository findings, RunRepository runs, StepRepository steps) {
        this.analysis = analysis;
        this.client = client;
        this.findings = findings;
        this.runs = runs;
        this.steps = steps;
    }

    public boolean handles(String runType) {
        return RunType.FINANCIAL_ANALYSIS.wire().equals(runType);
    }

    /**
     * REQUIRES_NEW, same reasoning as {@code LocalRunExecutor}: the run is claimed last,
     * and if that claim fails because the run already went terminal, everything written
     * here must unwind with it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(Run run) {
        var analysisId = (String) run.request().get("analysis_id");
        var session = analysis.findSession(analysisId)
                .orElseThrow(() -> new IllegalStateException("analysis_session gone: " + analysisId));
        var states = analysis.readStates(analysisId);

        // Send null prompt/rule versions so the orchestrator applies its own defaults; the
        // result echoes the versions it actually ran under, which we then pin on the session
        // (replacing the "unpinned" placeholder). previousResponseId chains a follow-up.
        var request = new FinancialAnalysisRequest(
                analysisId, session.analysisMode(), session.orderNumber(),
                null, null, session.latestResponseId(), states);

        var result = "SNAPSHOT_SANCTITY".equals(session.analysisMode())
                ? client.analyzeSnapshot(request)
                : client.analyzeLifecycle(request);

        // Findings feed: the actionable ones (a PASS finding is not an issue and would only
        // dilute the feed). expected/actual/difference fold into the message so nothing is
        // lost, while the raw fields stay in the run summary.
        int failCount = 0;
        if (result.findings() != null) {
            for (var f : result.findings()) {
                if ("PASS".equals(f.status())) {
                    continue;
                }
                if ("FAIL".equals(f.status())) {
                    failCount++;
                }
                findings.insert(run.sessionId(), null, run.stepId(),
                        mapSeverity(f.severity()), f.category(), null,
                        entityKey(f), f.ruleId(), null, null, message(f));
            }
        }

        // Pin the chaining token + the real versions on the session head (optimistic).
        analysis.recordResult(analysisId, session.version(),
                result.responseId(), result.promptVersion(), result.ruleVersion());

        var summary = new LinkedHashMap<String, Object>();
        summary.put("analysis_id", analysisId);
        summary.put("overall_status", result.overallStatus());
        summary.put("confidence", result.confidence());
        summary.put("executive_summary", result.executiveSummary());
        summary.put("ai_analysis_status", result.aiAnalysisStatus());
        if (result.aiUnavailableReason() != null) {
            summary.put("ai_unavailable_reason", result.aiUnavailableReason());
        }
        summary.put("findings_total", result.findings() == null ? 0 : result.findings().size());
        summary.put("findings_fail", failCount);
        summary.put("model", result.model());

        if (!runs.succeed(run.runId(), run.workerId(), summary)) {
            throw new EnvelopePersister.LateResultException(run.runId());
        }
        steps.complete(run.stepId(), "succeeded",
                "Financial analysis — " + result.overallStatus());
    }

    /** The financial severity scale onto the DB's info/low/medium/high/critical. */
    private static String mapSeverity(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL" -> "critical";
            case "ERROR" -> "high";
            case "WARNING" -> "medium";
            case "NOT_VERIFIABLE" -> "low";
            default -> "info"; // INFO and anything unexpected
        };
    }

    private static String entityKey(FinancialAnalysisResult.Finding f) {
        if (f.stateLabel() != null && f.lineNumber() != null) {
            return f.stateLabel() + " / line " + f.lineNumber();
        }
        return f.stateLabel() != null ? f.stateLabel() : f.lineNumber();
    }

    private static String message(FinancialAnalysisResult.Finding f) {
        var sb = new StringBuilder(f.explanation() == null ? f.ruleId() : f.explanation());
        if (f.expected() != null || f.actual() != null) {
            sb.append(" (expected ").append(f.expected()).append(", actual ").append(f.actual());
            if (f.difference() != null) {
                sb.append(", Δ ").append(f.difference());
            }
            sb.append(')');
        }
        return sb.toString();
    }
}
