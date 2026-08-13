package com.autwit.copilot.web;

import java.util.List;
import java.util.Map;

import com.autwit.copilot.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 7807 responses, per openapi.yaml's Problem schema (BUILD_BRIEF §4: "web/
 * controllers, ProblemDetail handlers").
 */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    private static final String TYPE_PREFIX = "https://autwit/errors/";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException e) {
        var problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setType(java.net.URI.create(TYPE_PREFIX + e.code().replace('_', '-')));
        problem.setTitle(titleFor(e.status()));
        problem.setProperty("code", e.code());
        return ResponseEntity.status(e.status()).body(problem);
    }

    /** Bean-validation failures become the Problem.errors[] array openapi.yaml documents. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();

        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setType(java.net.URI.create(TYPE_PREFIX + "validation-failed"));
        problem.setTitle("Invalid request");
        problem.setProperty("code", "validation_failed");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * The client went away mid-response — almost always an EventSource on
     * {@code /sessions/{id}/stream} reconnecting, or a closed tab. The response is already
     * gone, so there is nothing to write back; trying to serialise a JSON ProblemDetail onto
     * a committed {@code text/event-stream} response only produces a second error
     * ({@code HttpMessageNotWritableException}) and closes the stream abnormally, which makes
     * the browser reconnect harder. A dropped SSE connection is expected (SseHub is
     * fire-and-forget), so log at debug and return no body. Declared here, more specific than
     * {@link #handleUnexpected}, so it wins the resolver.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void handleClientDisconnected(AsyncRequestNotUsableException e) {
        log.debug("Async request no longer usable (client disconnected): {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        // Log the cause here; the response deliberately does not carry it.
        log.error("Unhandled exception", e);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
        problem.setType(java.net.URI.create(TYPE_PREFIX + "internal"));
        problem.setTitle("Internal Server Error");
        problem.setProperty("code", "internal_error");
        return ResponseEntity.internalServerError().body(problem);
    }

    private static String titleFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "Not found";
            case CONFLICT -> "Conflicting state";
            case BAD_REQUEST -> "Invalid request";
            case GONE -> "Body purged by retention policy";
            case PAYLOAD_TOO_LARGE -> "Artifact too large";
            default -> status.getReasonPhrase();
        };
    }
}
