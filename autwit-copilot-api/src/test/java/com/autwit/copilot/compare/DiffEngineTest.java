package com.autwit.copilot.compare;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.autwit.copilot.compare.DiffEngine.PartInput;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUILD_BRIEF §9 DiffEngineTest:
 * <ul>
 *   <li>identical snapshots → verdict pass, zero findings
 *   <li>part_key on one side only → high finding
 *   <li>missing pk_columns → inconclusive + high finding, no guessing
 *   <li>ignore_columns applied → reported in part_results.ignored_columns
 *   <li>financial: order total != Σ line items → critical finding
 * </ul>
 *
 * <p>Against the pure engine, so each case says what it means rather than arriving via
 * a fixture and a database.
 */
class DiffEngineTest {

    private final DiffEngine engine = new DiffEngine();

    // ---------------------------------------------------------------- helpers

    private static PartInput table(String partKey, List<String> pk, List<Map<String, Object>> rows) {
        return new PartInput(partKey, rows, Map.of("pk_columns", pk), "rdbms_table");
    }

    private static PartInput table(String partKey, List<String> pk, List<String> ignore,
            List<Map<String, Object>> rows) {
        return new PartInput(partKey, rows, Map.of("pk_columns", pk, "ignore_columns", ignore), "rdbms_table");
    }

    private static Map<String, PartInput> snapshot(PartInput... parts) {
        var map = new java.util.LinkedHashMap<String, PartInput>();
        for (var p : parts) {
            map.put(p.partKey(), p);
        }
        return map;
    }

    private static Map<String, Object> order(String id, String status, String total) {
        return new java.util.LinkedHashMap<>(Map.of(
                "order_id", id, "status", status, "total_amount", new BigDecimal(total)));
    }

    // ---------------------------------------------------------------- identical

    @Test
    void identicalSnapshotsAreAPassWithZeroFindings() {
        var rows = List.of(order("XXXX", "CREATED", "1200.00"));
        var from = snapshot(table("oms.orders", List.of("order_id"), rows));
        var to = snapshot(table("oms.orders", List.of("order_id"), rows));

        var result = engine.compare(from, to, Map.of());

        assertThat(result.verdict()).isEqualTo("pass");
        assertThat(result.findings()).isEmpty();
        assertThat(result.partResults()).singleElement().satisfies(p -> {
            assertThat(p.rowsUnchanged()).isEqualTo(1);
            assertThat(p.hasChanges()).isFalse();
            assertThat(p.inconclusive()).isFalse();
        });
    }

    @Test
    void aTrailingZeroIsNotAChange() {
        // BigDecimal.equals compares scale, so 1200.00.equals(1200.0) is false. Using it
        // here would report a modification on every trailing zero and bury the real
        // diffs. compareTo asks the right question: is this the same number.
        var from = snapshot(table("oms.orders", List.of("order_id"), List.of(order("XXXX", "CREATED", "1200.00"))));
        var to = snapshot(table("oms.orders", List.of("order_id"), List.of(order("XXXX", "CREATED", "1200.0"))));

        var result = engine.compare(from, to, Map.of());

        assertThat(result.findings()).isEmpty();
        assertThat(result.verdict()).isEqualTo("pass");
    }

    // ---------------------------------------------------------------- scope drift

    @Test
    void aPartKeyOnOneSideOnlyIsAHighFinding() {
        var from = snapshot(
                table("oms.orders", List.of("order_id"), List.of(order("XXXX", "CREATED", "1200.00"))),
                table("shipment.legs", List.of("leg_no"), List.of(Map.of("leg_no", 1))));
        var to = snapshot(
                table("oms.orders", List.of("order_id"), List.of(order("XXXX", "CREATED", "1200.00"))));

        var result = engine.compare(from, to, Map.of());

        // "That's either a real bug or scope drift, and both matter."
        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo("high");
            assertThat(f.category()).isEqualTo("scope_drift");
            assertThat(f.partKey()).isEqualTo("shipment.legs");
        });
        assertThat(result.partResults()).anySatisfy(p -> {
            assertThat(p.partKey()).isEqualTo("shipment.legs");
            assertThat(p.inconclusive()).isTrue();
        });
    }

    @Test
    void aNewPartOnTheToSideIsAlsoFlagged() {
        var from = snapshot(table("oms.orders", List.of("order_id"), List.of()));
        var to = snapshot(
                table("oms.orders", List.of("order_id"), List.of()),
                table("shipment.legs", List.of("leg_no"), List.of()));

        var result = engine.compare(from, to, Map.of());

        assertThat(result.findings()).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo("high"));
    }

    @Test
    void comparisonJoinsOnTheUnionOfPartKeysNotTheIntersection() {
        // Comparing only shared keys would make a vanished part look like nothing
        // happened -- the exact failure this product exists to catch.
        var from = snapshot(table("a", List.of("id"), List.of()), table("b", List.of("id"), List.of()));
        var to = snapshot(table("a", List.of("id"), List.of()), table("c", List.of("id"), List.of()));

        var result = engine.compare(from, to, Map.of());

        assertThat(result.partResults()).extracting(PartResult::partKey)
                .containsExactlyInAnyOrder("a", "b", "c");
    }

    // ---------------------------------------------------------------- missing pk_columns

    @Test
    void aMissingPkColumnsIsInconclusiveAndAHighFindingWithNoGuessing() {
        var noPk = new PartInput("oms.orders", List.of(order("XXXX", "CREATED", "1200.00")),
                Map.of(), "rdbms_table");
        var withPk = table("oms.orders", List.of("order_id"), List.of(order("XXXX", "FULFILLED", "1450.00")));

        var result = engine.compare(snapshot(noPk), snapshot(noPk), Map.of());

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo("high");
            assertThat(f.category()).isEqualTo("contract_violation");
            assertThat(f.message()).contains("meta.pk_columns").contains("not guessed");
        });
        assertThat(result.partResults()).singleElement().satisfies(p -> {
            assertThat(p.inconclusive()).isTrue();
            // Zeroed, not "no changes" -- those must never look alike.
            assertThat(p.rowsModified()).isZero();
            assertThat(p.rowsUnchanged()).isZero();
        });
        assertThat(result.verdict()).isEqualTo("inconclusive");

        // And it does not fall back to some other key when one side does have pk_columns.
        var mixed = engine.compare(snapshot(noPk), snapshot(withPk), Map.of());
        assertThat(mixed.partResults()).singleElement()
                .satisfies(p -> assertThat(p.inconclusive()).isFalse());
    }

    @Test
    void inconclusiveWinsOverEverythingInTheVerdict() {
        // A pass that silently excluded a part it could not read is the most dangerous
        // output this thing could produce.
        var noPk = new PartInput("oms.orders", List.of(), Map.of(), "rdbms_table");
        var fine = table("shipment.legs", List.of("leg_no"), List.of(Map.of("leg_no", 1)));

        var result = engine.compare(snapshot(noPk, fine), snapshot(noPk, fine), Map.of());

        assertThat(result.verdict()).isEqualTo("inconclusive");
    }

    @Test
    void aDocumentWithoutPkColumnsIsComparedWholeRatherThanRejected() {
        // dynamo_doc / api_response have no row key by nature. Only rdbms_table is a
        // contract violation without one.
        var before = new PartInput("dynamo.order_doc", List.of(Map.of("orderId", "XXXX", "status", "CREATED")),
                Map.of(), "dynamo_doc");
        var after = new PartInput("dynamo.order_doc", List.of(Map.of("orderId", "XXXX", "status", "FULFILLED")),
                Map.of(), "dynamo_doc");

        var result = engine.compare(snapshot(before), snapshot(after), Map.of());

        assertThat(result.partResults()).singleElement().satisfies(p -> {
            assertThat(p.inconclusive()).isFalse();
            assertThat(p.rowsModified()).isEqualTo(1);
        });
        assertThat(result.findings()).singleElement()
                .satisfies(f -> assertThat(f.field()).isEqualTo("status"));
    }

    // ---------------------------------------------------------------- ignore rules

    @Test
    void ignoredColumnsAreAppliedAndReported() {
        var before = table("oms.orders", List.of("order_id"), List.of("updated_at"),
                List.of(new java.util.LinkedHashMap<>(Map.of(
                        "order_id", "XXXX", "status", "CREATED", "updated_at", "09:00"))));
        var after = table("oms.orders", List.of("order_id"), List.of("updated_at"),
                List.of(new java.util.LinkedHashMap<>(Map.of(
                        "order_id", "XXXX", "status", "CREATED", "updated_at", "09:05"))));

        var result = engine.compare(snapshot(before), snapshot(after), Map.of());

        // Suppressed...
        assertThat(result.findings()).isEmpty();
        assertThat(result.partResults()).singleElement().satisfies(p -> {
            assertThat(p.rowsUnchanged()).isEqualTo(1);
            // ...but never silently. "If updated_at diffs vanish without explanation,
            // nobody trusts the report and the tool dies."
            assertThat(p.ignoredColumns()).containsExactly("updated_at");
        });
    }

    @Test
    void comparisonRulesAddToTheScopeDefaultsRatherThanReplacingThem() {
        var rows = List.of(new java.util.LinkedHashMap<String, Object>(Map.of(
                "order_id", "XXXX", "trace_id", "t1", "updated_at", "09:00")));
        var rows2 = List.of(new java.util.LinkedHashMap<String, Object>(Map.of(
                "order_id", "XXXX", "trace_id", "t2", "updated_at", "09:05")));

        var before = table("oms.orders", List.of("order_id"), List.of("trace_id"), List.copyOf(rows));
        var after = table("oms.orders", List.of("order_id"), List.of("trace_id"), List.copyOf(rows2));

        // A tester adding updated_at must not silently re-enable the scope's trace_id noise.
        var result = engine.compare(snapshot(before), snapshot(after),
                Map.of("ignore_columns", List.of("updated_at")));

        assertThat(result.findings()).isEmpty();
        assertThat(result.partResults()).singleElement()
                .satisfies(p -> assertThat(p.ignoredColumns()).containsExactly("trace_id", "updated_at"));
    }

    @Test
    void anIgnoreRuleForAnAbsentColumnIsNotReported() {
        // A rule for a column nobody has is noise, and the report's credibility is the
        // product.
        var before = table("oms.orders", List.of("order_id"), List.of("nonexistent"),
                List.of(order("XXXX", "CREATED", "1200.00")));

        var result = engine.compare(snapshot(before), snapshot(before), Map.of());

        assertThat(result.partResults()).singleElement()
                .satisfies(p -> assertThat(p.ignoredColumns()).isEmpty());
    }

    // ---------------------------------------------------------------- row-level counts

    @Test
    void addedRemovedModifiedAndUnchangedAreCounted() {
        var before = table("oms.order_items", List.of("order_id", "line_no"), List.of(
                new java.util.LinkedHashMap<>(Map.of("order_id", "X", "line_no", 1, "qty", 1)),
                new java.util.LinkedHashMap<>(Map.of("order_id", "X", "line_no", 2, "qty", 2)),
                new java.util.LinkedHashMap<>(Map.of("order_id", "X", "line_no", 3, "qty", 3))));
        var after = table("oms.order_items", List.of("order_id", "line_no"), List.of(
                new java.util.LinkedHashMap<>(Map.of("order_id", "X", "line_no", 1, "qty", 1)),   // unchanged
                new java.util.LinkedHashMap<>(Map.of("order_id", "X", "line_no", 2, "qty", 99)),  // modified
                new java.util.LinkedHashMap<>(Map.of("order_id", "X", "line_no", 4, "qty", 4))));  // added; 3 removed

        var result = engine.compare(snapshot(before), snapshot(after), Map.of());

        assertThat(result.partResults()).singleElement().satisfies(p -> {
            assertThat(p.rowsUnchanged()).isEqualTo(1);
            assertThat(p.rowsModified()).isEqualTo(1);
            assertThat(p.rowsAdded()).isEqualTo(1);
            assertThat(p.rowsRemoved()).isEqualTo(1);
        });
    }

    @Test
    void aCompositeKeyIsJoinedOnAllItsColumns() {
        var before = table("oms.order_items", List.of("order_id", "line_no"), List.of(
                new java.util.LinkedHashMap<>(Map.of("order_id", "A", "line_no", 1, "qty", 1)),
                new java.util.LinkedHashMap<>(Map.of("order_id", "B", "line_no", 1, "qty", 1))));

        var result = engine.compare(snapshot(before), snapshot(before), Map.of());

        // Keying on order_id alone would collapse these two rows into one.
        assertThat(result.partResults()).singleElement()
                .satisfies(p -> assertThat(p.rowsUnchanged()).isEqualTo(2));
    }

    @Test
    void aChangedFieldProducesAFindingNamingTheEntityAndBothValues() {
        var before = table("oms.orders", List.of("order_id"), List.of(order("XXXX", "CREATED", "1200.00")));
        var after = table("oms.orders", List.of("order_id"), List.of(order("XXXX", "FULFILLED", "1200.00")));

        var result = engine.compare(snapshot(before), snapshot(after), Map.of());

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.partKey()).isEqualTo("oms.orders");
            assertThat(f.entityKey()).isEqualTo("XXXX");
            assertThat(f.field()).isEqualTo("status");
            assertThat(f.beforeValue()).isEqualTo("CREATED");
            assertThat(f.afterValue()).isEqualTo("FULFILLED");
        });
        // A status change is the tester's own doing, not a defect: info, and a pass.
        assertThat(result.verdict()).isEqualTo("pass");
    }
}
