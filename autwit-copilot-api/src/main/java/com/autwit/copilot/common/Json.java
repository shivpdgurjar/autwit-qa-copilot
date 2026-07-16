package com.autwit.copilot.common;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * jsonb ↔ Java conversion for the repositories.
 *
 * <p>Every jsonb column is read as text and parsed here, and written as a
 * {@code ?::jsonb} cast. That is deliberate over {@code PGobject}: it keeps the
 * driver types out of the repositories and the casts visible in the SQL.
 *
 * <p>This uses the MVC {@link ObjectMapper}, so it inherits the snake_case naming
 * strategy. That is what we want — jsonb columns hold contract-shaped documents
 * (meta.pk_columns, subjects, detail), and they should look identical in the
 * database and on the wire.
 */
@Component
public class Json {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Value is not serialisable to JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** Writes a jsonb column value, substituting an empty object for null. */
    public String writeOrEmptyObject(Object value) {
        return value == null ? "{}" : write(value);
    }

    public Map<String, Object> readObject(String json) {
        return read(json, OBJECT_MAP);
    }

    public Map<String, String> readStringMap(String json) {
        return read(json, STRING_MAP);
    }

    public <T> T read(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            // A parse failure here means the column holds something we did not write.
            throw new IllegalStateException("Malformed JSON read from the database: " + e.getOriginalMessage(), e);
        }
    }

    public Object readTree(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed JSON read from the database: " + e.getOriginalMessage(), e);
        }
    }
}
