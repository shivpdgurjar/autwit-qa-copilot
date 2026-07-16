package com.autwit.copilot.compare;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * openapi.yaml Severity, and the normalisation the orchestrator boundary needs.
 *
 * <h2>Why "warn" needs handling</h2>
 *
 * SKILL_CONTRACT §5 instructs copilot-api to raise a <em>"warn finding"</em> when a
 * capture comes back partial. There is no {@code warn} severity: the enum is
 * info/low/medium/high/critical in openapi.yaml and in the DB's
 * {@code finding_severity_check}. {@code warn} is a <em>Verdict</em> value
 * (pass/fail/warn/inconclusive) — the contract conflates the two scales, and §6.4's
 * own example uses {@code high}.
 *
 * <p>An orchestrator implementing §5 literally will therefore send
 * {@code severity: "warn"}, and without this every partial run would die on a check
 * constraint — turning a documented, expected condition into a hard failure.
 *
 * <p>So unknown severities are mapped rather than rejected. A finding is evidence:
 * dropping one because its label is off-scale would hide the very thing the tester
 * needs to see. The mapping is logged, never silent, and is raised as Q4 in
 * CONTRACT_RATIFICATION_REQUEST.md.
 */
public final class Severity {

    private static final Logger log = LoggerFactory.getLogger(Severity.class);

    public static final String INFO = "info";
    public static final String LOW = "low";
    public static final String MEDIUM = "medium";
    public static final String HIGH = "high";
    public static final String CRITICAL = "critical";

    private static final Set<String> VALID = Set.of(INFO, LOW, MEDIUM, HIGH, CRITICAL);

    /** Where an off-scale severity lands: visible, but not crying wolf. */
    private static final String FALLBACK = MEDIUM;

    private Severity() {
    }

    public static boolean isValid(String severity) {
        return severity != null && VALID.contains(severity.toLowerCase());
    }

    /**
     * @return a severity the DB will accept. Never throws: rejecting the row would lose
     *         the finding, and the finding is the product.
     */
    public static String normalize(String severity) {
        if (severity == null) {
            return FALLBACK;
        }
        var lower = severity.toLowerCase().trim();
        if (VALID.contains(lower)) {
            return lower;
        }

        log.warn("Finding severity '{}' is not one of {}; storing it as '{}'. "
                + "SKILL_CONTRACT §5 says \"warn finding\" but warn is a Verdict, not a Severity — "
                + "see CONTRACT_RATIFICATION_REQUEST.md Q4.", severity, VALID, FALLBACK);
        return FALLBACK;
    }
}
