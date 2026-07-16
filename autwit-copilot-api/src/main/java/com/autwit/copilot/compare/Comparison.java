package com.autwit.copilot.compare;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** openapi.yaml Comparison / ComparisonDetail. */
public record Comparison(
        UUID comparisonId,
        UUID sessionId,
        UUID stepId,
        UUID runId,
        UUID fromSnapshotId,
        UUID toSnapshotId,
        String compareType,
        Map<String, Object> rules,
        String verdict,
        String summary,
        UUID reportRef,
        List<PartResult> partResults,
        Instant createdAt,
        List<Finding> findings,
        Map<String, Integer> findingCounts) {

    /** openapi.yaml Verdict. */
    public static final class Verdict {
        public static final String PASS = "pass";
        public static final String FAIL = "fail";
        public static final String WARN = "warn";
        public static final String INCONCLUSIVE = "inconclusive";

        private Verdict() {
        }
    }

    public Comparison withDetail(List<Finding> findings, Map<String, Integer> counts) {
        return new Comparison(comparisonId, sessionId, stepId, runId, fromSnapshotId, toSnapshotId,
                compareType, rules, verdict, summary, reportRef, partResults, createdAt, findings, counts);
    }
}
