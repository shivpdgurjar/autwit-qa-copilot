package com.autwit.copilot.compare;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.autwit.copilot.compare.DiffEngine.PartInput;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUILD_BRIEF §9: "financial: order total != Σ line items → critical finding", plus
 * the other two rule kinds §7 names — tolerance on money fields, and cross-source
 * consistency.
 *
 * <p>Every rule here is built from config values rather than hard-coded, which is the
 * point: §7 says "Put the rules in config, not in Java", so the test constructs the
 * config the same way application.yml does.
 */
class FinancialRulesTest {

    /** Mirrors application.yml's autwit.compare.financial block. */
    private static final FinancialProperties PROPS = new FinancialProperties(
            List.of("total_amount", "amount", "line_total", "totalAmount"),
            new BigDecimal("0.01"),
            List.of(new FinancialProperties.SumInvariant(
                    "order_total_equals_line_items", "oms.orders", "total_amount", "order_id",
                    "oms.order_items", "line_total", "critical")),
            List.of(new FinancialProperties.CrossSource(
                    "order_total_consistency",
                    List.of(new FinancialProperties.CrossSource.Source("oms.orders", "total_amount", "order_id"),
                            new FinancialProperties.CrossSource.Source("dynamo.order_doc", "totalAmount", "orderId"),
                            new FinancialProperties.CrossSource.Source("api.order_response", "totalAmount", "orderId")),
                    "high")));

    private final FinancialRules rules = new FinancialRules(PROPS);

    private static PartInput part(String key, String type, List<Map<String, Object>> rows) {
        return new PartInput(key, rows, Map.of("pk_columns", List.of("order_id")), type);
    }

    private static Map<String, Object> row(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, PartInput> snapshot(PartInput... parts) {
        var map = new LinkedHashMap<String, PartInput>();
        for (var p : parts) {
            map.put(p.partKey(), p);
        }
        return map;
    }

    // ---------------------------------------------------------------- sum invariants

    @Test
    void orderTotalNotEqualToSumOfLineItemsIsACriticalFinding() {
        var to = snapshot(
                part("oms.orders", "rdbms_table", List.of(
                        row("order_id", "XXXX", "total_amount", new BigDecimal("1450.00")))),
                part("oms.order_items", "rdbms_table", List.of(
                        row("order_id", "XXXX", "line_no", 1, "line_total", new BigDecimal("800.00")),
                        row("order_id", "XXXX", "line_no", 2, "line_total", new BigDecimal("400.00")))));

        var findings = rules.apply(to, Map.of());

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo("critical");
            assertThat(f.category()).isEqualTo("financial");
            assertThat(f.entityKey()).isEqualTo("XXXX");
            assertThat(f.message())
                    .contains("1450.00")
                    .contains("1200.00")
                    .contains("250.00")
                    .contains("does not match the sum of its line items");
        });
    }

    @Test
    void aTotalThatMatchesItsLineItemsRaisesNothing() {
        var to = snapshot(
                part("oms.orders", "rdbms_table", List.of(
                        row("order_id", "XXXX", "total_amount", new BigDecimal("1200.00")))),
                part("oms.order_items", "rdbms_table", List.of(
                        row("order_id", "XXXX", "line_total", new BigDecimal("800.00")),
                        row("order_id", "XXXX", "line_total", new BigDecimal("400.00")))));

        assertThat(rules.apply(to, Map.of())).isEmpty();
    }

    @Test
    void moneyAsAStringIsHandled() {
        // SKILL_CONTRACT §6.1's own example sends money as a string: "total_amount": "1200.00".
        var to = snapshot(
                part("oms.orders", "rdbms_table", List.of(row("order_id", "X", "total_amount", "1450.00"))),
                part("oms.order_items", "rdbms_table", List.of(row("order_id", "X", "line_total", "1200.00"))));

        assertThat(rules.apply(to, Map.of())).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo("critical"));
    }

    @Test
    void aSumInvariantWhoseTableIsNotInScopeIsSkipped() {
        // A shipment_only snapshot has no order totals to check. Not a failure.
        var to = snapshot(part("shipment.legs", "rdbms_table", List.of(row("leg_no", 1))));

        assertThat(rules.apply(to, Map.of())).isEmpty();
    }

    @Test
    void aMissingLineItemMakesTheTotalDisagreeWithTheSum() {
        // The realistic shape of the bug: the order says 1200, but a line item vanished.
        var to = snapshot(
                part("oms.orders", "rdbms_table", List.of(
                        row("order_id", "XXXX", "total_amount", new BigDecimal("1200.00")))),
                part("oms.order_items", "rdbms_table", List.of(
                        row("order_id", "XXXX", "line_total", new BigDecimal("800.00")))));

        assertThat(rules.apply(to, Map.of())).singleElement()
                .satisfies(f -> assertThat(f.afterValue()).isEqualTo(new BigDecimal("800.00")));
    }

    @Test
    void aRoundingDifferenceInsideToleranceIsNotAFinding() {
        var to = snapshot(
                part("oms.orders", "rdbms_table", List.of(
                        row("order_id", "X", "total_amount", new BigDecimal("1200.00")))),
                part("oms.order_items", "rdbms_table", List.of(
                        row("order_id", "X", "line_total", new BigDecimal("1199.995")))));

        // A report that screams about 0.005 gets ignored, which is worse than silence.
        assertThat(rules.apply(to, Map.of())).isEmpty();
    }

    // ---------------------------------------------------------------- cross-source

    @Test
    void sourcesThatDisagreeOnTheSameValueAreAHighFinding() {
        // "RDBMS order total == Dynamo doc total == API response total"
        var to = snapshot(
                part("oms.orders", "rdbms_table", List.of(
                        row("order_id", "XXXX", "total_amount", new BigDecimal("1200.00")))),
                part("dynamo.order_doc", "dynamo_doc", List.of(
                        row("orderId", "XXXX", "totalAmount", new BigDecimal("1200.00")))),
                part("api.order_response", "api_response", List.of(
                        row("orderId", "XXXX", "totalAmount", new BigDecimal("1450.00")))));

        var findings = rules.apply(to, Map.of()).stream()
                .filter(f -> "consistency".equals(f.category())).toList();

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo("high");
            assertThat(f.entityKey()).isEqualTo("XXXX");
            assertThat(f.message()).contains("sources disagree").contains("250.00");
        });
    }

    @Test
    void sourcesThatAgreeRaiseNothing() {
        var to = snapshot(
                part("oms.orders", "rdbms_table", List.of(
                        row("order_id", "X", "total_amount", new BigDecimal("1200.00")))),
                part("dynamo.order_doc", "dynamo_doc", List.of(
                        row("orderId", "X", "totalAmount", new BigDecimal("1200.00")))));

        assertThat(rules.apply(to, Map.of())).isEmpty();
    }

    @Test
    void aSingleSourceHasNothingToDisagreeWith() {
        var to = snapshot(part("oms.orders", "rdbms_table", List.of(
                row("order_id", "X", "total_amount", new BigDecimal("1200.00")))));

        assertThat(rules.apply(to, Map.of()).stream().filter(f -> "consistency".equals(f.category())))
                .isEmpty();
    }

    // ---------------------------------------------------------------- tolerance on changes

    @Test
    void aMoneyChangeBeyondToleranceIsUpgradedToMedium() {
        var structural = List.of(new DiffEngine.Finding("info", "changed", "oms.orders", "XXXX",
                "total_amount", new BigDecimal("1200.00"), new BigDecimal("1450.00"), "changed"));

        assertThat(rules.withinTolerance(structural, Map.of())).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo("medium");
            assertThat(f.category()).isEqualTo("financial");
            assertThat(f.message()).contains("delta 250.00").contains("beyond");
        });
    }

    @Test
    void aMoneyChangeInsideToleranceIsDowngradedButStillReported() {
        var structural = List.of(new DiffEngine.Finding("info", "changed", "oms.orders", "XXXX",
                "total_amount", new BigDecimal("1200.00"), new BigDecimal("1200.005"), "changed"));

        // Downgraded, not dropped: still a change worth seeing, just not worth shouting about.
        assertThat(rules.withinTolerance(structural, Map.of())).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo("info");
            assertThat(f.category()).isEqualTo("money_within_tolerance");
            assertThat(f.message()).contains("within the 0.01 tolerance");
        });
    }

    @Test
    void aNonMoneyFieldIsLeftAlone() {
        var structural = List.of(new DiffEngine.Finding("info", "changed", "oms.orders", "XXXX",
                "status", "CREATED", "FULFILLED", "changed"));

        assertThat(rules.withinTolerance(structural, Map.of())).singleElement()
                .satisfies(f -> assertThat(f.category()).isEqualTo("changed"));
    }

    @Test
    void aComparisonCanOverrideTheConfiguredTolerance() {
        // openapi: rules e.g. {"ignore_columns":["updated_at"],"tolerance":{"amount":0.01}}
        var structural = List.of(new DiffEngine.Finding("info", "changed", "oms.orders", "XXXX",
                "total_amount", new BigDecimal("1200.00"), new BigDecimal("1200.50"), "changed"));

        assertThat(rules.withinTolerance(structural, Map.of("tolerance", Map.of("amount", "1.00"))))
                .singleElement()
                .satisfies(f -> assertThat(f.severity()).as("0.50 is inside a 1.00 tolerance").isEqualTo("info"));
    }
}
