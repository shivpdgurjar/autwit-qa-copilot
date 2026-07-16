package com.autwit.copilot.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.compare.ComparisonService;
import com.autwit.copilot.report.ReportRenderer;
import com.autwit.copilot.session.StepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs that never call the orchestrator: the diff engine and the report renderer.
 *
 * <p>They are runs anyway. BUILD_BRIEF invariant 2: "Everything that touches the
 * orchestrator or takes >1s is a run. Uniform. Even a local diff." — and §8's
 * comparison endpoint: "Also a run, even though the diff is local and fast.
 * Uniformity beats saving 200ms." So they queue, take the session's advisory lock, and
 * hold a lease exactly like an invoke does. Only what happens in the middle differs.
 */
@Service
public class LocalRunExecutor {

    private final ComparisonService comparisons;
    private final ReportRenderer reports;
    private final RunRepository runs;
    private final StepRepository steps;

    public LocalRunExecutor(ComparisonService comparisons, ReportRenderer reports, RunRepository runs,
            StepRepository steps) {
        this.comparisons = comparisons;
        this.reports = reports;
        this.runs = runs;
        this.steps = steps;
    }

    public boolean handles(String runType) {
        return RunType.COMPARISON.wire().equals(runType) || RunType.REPORT.wire().equals(runType);
    }

    /**
     * REQUIRES_NEW for the same reason EnvelopePersister uses it: the run is claimed
     * last, and if the claim fails because the run went terminal, everything written
     * here must unwind with it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(Run run) {
        var summary = RunType.COMPARISON.wire().equals(run.runType())
                ? runComparison(run)
                : runReport(run);

        if (!runs.succeed(run.runId(), run.workerId(), summary)) {
            throw new EnvelopePersister.LateResultException(run.runId());
        }
        steps.updateStatus(run.stepId(), "succeeded");
    }

    private Map<String, Object> runComparison(Run run) {
        var request = run.request();
        var result = comparisons.run(
                run.sessionId(), run.stepId(), run.runId(),
                uuid(request.get("comparison_id")),
                uuid(request.get("from_snapshot_id")),
                uuid(request.get("to_snapshot_id")),
                (String) request.get("compare_type"),
                asMap(request.get("rules")));

        var summary = new LinkedHashMap<String, Object>();
        summary.put("comparison_id", result.comparisonId().toString());
        summary.put("verdict", result.verdict());
        summary.put("summary", result.summary());
        summary.put("findings", result.findings().size());
        summary.put("parts", result.partResults().size());
        return summary;
    }

    private Map<String, Object> runReport(Run run) {
        var request = run.request();
        List<UUID> artifacts = reports.render(run.sessionId(), run.stepId(), run.runId(),
                (String) request.getOrDefault("format", "html"), (String) request.get("notes"));

        var summary = new LinkedHashMap<String, Object>();
        summary.put("report_artifacts", artifacts.stream().map(UUID::toString).toList());
        return summary;
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
