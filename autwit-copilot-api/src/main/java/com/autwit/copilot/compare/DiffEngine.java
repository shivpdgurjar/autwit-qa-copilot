package com.autwit.copilot.compare;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

/**
 * The core of the product (BUILD_BRIEF §7).
 *
 * <pre>
 * input:  from_snapshot, to_snapshot, compare_type, rules
 *         join snapshot_part on part_key   &lt;- both sides must have the same keys
 *         per part: artifact.meta.pk_columns drives the row-level key
 * output: comparison.part_results[] + finding[]
 * </pre>
 *
 * <p>Pure: it takes loaded parts and returns a result. No DB, no IO — which is what
 * makes DiffEngineTest able to state the semantics plainly instead of through a
 * fixture.
 *
 * <p>Two rules run through everything here. It never guesses a key, and it never
 * hides a difference. Both exist because a diff tool that is quietly wrong is worse
 * than no diff tool: the tester believes it.
 */
@Component
public class DiffEngine {

    /** One side of a part, already loaded. */
    public record PartInput(String partKey, List<Map<String, Object>> rows, Map<String, Object> meta,
            String artifactType) {

        @SuppressWarnings("unchecked")
        public List<String> pkColumns() {
            if (meta != null && meta.get("pk_columns") instanceof List<?> pk && !pk.isEmpty()) {
                return pk.stream().filter(String.class::isInstance).map(String.class::cast).toList();
            }
            return List.of();
        }

        public boolean isRdbmsTable() {
            return "rdbms_table".equals(artifactType);
        }
    }

    public record Result(List<PartResult> partResults, List<Finding> findings, String verdict) {
    }

    /** A finding before it has an id — the engine does not touch the database. */
    public record Finding(String severity, String category, String partKey, String entityKey, String field,
            Object beforeValue, Object afterValue, String message) {
    }

    public Result compare(Map<String, PartInput> from, Map<String, PartInput> to,
            Map<String, Object> comparisonRules) {

        var partResults = new ArrayList<PartResult>();
        var findings = new ArrayList<Finding>();

        // The union, not the intersection. Comparing only the keys both sides share
        // would make a vanished part look like nothing happened -- the exact failure
        // this product exists to catch.
        var allKeys = new TreeSet<String>();
        allKeys.addAll(from.keySet());
        allKeys.addAll(to.keySet());

        for (var partKey : allKeys) {
            var before = from.get(partKey);
            var after = to.get(partKey);

            if (before == null || after == null) {
                // §7: "part_key present on one side only -> high finding. That's either a
                // real bug or scope drift, and both matter."
                var side = before == null ? "to" : "from";
                var missing = before == null ? "from" : "to";
                findings.add(new Finding("high", "scope_drift", partKey, null, null, null, null,
                        "Part '%s' is present in the %s snapshot but missing from the %s snapshot. "
                                .formatted(partKey, side, missing)
                                + "Either the scope changed between captures, or the data really did "
                                + "appear/disappear. part_key is required to be stable across snapshots of "
                                + "the same scope (SKILL_CONTRACT §6.2)."));
                partResults.add(PartResult.inconclusive(partKey,
                        "present only in the %s snapshot".formatted(side)));
                continue;
            }

            comparePart(partKey, before, after, comparisonRules, partResults, findings);
        }

        return new Result(partResults, findings, verdictFor(partResults, findings));
    }

    private void comparePart(String partKey, PartInput before, PartInput after,
            Map<String, Object> comparisonRules, List<PartResult> partResults, List<Finding> findings) {

        var pkColumns = after.pkColumns().isEmpty() ? before.pkColumns() : after.pkColumns();

        if (pkColumns.isEmpty()) {
            if (before.isRdbmsTable() || after.isRdbmsTable()) {
                // §7: "If absent on an rdbms_table, that's a contract violation -- raise a
                // high finding, mark the part inconclusive. Do not guess the key."
                //
                // Guessing would be easy here and is exactly the wrong move: infer the key
                // wrong and every row reads as removed-and-added, which looks like
                // catastrophic data loss and is really a missing bit of metadata.
                findings.add(new Finding("high", "contract_violation", partKey, null, null, null, null,
                        "Part '%s' is an rdbms_table with no meta.pk_columns, so its rows cannot be "
                                .formatted(partKey)
                                + "joined and the part cannot be compared. meta.pk_columns is required for "
                                + "rdbms_table (SKILL_CONTRACT §6.1). The key is not guessed."));
                partResults.add(PartResult.inconclusive(partKey, "meta.pk_columns is missing"));
                return;
            }
            // A document, not a table: one row, compared whole.
            compareAsDocument(partKey, before, after, comparisonRules, partResults, findings);
            return;
        }

        var ignore = IgnoreRules.of(after.meta() != null ? after.meta() : before.meta(), comparisonRules);

        var beforeRows = keyBy(before.rows(), pkColumns);
        var afterRows = keyBy(after.rows(), pkColumns);

        var keys = new LinkedHashSet<String>();
        keys.addAll(beforeRows.keySet());
        keys.addAll(afterRows.keySet());

        int added = 0;
        int removed = 0;
        int modified = 0;
        int unchanged = 0;
        var columnsSeen = new LinkedHashSet<String>();

        for (var key : keys) {
            var b = beforeRows.get(key);
            var a = afterRows.get(key);

            if (b == null) {
                added++;
                columnsSeen.addAll(a.keySet());
                continue;
            }
            if (a == null) {
                removed++;
                columnsSeen.addAll(b.keySet());
                continue;
            }

            columnsSeen.addAll(b.keySet());
            columnsSeen.addAll(a.keySet());

            var changed = changedFields(b, a, ignore);
            if (changed.isEmpty()) {
                unchanged++;
            } else {
                modified++;
                changed.forEach((field, values) -> findings.add(new Finding(
                        "info", "changed", partKey, key, field, values[0], values[1],
                        "%s.%s changed from %s to %s for %s"
                                .formatted(partKey, field, render(values[0]), render(values[1]), key))));
            }
        }

        partResults.add(PartResult.of(partKey, added, removed, modified, unchanged,
                ignore.appliedTo(columnsSeen)));
    }

    /** dynamo_doc / api_response: no row key, so the whole body is the unit. */
    private void compareAsDocument(String partKey, PartInput before, PartInput after,
            Map<String, Object> comparisonRules, List<PartResult> partResults, List<Finding> findings) {

        var ignore = IgnoreRules.of(after.meta() != null ? after.meta() : before.meta(), comparisonRules);
        var b = before.rows().isEmpty() ? Map.<String, Object>of() : before.rows().get(0);
        var a = after.rows().isEmpty() ? Map.<String, Object>of() : after.rows().get(0);

        var columnsSeen = new LinkedHashSet<String>();
        columnsSeen.addAll(b.keySet());
        columnsSeen.addAll(a.keySet());

        var changed = changedFields(b, a, ignore);
        changed.forEach((field, values) -> findings.add(new Finding(
                "info", "changed", partKey, null, field, values[0], values[1],
                "%s.%s changed from %s to %s".formatted(partKey, field, render(values[0]), render(values[1])))));

        partResults.add(PartResult.of(partKey, 0, 0, changed.isEmpty() ? 0 : 1, changed.isEmpty() ? 1 : 0,
                ignore.appliedTo(columnsSeen)));
    }

    /** @return field -> [before, after] for every field that differs and is not ignored. */
    private static Map<String, Object[]> changedFields(Map<String, Object> before, Map<String, Object> after,
            IgnoreRules ignore) {

        var fields = new LinkedHashSet<String>();
        fields.addAll(before.keySet());
        fields.addAll(after.keySet());

        var changed = new LinkedHashMap<String, Object[]>();
        for (var field : fields) {
            if (ignore.ignores(field)) {
                continue;
            }
            var b = before.get(field);
            var a = after.get(field);
            if (!valuesEqual(b, a)) {
                changed.put(field, new Object[] {b, a});
            }
        }
        return changed;
    }

    /**
     * Value equality that understands numbers.
     *
     * <p>BigDecimal.equals compares scale, so 1200.00.equals(1200.0) is false — and
     * artifact bodies preserve the scale they arrived with, deliberately. Using equals
     * here would report a modification on every trailing zero and bury the real diffs
     * in noise. compareTo is the right question: is this the same number.
     *
     * <p>A scale change is not nothing, but it is a formatting difference rather than a
     * value one, and this engine reports value differences.
     */
    static boolean valuesEqual(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return toBigDecimal(na).compareTo(toBigDecimal(nb)) == 0;
        }
        return Objects.equals(a, b);
    }

    static BigDecimal toBigDecimal(Number n) {
        return n instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
    }

    /**
     * Row key: the pk_columns values joined. Never inferred — see comparePart.
     */
    private static Map<String, Map<String, Object>> keyBy(List<Map<String, Object>> rows,
            List<String> pkColumns) {

        var byKey = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : rows) {
            byKey.put(keyOf(row, pkColumns), row);
        }
        return byKey;
    }

    static String keyOf(Map<String, Object> row, List<String> pkColumns) {
        return pkColumns.stream().map(c -> String.valueOf(row.get(c))).reduce((a, b) -> a + "|" + b).orElse("");
    }

    /**
     * inconclusive wins over everything.
     *
     * <p>If any part could not be compared, the comparison as a whole cannot honestly
     * say pass or fail — a pass that silently excluded a part it could not read is the
     * most dangerous output this thing could produce.
     */
    private static String verdictFor(List<PartResult> partResults, List<Finding> findings) {
        if (partResults.stream().anyMatch(PartResult::inconclusive)) {
            return Comparison.Verdict.INCONCLUSIVE;
        }
        var severities = findings.stream().map(Finding::severity).collect(java.util.stream.Collectors.toSet());
        if (severities.contains("critical") || severities.contains("high")) {
            return Comparison.Verdict.FAIL;
        }
        if (severities.contains("medium") || severities.contains("low")) {
            return Comparison.Verdict.WARN;
        }
        // info findings are the changed-field log, not problems: a run that only reports
        // "status changed from CREATED to FULFILLED" is a pass.
        return Comparison.Verdict.PASS;
    }

    private static String render(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    static Set<String> columnsOf(List<Map<String, Object>> rows) {
        var columns = new LinkedHashSet<String>();
        rows.forEach(r -> columns.addAll(r.keySet()));
        return columns;
    }
}
