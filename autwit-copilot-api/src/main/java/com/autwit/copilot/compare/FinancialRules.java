package com.autwit.copilot.compare;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.autwit.copilot.compare.DiffEngine.Finding;
import com.autwit.copilot.compare.DiffEngine.PartInput;
import org.springframework.stereotype.Component;

/**
 * BUILD_BRIEF §7: "financial_validation is a rule set over the structural diff, not a
 * separate engine: tolerance on money fields, sum invariants (order total == Σ line
 * items), and cross-source consistency (RDBMS order total == Dynamo doc total == API
 * response total)."
 *
 * <p>So this runs after {@link DiffEngine}, over the same loaded parts, and adds
 * findings. It never re-implements the join.
 *
 * <p>All three checks read from {@link FinancialProperties}. The only thing hard-coded
 * here is how to apply a rule, never which rules exist.
 */
@Component
public class FinancialRules {

    private final FinancialProperties props;

    public FinancialRules(FinancialProperties props) {
        this.props = props;
    }

    /**
     * @param rules the comparison's own rules, which may override the tolerance, e.g.
     *              {"tolerance":{"amount":0.05}}
     */
    public List<Finding> apply(Map<String, PartInput> to, Map<String, Object> rules) {
        var findings = new ArrayList<Finding>();
        var tolerance = toleranceFrom(rules);

        props.sumInvariants().forEach(rule -> checkSum(rule, to, tolerance, findings));
        props.crossSource().forEach(rule -> checkCrossSource(rule, to, tolerance, findings));

        return findings;
    }

    /**
     * Re-checks money fields the structural diff already flagged as changed, and
     * downgrades those inside tolerance.
     *
     * <p>Kept separate from {@link #apply} because it edits the structural findings
     * rather than adding to them: a money field that moved by less than tolerance is
     * still a change worth seeing, just not one worth shouting about.
     */
    public List<Finding> withinTolerance(List<Finding> structural, Map<String, Object> rules) {
        var tolerance = toleranceFrom(rules);
        return structural.stream().map(f -> {
            if (!isMoneyField(f.field()) || !"changed".equals(f.category())) {
                return f;
            }
            var before = asDecimal(f.beforeValue());
            var after = asDecimal(f.afterValue());
            if (before == null || after == null) {
                return f;
            }
            var delta = after.subtract(before).abs();
            if (delta.compareTo(tolerance) <= 0) {
                return new Finding("info", "money_within_tolerance", f.partKey(), f.entityKey(), f.field(),
                        f.beforeValue(), f.afterValue(),
                        "%s.%s moved by %s, within the %s tolerance."
                                .formatted(f.partKey(), f.field(), delta.toPlainString(),
                                        tolerance.toPlainString()));
            }
            return new Finding("medium", "financial", f.partKey(), f.entityKey(), f.field(),
                    f.beforeValue(), f.afterValue(),
                    "%s.%s changed from %s to %s (delta %s, beyond the %s tolerance)."
                            .formatted(f.partKey(), f.field(), before.toPlainString(), after.toPlainString(),
                                    delta.toPlainString(), tolerance.toPlainString()));
        }).toList();
    }

    /** order total == Σ line items. */
    private void checkSum(FinancialProperties.SumInvariant rule, Map<String, PartInput> parts,
            BigDecimal tolerance, List<Finding> findings) {

        var totals = parts.get(rule.totalPart());
        var components = parts.get(rule.partsPart());
        if (totals == null || components == null) {
            // The scope did not include both parts. Not a failure -- a shipment_only
            // snapshot has no order totals to check.
            return;
        }

        var sums = new LinkedHashMap<String, BigDecimal>();
        for (var row : components.rows()) {
            var key = String.valueOf(row.get(rule.entityKey()));
            var value = asDecimal(row.get(rule.partsField()));
            if (value != null) {
                sums.merge(key, value, BigDecimal::add);
            }
        }

        for (var row : totals.rows()) {
            var key = String.valueOf(row.get(rule.entityKey()));
            var declared = asDecimal(row.get(rule.totalField()));
            if (declared == null) {
                continue;
            }
            var summed = sums.getOrDefault(key, BigDecimal.ZERO);
            var delta = declared.subtract(summed).abs();

            if (delta.compareTo(tolerance) > 0) {
                findings.add(new Finding(rule.severity(), "financial", rule.totalPart(), key,
                        rule.totalField(), declared, summed,
                        "%s: %s.%s is %s but Σ %s.%s is %s for %s — a discrepancy of %s. "
                                .formatted(rule.name(), rule.totalPart(), rule.totalField(),
                                        declared.toPlainString(), rule.partsPart(), rule.partsField(),
                                        summed.toPlainString(), key, delta.toPlainString())
                                + "The order total does not match the sum of its line items."));
            }
        }
    }

    /** RDBMS order total == Dynamo doc total == API response total. */
    private void checkCrossSource(FinancialProperties.CrossSource rule, Map<String, PartInput> parts,
            BigDecimal tolerance, List<Finding> findings) {

        var observed = new LinkedHashMap<String, Map<String, BigDecimal>>();

        for (var source : rule.sources()) {
            var part = parts.get(source.partKey());
            if (part == null) {
                continue;
            }
            for (var row : part.rows()) {
                var value = asDecimal(row.get(source.field()));
                if (value == null) {
                    continue;
                }
                var key = source.entityKey() != null ? String.valueOf(row.get(source.entityKey())) : "*";
                observed.computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .put(source.partKey() + "." + source.field(), value);
            }
        }

        observed.forEach((entityKey, bySource) -> {
            if (bySource.size() < 2) {
                // Only one source present: nothing to disagree with.
                return;
            }
            var min = bySource.values().stream().min(BigDecimal::compareTo).orElseThrow();
            var max = bySource.values().stream().max(BigDecimal::compareTo).orElseThrow();

            if (max.subtract(min).compareTo(tolerance) > 0) {
                var detail = bySource.entrySet().stream()
                        .map(e -> "%s=%s".formatted(e.getKey(), e.getValue().toPlainString()))
                        .reduce((a, b) -> a + ", " + b).orElse("");
                findings.add(new Finding(rule.severity(), "consistency", null, entityKey, null, min, max,
                        "%s: sources disagree for %s (%s). The same value differs by %s across systems."
                                .formatted(rule.name(), entityKey, detail,
                                        max.subtract(min).toPlainString())));
            }
        });
    }

    private boolean isMoneyField(String field) {
        return field != null && props.moneyFields().contains(field);
    }

    /** Comparison rules may override the configured default, e.g. {"tolerance":{"amount":0.05}}. */
    private BigDecimal toleranceFrom(Map<String, Object> rules) {
        if (rules != null && rules.get("tolerance") instanceof Map<?, ?> t && !t.isEmpty()) {
            var first = t.values().iterator().next();
            var parsed = asDecimal(first);
            if (parsed != null) {
                return parsed;
            }
        }
        return props.tolerance();
    }

    /**
     * Money arrives as a JSON string in the contract's own example ("total_amount":
     * "1200.00"), and as a number elsewhere. Both must work, and neither may go via
     * double — that would undo the scale preservation the whole pipeline protects.
     */
    static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
