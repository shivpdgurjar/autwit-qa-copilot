package com.autwit.copilot.compare;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The off-scale severity shim.
 *
 * <p>This used to be covered by `invoke_partial.json` sending `severity: "warn"`. It
 * is a unit test now because that made the fixture wrong: the real orchestrator emits
 * `medium` (SKILL_CONTRACT 0.1.1 §5), so a fixture sending `warn` was quietly asserting
 * that a sender behaves in a way no sender does — and if the orchestrator ever *did*
 * drift, the shim would have absorbed it and the fixture would have looked fine.
 *
 * <p>A fixture must represent what the real orchestrator sends. A defensive shim needs
 * its own test. Conflating the two hides exactly the divergence the fixtures exist to
 * catch.
 *
 * <p>The shim stays, because `warn` was in the contract's own wording for a release and
 * someone will implement against a stale copy of it.
 */
class SeverityTest {

    @Test
    void everyValueOnTheScalePassesThrough() {
        for (var severity : new String[] {"info", "low", "medium", "high", "critical"}) {
            assertThat(Severity.normalize(severity)).isEqualTo(severity);
            assertThat(Severity.isValid(severity)).isTrue();
        }
    }

    @Test
    void warnBecomesMediumRatherThanBlowingUpTheRun() {
        // §5 said "raises a warn finding" until 0.1.1. warn is a Verdict value, not a
        // Severity: the DB's finding_severity_check rejects it, and without this the
        // whole persist transaction unwinds -- turning a documented, expected condition
        // into a hard failure of the entire run.
        assertThat(Severity.isValid("warn")).isFalse();
        assertThat(Severity.normalize("warn")).isEqualTo("medium");
    }

    @Test
    void anUnknownSeverityIsRelabelledNotDropped() {
        // A finding is evidence. Losing one because its label is off-scale hides the
        // thing the tester needs to see.
        assertThat(Severity.normalize("catastrophic")).isEqualTo("medium");
        assertThat(Severity.normalize("")).isEqualTo("medium");
    }

    @Test
    void aNullSeverityIsSurvivable() {
        assertThat(Severity.normalize(null)).isEqualTo("medium");
        assertThat(Severity.isValid(null)).isFalse();
    }

    @Test
    void caseAndWhitespaceAreForgiven() {
        assertThat(Severity.normalize("CRITICAL")).isEqualTo("critical");
        assertThat(Severity.normalize("  High  ")).isEqualTo("high");
    }
}
