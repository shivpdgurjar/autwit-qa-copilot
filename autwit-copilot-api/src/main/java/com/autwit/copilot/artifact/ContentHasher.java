package com.autwit.copilot.artifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

/**
 * sha256 over the canonical artifact body (SKILL_CONTRACT §6.1).
 *
 * <p>copilot-api recomputes this on every artifact and rejects on mismatch, which
 * is what catches a truncated body — a half-written 9-table dump that still parses
 * as valid JSON is otherwise indistinguishable from a real one.
 *
 * <h2>What "canonical" means here</h2>
 *
 * SKILL_CONTRACT §6.1 says "sha256 over the canonical body" but never defines
 * canonical. That is a contract gap, and it is a total one: if the orchestrator
 * canonicalises differently, <em>every</em> artifact is rejected at step 8. It
 * cannot be discovered by testing against our own fixtures, because we generate
 * those with this class. It needs ratifying with the orchestrator session.
 *
 * <p>This is the definition to ratify:
 *
 * <ul>
 *   <li><b>json / jsonb</b> — UTF-8 bytes of the JSON text with object keys sorted
 *       lexicographically at every level, no insignificant whitespace. Numbers keep
 *       the scale they were written with: {@code 1200.00} hashes as {@code 1200.00},
 *       not {@code 1200.0} (hence use-big-decimal-for-floats in application.yml).
 *   <li><b>xml / text / csv / html / md</b> — UTF-8 bytes of the string, byte for
 *       byte. No trimming, no newline normalisation: an artifact is evidence, and
 *       normalising evidence hides the diff you are looking for.
 *   <li><b>binary</b> — the raw bytes, after base64-decoding the transport encoding.
 *       The hash covers the bytes, never the base64 text.
 * </ul>
 *
 * <p>Key sorting is what makes this work across languages: it is the one rule that
 * lets a Python or Node orchestrator agree with us without sharing an
 * implementation. Everything else follows from "hash exactly what was sent".
 */
@Component
public class ContentHasher {

    public static final String PREFIX = "sha256:";

    /**
     * Sorting is applied at serialisation time rather than by rebuilding the tree.
     * ORDER_MAP_ENTRIES_BY_KEYS sorts every nested Map, and generic JSON binding
     * produces Maps all the way down.
     */
    private final ObjectMapper canonical;

    public ContentHasher(ObjectMapper base) {
        this.canonical = base.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.INDENT_OUTPUT);
    }

    /** The bytes that get hashed, and also the bytes stored in the body column. */
    public byte[] canonicalBytes(ArtifactFormat format, Object body) {
        if (body == null) {
            throw new IllegalArgumentException("Cannot canonicalise a null body");
        }
        return switch (format.family()) {
            case JSON -> canonicalJson(body).getBytes(StandardCharsets.UTF_8);
            case TEXT -> asString(format, body).getBytes(StandardCharsets.UTF_8);
            case BINARY -> decodeBase64(body);
        };
    }

    public String hash(ArtifactFormat format, Object body) {
        return hashBytes(canonicalBytes(format, body));
    }

    public String hashBytes(byte[] bytes) {
        return PREFIX + HexFormat.of().formatHex(sha256().digest(bytes));
    }

    /**
     * Constant-time compare. Not because an attacker is timing us, but because it
     * costs nothing and this is the one integrity check in the write path.
     */
    public boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.trim().toLowerCase().getBytes(StandardCharsets.UTF_8),
                actual.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    public String canonicalJson(Object body) {
        try {
            return canonical.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Body is not serialisable as JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String asString(ArtifactFormat format, Object body) {
        if (body instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(
                "format=%s requires a string body, got %s".formatted(format.wire(), body.getClass().getSimpleName()));
    }

    private static byte[] decodeBase64(Object body) {
        if (!(body instanceof String s)) {
            throw new IllegalArgumentException(
                    "format=binary requires a base64 string body, got " + body.getClass().getSimpleName());
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("format=binary body is not valid base64", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
