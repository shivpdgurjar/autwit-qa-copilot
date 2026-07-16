package com.autwit.copilot.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Shared ResultSet readers for the types this schema uses. */
public final class Columns {

    private Columns() {
    }

    /**
     * timestamptz. Read as OffsetDateTime rather than Timestamp: Timestamp applies
     * the JVM's default zone, which quietly shifts every instant when the server
     * and the test box disagree about time zones.
     */
    public static Instant instant(ResultSet rs, String column) throws SQLException {
        var odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    /** Nullable int, as distinct from 0. */
    public static Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
