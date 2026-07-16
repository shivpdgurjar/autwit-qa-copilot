package com.autwit.copilot.compare;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * openapi.yaml ComparisonDetail.part_results[].
 *
 * @param ignoredColumns surfaced deliberately. BUILD_BRIEF §10 and §7: ignore rules
 *                       are always surfaced, never applied silently. "If updated_at
 *                       diffs vanish without explanation, nobody trusts the report
 *                       and the tool dies. This is a product requirement, not a
 *                       nicety."
 * @param inconclusive   true when the part could not be compared at all — a missing
 *                       pk_columns, or a part_key present on one side only. The
 *                       counts are then meaningless and must not be read as "no
 *                       changes".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartResult(
        String partKey,
        int rowsAdded,
        int rowsRemoved,
        int rowsModified,
        int rowsUnchanged,
        List<String> ignoredColumns,
        boolean inconclusive,
        String reason) {

    public static PartResult of(String partKey, int added, int removed, int modified, int unchanged,
            List<String> ignoredColumns) {
        return new PartResult(partKey, added, removed, modified, unchanged, ignoredColumns, false, null);
    }

    /** Zeroed counts, because "0 changes" and "could not tell" must never look alike. */
    public static PartResult inconclusive(String partKey, String reason) {
        return new PartResult(partKey, 0, 0, 0, 0, List.of(), true, reason);
    }

    public boolean hasChanges() {
        return rowsAdded > 0 || rowsRemoved > 0 || rowsModified > 0;
    }
}
