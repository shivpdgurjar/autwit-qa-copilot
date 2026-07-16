package com.autwit.copilot.compare;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which columns a comparison ignores, and — just as importantly — the record of which
 * ones it actually applied.
 *
 * <p>BUILD_BRIEF §7: "Ignore rules are surfaced in the UI. If updated_at diffs vanish
 * without explanation, nobody trusts the report and the tool dies. This is a product
 * requirement, not a nicety." So this type never just filters: it returns what it
 * suppressed, and {@link PartResult#ignoredColumns()} carries that all the way to the
 * UI.
 *
 * <p>Sources, in precedence order:
 * <ol>
 *   <li>{@code artifact.meta.ignore_columns} — the scope's own defaults
 *   <li>{@code rules.ignore_columns} from CreateComparisonRequest — the tester's
 *       override for this comparison
 * </ol>
 * They union rather than replace. A tester adding {@code updated_at} for one
 * comparison should not silently re-enable the scope's {@code trace_id} noise.
 */
public final class IgnoreRules {

    private final Set<String> columns;

    private IgnoreRules(Set<String> columns) {
        this.columns = columns;
    }

    public static IgnoreRules of(Map<String, Object> artifactMeta, Map<String, Object> comparisonRules) {
        var merged = new LinkedHashSet<String>();
        merged.addAll(stringList(artifactMeta, "ignore_columns"));
        merged.addAll(stringList(comparisonRules, "ignore_columns"));
        return new IgnoreRules(merged);
    }

    public boolean ignores(String column) {
        return columns.contains(column);
    }

    /**
     * @return only the ignored columns that were actually present in the data. A rule
     *         for a column nobody has is noise in the report, and the report's
     *         credibility is the product.
     */
    public List<String> appliedTo(Set<String> columnsSeen) {
        return columns.stream().filter(columnsSeen::contains).sorted().toList();
    }

    public Set<String> all() {
        return Set.copyOf(columns);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> source, String key) {
        if (source == null || !(source.get(key) instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
