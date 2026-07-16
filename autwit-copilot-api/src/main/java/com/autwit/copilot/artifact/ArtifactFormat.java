package com.autwit.copilot.artifact;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.http.MediaType;

/**
 * openapi.yaml's ArtifactFormat, plus the dispatch every caller needs: which body
 * column a format lands in, and what content type it is served as.
 *
 * <p>Keeping that here rather than in ArtifactService is what stops the three-way
 * body_jsonb/body_text/body_bytes decision from being re-derived — subtly
 * differently — at each call site.
 */
public enum ArtifactFormat {

    JSON("json", Family.JSON, MediaType.APPLICATION_JSON_VALUE),
    JSONB("jsonb", Family.JSON, MediaType.APPLICATION_JSON_VALUE),
    XML("xml", Family.TEXT, MediaType.APPLICATION_XML_VALUE),
    TEXT("text", Family.TEXT, MediaType.TEXT_PLAIN_VALUE),
    CSV("csv", Family.TEXT, "text/csv"),
    HTML("html", Family.TEXT, MediaType.TEXT_HTML_VALUE),
    MD("md", Family.TEXT, "text/markdown"),
    BINARY("binary", Family.BINARY, MediaType.APPLICATION_OCTET_STREAM_VALUE);

    /** Which body column the format stores into. Mirrors the one_body constraint. */
    public enum Family {
        JSON, TEXT, BINARY
    }

    private final String wire;
    private final Family family;
    private final String contentType;

    ArtifactFormat(String wire, Family family, String contentType) {
        this.wire = wire;
        this.family = family;
        this.contentType = contentType;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    public Family family() {
        return family;
    }

    /** Native content type for GET /artifacts/{id}/raw. */
    public String contentType() {
        return contentType;
    }

    @JsonCreator
    public static ArtifactFormat of(String wire) {
        return Arrays.stream(values())
                .filter(f -> f.wire.equalsIgnoreCase(wire))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown artifact format '%s'. Known: %s".formatted(wire, Arrays.toString(values()))));
    }
}
