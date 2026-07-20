package com.autwit.copilot.analysis;

import java.util.Optional;

/**
 * The eleven source systems the orchestrator recognises (their
 * {@code financial/domain/types.ts} {@code SourceSystem}). The name is the wire value.
 *
 * <p>{@code UNKNOWN} is the honest default when a piece of session evidence does not map
 * to a named system — a captured artifact from {@code shipment_pg}, say, has no financial
 * source. Better {@code UNKNOWN} than a confident wrong guess; the tester can correct it.
 */
public enum SourceSystem {
    ORDER_DB,
    CALCULATE_API,
    UPDATE_LINES_API,
    CANCEL_CALCULATE_API,
    TAX_EXEMPTION_API,
    ISSUE_CREDIT_API,
    INVOICE_DB,
    PAYMENT_DB,
    KAFKA_EVENT,
    REFUND_SERVICE,
    UNKNOWN;

    public static Optional<SourceSystem> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(SourceSystem.valueOf(value.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
