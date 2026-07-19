package com.autwit.copilot.run;

import java.util.List;
import java.util.Map;

import com.autwit.copilot.orchestrator.dto.InvokeRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKILL_CONTRACT §6.3: copilot-api carries the event cursor and passes it back as
 * {@code since_producer_time}. The orchestrator is stateless, so nobody else can.
 */
class SkillInputsTest {

    private static InvokeRequest.SessionContext context(Map<String, Object> cursors) {
        return new InvokeRequest.SessionContext(
                "qa2", "priya", Map.of("order_id", "XXXX"), List.of(), null, cursors, List.of());
    }

    private static InvokeRequest.SessionContext withCursor(long producerTime) {
        return context(Map.of("order.events", Map.of("0", producerTime)));
    }

    @Test
    void theStoredCursorBecomesSinceProducerTime() {
        var input = SkillInputs.withEventCursor(
                "events.capture_since", Map.of("order_id", "XXXX"), withCursor(1703000013000L));

        assertThat(input)
                .containsEntry("order_id", "XXXX")
                .containsEntry("since_producer_time", 1703000013000L);
    }

    /**
     * The singular topic. `orders.events` was the plural form the contract carried until
     * v0.1.3; a cursor stored under it must not be mistaken for a live one, because
     * mergeCursors keys on the raw string and the two never collide.
     */
    @Test
    void aPluralTopicCursorIsNotUsed() {
        var input = SkillInputs.withEventCursor(
                "events.capture_since", Map.of("order_id", "XXXX"),
                context(Map.of("orders.events", Map.of("0", 10445))));

        assertThat(input).doesNotContainKey("since_producer_time");
    }

    /**
     * First capture of a session. Omitting the key means "from the beginning", which is
     * what we want — sending a null or a zero would be a different and worse claim.
     */
    @Test
    void noCursorMeansNoSinceKey() {
        assertThat(SkillInputs.withEventCursor(
                "events.capture_since", Map.of("order_id", "XXXX"), context(Map.of())))
                .doesNotContainKey("since_producer_time");
    }

    /**
     * An explicit re-read from an older point is a legitimate request. Overriding it
     * would make deliberately re-capturing history impossible.
     */
    @Test
    void anExplicitCallerValueWins() {
        var input = SkillInputs.withEventCursor(
                "events.capture_since",
                Map.of("order_id", "XXXX", "since_producer_time", 1L),
                withCursor(1703000013000L));

        assertThat(input).containsEntry("since_producer_time", 1L);
    }

    @Test
    void otherSkillsAreUntouched() {
        var input = Map.<String, Object>of("scope", "order_flow");

        assertThat(SkillInputs.withEventCursor("snapshot.capture", input, withCursor(1703000013000L)))
                .isSameAs(input);
    }

    /**
     * A malformed cursor degrades to a full re-read — slow but correct — rather than
     * failing the run. Evidence capture should not be lost to a bad cursor shape.
     */
    @Test
    void aMalformedCursorDegradesRatherThanThrows() {
        assertThat(SkillInputs.withEventCursor(
                "events.capture_since", Map.of("order_id", "XXXX"),
                context(Map.of("order.events", "not-a-map"))))
                .doesNotContainKey("since_producer_time");

        assertThat(SkillInputs.withEventCursor(
                "events.capture_since", Map.of("order_id", "XXXX"), context(null)))
                .doesNotContainKey("since_producer_time");
    }
}
