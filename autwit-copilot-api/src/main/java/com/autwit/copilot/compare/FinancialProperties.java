package com.autwit.copilot.compare;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * BUILD_BRIEF §7: "Put the rules in config, not in Java."
 *
 * <p>The reason is that these rules are the part of the product that changes without
 * the code changing. Which fields are money, what tolerance is acceptable, which
 * totals must equal which sums, which sources must agree — those are business facts
 * about an order flow, and encoding them in Java means a deploy every time QA learns
 * something new about the domain.
 */
@ConfigurationProperties(prefix = "autwit.compare.financial")
public record FinancialProperties(

        /** Fields treated as money. Compared with tolerance rather than exact equality. */
        @DefaultValue({"total_amount", "amount", "line_total", "price", "subtotal", "tax", "totalAmount"})
        List<String> moneyFields,

        /**
         * Absolute tolerance for money comparisons. Not zero: independent systems round
         * differently, and a report that screams about 0.001 gets ignored, which is worse
         * than one that says nothing.
         */
        @DefaultValue("0.01") BigDecimal tolerance,

        /** e.g. order total == Σ line items. */
        @DefaultValue List<SumInvariant> sumInvariants,

        /** e.g. RDBMS order total == Dynamo doc total == API response total. */
        @DefaultValue List<CrossSource> crossSource) {

    /**
     * @param totalPart   part_key holding the total, e.g. oms.orders
     * @param totalField  the total column, e.g. total_amount
     * @param entityKey   the column joining total to components, e.g. order_id
     * @param partsPart   part_key holding the components, e.g. oms.order_items
     * @param partsField  the component column, e.g. line_total
     */
    public record SumInvariant(
            String name, String totalPart, String totalField, String entityKey,
            String partsPart, String partsField, @DefaultValue("critical") String severity) {
    }

    /**
     * @param sources part_key + field pairs that must all agree, across systems.
     */
    public record CrossSource(String name, List<Source> sources, @DefaultValue("high") String severity) {

        public record Source(String partKey, String field, String entityKey) {
        }
    }
}
