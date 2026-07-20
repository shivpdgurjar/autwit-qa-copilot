package com.autwit.copilot.analysis;

import java.util.Optional;

/**
 * The nine state kinds the orchestrator's financial engine keys off
 * (their {@code financial/domain/types.ts}, mirrored in V2's {@code state_type} CHECK).
 *
 * <p>The name IS the wire value and the stored value — {@code ORDER_SNAPSHOT.name()} is
 * exactly what goes over the wire and into {@code analysis_state.state_type}. Kept as a
 * Java enum so a bad projection is a compile error on our side rather than a CHECK
 * violation at insert, and so the tester's override in the picker resolves to a known
 * value or is rejected before it reaches Postgres.
 *
 * <p>Which rules apply to which type is the orchestrator's to state (asked in v1.0.17
 * §4.2); we only tag. The financial rules mis-fire on a wrong tag, which is why the tag
 * is tester-overridable rather than a silent guess.
 */
public enum StateType {
    ORDER_SNAPSHOT,
    API_REQUEST,
    API_RESPONSE,
    DOMAIN_EVENT,
    INVOICE_SNAPSHOT,
    PAYMENT_SNAPSHOT,
    REFUND_EVENT,
    CALCULATION_RESULT,
    OTHER;

    /** Parses a tester override / stored value, empty if it is not one of the nine. */
    public static Optional<StateType> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(StateType.valueOf(value.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
