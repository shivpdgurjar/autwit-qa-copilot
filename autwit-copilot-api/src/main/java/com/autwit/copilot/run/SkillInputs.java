package com.autwit.copilot.run;

import java.util.LinkedHashMap;
import java.util.Map;

import com.autwit.copilot.orchestrator.dto.InvokeRequest;

/**
 * Fills in skill inputs that copilot-api owns rather than the caller.
 *
 * <p>Today that is exactly one: {@code events.capture_since}'s {@code since_producer_time}.
 * SKILL_CONTRACT §6.3 makes this our job explicitly — "copilot-api stores it in
 * {@code session_context.event_cursors} and passes it back as {@code since_producer_time}
 * on the next call — that is the 'since'." The orchestrator is stateless and cannot
 * remember where the last capture stopped, so if we do not supply it, every capture
 * re-reads the order's whole history. That is not incorrect — {@code dedupe_hash} plus
 * our unique constraint drop the overlap — but it is unbounded work that grows with the
 * order, and the cursor exists to avoid it.
 *
 * <p>Neither the ⌘K palette nor the LLM can supply this: the palette renders a form from
 * {@code input_schema} and a tester has no idea what epoch-millis cursor the last
 * milestone reached, and the LLM never sees our cursor table.
 */
final class SkillInputs {

    /** §6.3: the only emitter of order-scoped event cursors today. */
    static final String EVENTS_CAPTURE_SINCE = "events.capture_since";

    /** §6.3 cursor key and partition. Singular `order.events` — see the v0.1.3 changelog. */
    static final String CURSOR_TOPIC = "order.events";
    static final String CURSOR_PARTITION = "0";

    static final String SINCE_PRODUCER_TIME = "since_producer_time";

    private SkillInputs() {
    }

    /**
     * Returns {@code input} with {@code since_producer_time} filled from the session's
     * stored cursor, where that applies.
     *
     * <p>Left untouched when: the skill is not {@code events.capture_since}; the caller
     * already supplied the value (an explicit re-read from an older point is a legitimate
     * thing to ask for, and silently overriding it would make that impossible); or there
     * is no cursor yet, in which case omitting the key is correct — it means "capture
     * from the beginning", which is what a first capture wants.
     */
    static Map<String, Object> withEventCursor(String skillName, Map<String, Object> input,
            InvokeRequest.SessionContext context) {

        if (!EVENTS_CAPTURE_SINCE.equals(skillName)) {
            return input;
        }
        if (input != null && input.containsKey(SINCE_PRODUCER_TIME)) {
            return input;
        }

        var cursor = cursorValue(context);
        if (cursor == null) {
            return input;
        }

        var enriched = new LinkedHashMap<String, Object>(input == null ? Map.of() : input);
        enriched.put(SINCE_PRODUCER_TIME, cursor);
        return enriched;
    }

    /**
     * Digs {@code {"order.events": {"0": <epoch millis>}}} out of the session context.
     *
     * <p>Returns {@code null} rather than throwing on any shape that is not that: a
     * malformed cursor should degrade to a full re-read, which is slow but correct, not
     * fail the run. The value is returned as a {@link Number} untouched — §6.3 calls
     * {@code source_offset} an opaque ordering token, and while the cursor is a real
     * epoch-millis integer we have no reason to reinterpret what the orchestrator sent.
     */
    private static Number cursorValue(InvokeRequest.SessionContext context) {
        if (context == null || context.eventCursors() == null) {
            return null;
        }
        if (!(context.eventCursors().get(CURSOR_TOPIC) instanceof Map<?, ?> partitions)) {
            return null;
        }
        return partitions.get(CURSOR_PARTITION) instanceof Number n ? n : null;
    }
}
