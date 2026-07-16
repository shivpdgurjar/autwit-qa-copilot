package com.autwit.copilot.compare;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.ArtifactRepository;
import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.compare.DiffEngine.PartInput;
import com.autwit.copilot.snapshot.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads two snapshots, runs {@link DiffEngine} and {@link FinancialRules}, persists
 * the comparison and its findings.
 *
 * <p>A comparison is a run like any other (invariant 2), even though the diff is
 * local and fast — "uniformity beats saving 200ms". But it never touches the
 * orchestrator, which is why RunWorker dispatches it here instead.
 */
@Service
public class ComparisonService {

    private static final Logger log = LoggerFactory.getLogger(ComparisonService.class);

    private final SnapshotRepository snapshots;
    private final ArtifactRepository artifacts;
    private final ComparisonRepository comparisons;
    private final FindingRepository findings;
    private final DiffEngine engine;
    private final FinancialRules financial;

    public ComparisonService(SnapshotRepository snapshots, ArtifactRepository artifacts,
            ComparisonRepository comparisons, FindingRepository findings, DiffEngine engine,
            FinancialRules financial) {
        this.snapshots = snapshots;
        this.artifacts = artifacts;
        this.comparisons = comparisons;
        this.findings = findings;
        this.engine = engine;
        this.financial = financial;
    }

    @Transactional
    public Result run(UUID sessionId, UUID stepId, UUID runId, UUID comparisonId, UUID fromSnapshotId,
            UUID toSnapshotId, String compareType, Map<String, Object> rules) {

        var from = load(fromSnapshotId);
        var to = load(toSnapshotId);

        var result = engine.compare(from, to, rules);
        var all = new ArrayList<>(result.findings());

        // financial_validation is a rule set OVER the structural diff, not a separate
        // engine -- so it reads the same loaded parts and refines the same findings.
        if ("financial_validation".equals(compareType)) {
            all = new ArrayList<>(financial.withinTolerance(all, rules));
            all.addAll(financial.apply(to, rules));
        }

        var verdict = verdict(result, all);
        var summary = summarise(result.partResults(), all, verdict);

        for (var f : all) {
            findings.insert(sessionId, comparisonId, stepId, f.severity(), f.category(), f.partKey(),
                    f.entityKey(), f.field(), f.beforeValue(), f.afterValue(), f.message());
        }

        comparisons.complete(comparisonId, runId, verdict, summary, result.partResults());

        log.info("Comparison {} -> {} ({} findings)", comparisonId, verdict, all.size());
        return new Result(comparisonId, verdict, summary, result.partResults(), all);
    }

    /**
     * Re-derived after the financial rules run, because they both add findings and
     * downgrade them — a verdict computed before them would be stale.
     */
    private static String verdict(DiffEngine.Result structural, List<DiffEngine.Finding> all) {
        if (structural.partResults().stream().anyMatch(PartResult::inconclusive)) {
            return Comparison.Verdict.INCONCLUSIVE;
        }
        var severities = all.stream().map(DiffEngine.Finding::severity).toList();
        if (severities.contains("critical") || severities.contains("high")) {
            return Comparison.Verdict.FAIL;
        }
        if (severities.contains("medium") || severities.contains("low")) {
            return Comparison.Verdict.WARN;
        }
        return Comparison.Verdict.PASS;
    }

    private static String summarise(List<PartResult> parts, List<DiffEngine.Finding> findings, String verdict) {
        int changed = (int) parts.stream().filter(PartResult::hasChanges).count();
        int inconclusive = (int) parts.stream().filter(PartResult::inconclusive).count();
        int ignored = parts.stream().mapToInt(p -> p.ignoredColumns().size()).sum();

        var summary = new StringBuilder("%s: %d of %d parts changed"
                .formatted(verdict, changed, parts.size()));
        if (inconclusive > 0) {
            summary.append(", %d inconclusive".formatted(inconclusive));
        }
        long real = findings.stream().filter(f -> !"info".equals(f.severity())).count();
        summary.append(", %d finding%s".formatted(real, real == 1 ? "" : "s"));
        if (ignored > 0) {
            // Surfaced in the summary itself, not just the part detail: the count is what
            // stops a reader assuming the diff saw everything.
            summary.append(" (%d ignored column%s applied)".formatted(ignored, ignored == 1 ? "" : "s"));
        }
        return summary.toString();
    }

    /** part_key -> the loaded part. This is the join key; nothing else is. */
    private Map<String, PartInput> load(UUID snapshotId) {
        var snapshot = snapshots.find(snapshotId);
        if (snapshot == null) {
            throw new ApiException.NotFound("snapshot", snapshotId);
        }

        var parts = new LinkedHashMap<String, PartInput>();
        for (var part : snapshots.parts(snapshotId)) {
            var artifact = artifacts.find(part.artifactId())
                    .orElseThrow(() -> new ApiException.NotFound("artifact", part.artifactId()));

            if (artifact.purgedAt() != null) {
                throw new ApiException.Gone(
                        "Artifact %s for part '%s' was purged by retention; snapshot %s can no longer be compared."
                                .formatted(artifact.artifactId(), part.partKey(), snapshotId));
            }

            parts.put(part.partKey(), new PartInput(part.partKey(), rowsOf(artifact.body()),
                    artifact.meta(), artifact.artifactType()));
        }
        return parts;
    }

    /** An rdbms_table body is an array of rows; a document body is a single object. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Object body) {
        if (body instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance).map(r -> (Map<String, Object>) r).toList();
        }
        if (body instanceof Map<?, ?> map) {
            return List.of((Map<String, Object>) map);
        }
        return List.of();
    }

    public record Result(UUID comparisonId, String verdict, String summary, List<PartResult> partResults,
            List<DiffEngine.Finding> findings) {
    }
}
