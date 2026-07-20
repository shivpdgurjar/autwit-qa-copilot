package com.autwit.copilot.events;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.common.Columns;
import com.autwit.copilot.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<EventRecord> mapper;

    public EventRepository(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.mapper = (rs, n) -> new EventRecord(
                Columns.uuid(rs, "event_id"),
                Columns.uuid(rs, "session_id"),
                Columns.uuid(rs, "artifact_id"),
                rs.getString("source"),
                rs.getString("topic"),
                rs.getString("event_type"),
                rs.getString("event_key"),
                rs.getString("source_offset"),
                Columns.instant(rs, "occurred_at"),
                Columns.instant(rs, "captured_at"),
                Columns.uuid(rs, "after_milestone_id"),
                json.readObject(rs.getString("payload")));
    }

    /** One event by id. Used when the tester selects an event to analyse (financial analysis). */
    public java.util.Optional<EventRecord> findById(UUID eventId) {
        return jdbc.query("select * from autwit.event_record where event_id = ?", mapper, eventId)
                .stream().findFirst();
    }

    /**
     * Keyset pagination on (captured_at, event_id).
     *
     * <p>Not OFFSET: a session under capture is appended to while the UI reads it, and
     * OFFSET would skip or repeat rows as the set grows underneath. Ordered by
     * captured_at rather than occurred_at because occurred_at is nullable — an event
     * the source never timestamped would sort unpredictably and could vanish from a
     * page entirely.
     */
    public Page list(UUID sessionId, UUID afterMilestoneId, String eventType, int limit, String cursor) {
        var key = Cursor.decode(cursor);

        var events = jdbc.query(
                """
                select * from autwit.event_record
                where session_id = ?
                  and (?::uuid is null or after_milestone_id = ?)
                  and (?::text is null or event_type = ?)
                  and (?::timestamptz is null
                       or (captured_at, event_id) > (?::timestamptz, ?::uuid))
                order by captured_at, event_id
                limit ?
                """,
                mapper,
                sessionId, afterMilestoneId, afterMilestoneId, eventType, eventType,
                key == null ? null : java.sql.Timestamp.from(key.capturedAt()),
                key == null ? null : java.sql.Timestamp.from(key.capturedAt()),
                key == null ? null : key.eventId(),
                limit + 1);

        // Asking for one more than requested is how we know there is a next page without
        // a second count query.
        var hasMore = events.size() > limit;
        var page = hasMore ? events.subList(0, limit) : events;
        var next = hasMore && !page.isEmpty()
                ? Cursor.encode(page.get(page.size() - 1))
                : null;

        return new Page(List.copyOf(page), next);
    }

    public record Page(List<EventRecord> events, String nextCursor) {
    }

    /** Opaque to the client on purpose: it is a keyset, not a row number. */
    private record Cursor(Instant capturedAt, UUID eventId) {

        static String encode(EventRecord last) {
            var raw = last.capturedAt().toString() + "|" + last.eventId();
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                var raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                var parts = raw.split("\\|", 2);
                return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new ApiException.BadRequest("invalid_cursor",
                        "The cursor is not one we issued. Omit it to start from the beginning.");
            }
        }
    }
}
