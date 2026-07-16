package com.autwit.copilot.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.autwit.copilot.common.Json;
import com.autwit.copilot.orchestrator.dto.EventDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The delta-for-free mechanism (SKILL_CONTRACT §6.3).
 *
 * <p>The orchestrator does not compute "events since step 2". It re-reads from the
 * cursor in session_context and returns everything it sees; the unique constraint on
 * (session_id, dedupe_hash) plus ON CONFLICT DO NOTHING makes the delta emerge. That
 * is the whole design — an orchestrator that tried to compute the delta itself would
 * need state, and it is deliberately stateless.
 */
@Service
public class EventIngestService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestService.class);

    private final JdbcTemplate jdbc;
    private final Json json;

    public EventIngestService(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @return how many events were genuinely new. Overlap is dropped silently, which
     *         is the point: re-reading from a cursor always returns events we already
     *         have, and that is not an error.
     */
    public int ingest(UUID sessionId, UUID artifactId, UUID afterMilestoneId, List<EventDescriptor> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }

        int inserted = 0;
        for (var e : events) {
            inserted += jdbc.update(
                    """
                    insert into autwit.event_record
                      (session_id, artifact_id, source, topic, event_type, event_key, source_offset,
                       occurred_at, after_milestone_id, payload, dedupe_hash)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    on conflict (session_id, dedupe_hash) do nothing
                    """,
                    sessionId, artifactId, e.source(), e.topic(), e.eventType(), e.eventKey(),
                    e.sourceOffset(), toTimestamp(e.occurredAt()), afterMilestoneId,
                    json.writeOrEmptyObject(e.payload()), e.dedupeHash());
        }

        if (inserted < events.size()) {
            log.debug("Ingested {} of {} events for session {}; {} were already known",
                    inserted, events.size(), sessionId, events.size() - inserted);
        }
        return inserted;
    }

    public int countBySession(UUID sessionId) {
        return jdbc.queryForObject(
                "select count(*) from autwit.event_record where session_id = ?", Integer.class, sessionId);
    }

    private static Timestamp toTimestamp(String iso) {
        return iso == null ? null : Timestamp.from(Instant.parse(iso));
    }
}
