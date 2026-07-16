package com.autwit.copilot.orchestrator.dto;

import java.util.Map;

/**
 * SKILL_CONTRACT §6.3.
 *
 * @param dedupeHash deterministic over (source, topic, source_offset) when offsets
 *                   exist, else over the canonical payload. This is how "events since
 *                   step 2" works: the orchestrator re-reads from the cursor and
 *                   returns everything it sees, and our unique constraint on
 *                   (session_id, dedupe_hash) makes the delta emerge for free. The
 *                   orchestrator never computes the delta itself.
 */
public record EventDescriptor(
        String source,
        String topic,
        String eventType,
        String eventKey,
        String sourceOffset,
        String occurredAt,
        Map<String, Object> payload,
        String dedupeHash) {
}
