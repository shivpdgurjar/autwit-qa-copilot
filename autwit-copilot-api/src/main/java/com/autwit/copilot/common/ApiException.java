package com.autwit.copilot.common;

import org.springframework.http.HttpStatus;

/**
 * Base for errors that map to an RFC 7807 response.
 *
 * <p>{@code code} is the machine-readable discriminator openapi.yaml's Problem
 * schema documents (e.g. {@code session_ended}, {@code content_hash_mismatch}). The
 * UI switches on it, so it is part of the contract, not a log string.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 404. */
    public static class NotFound extends ApiException {
        public NotFound(String what, Object id) {
            super(HttpStatus.NOT_FOUND, "not_found", "%s %s not found".formatted(what, id));
        }
    }

    /** 409. */
    public static class Conflict extends ApiException {
        public Conflict(String code, String message) {
            super(HttpStatus.CONFLICT, code, message);
        }
    }

    /** 400. */
    public static class BadRequest extends ApiException {
        public BadRequest(String code, String message) {
            super(HttpStatus.BAD_REQUEST, code, message);
        }
    }

    /** 413. */
    public static class PayloadTooLarge extends ApiException {
        public PayloadTooLarge(String message) {
            super(HttpStatus.PAYLOAD_TOO_LARGE, "artifact_too_large", message);
        }
    }

    /**
     * 410. The artifact row survives retention; only the body is nulled, so the
     * metadata is still served and the trace stays intact.
     */
    public static class Gone extends ApiException {
        public Gone(String message) {
            super(HttpStatus.GONE, "artifact_purged", message);
        }
    }
}
