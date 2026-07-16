package com.autwit.copilot.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.session.CreateSessionRequest;
import com.autwit.copilot.session.Session;
import com.autwit.copilot.session.SessionDetail;
import com.autwit.copilot.session.SessionService;
import com.autwit.copilot.session.Step;
import com.autwit.copilot.session.StepRepository;
import com.autwit.copilot.session.UpdateSessionRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
public class SessionController {

    private final SessionService sessions;
    private final StepRepository steps;

    public SessionController(SessionService sessions, StepRepository steps) {
        this.sessions = sessions;
        this.steps = steps;
    }

    @PostMapping
    ResponseEntity<Session> createSession(@Valid @RequestBody CreateSessionRequest req) {
        var session = sessions.create(req);
        return ResponseEntity.created(URI.create("/api/v1/sessions/" + session.sessionId())).body(session);
    }

    @GetMapping
    Map<String, Object> listSessions(
            @RequestParam(required = false) String status,
            @RequestParam(name = "tester_id", required = false) String testerId,
            @RequestParam(required = false) String env,
            @RequestParam(defaultValue = "50") int limit) {
        return Map.of("sessions", sessions.list(status, testerId, env, Math.min(limit, 200)));
    }

    /** THE source of truth. The UI hydrates from this and refetches on any SSE hint. */
    @GetMapping("/{sessionId}")
    SessionDetail getSession(@PathVariable UUID sessionId) {
        return sessions.detail(sessionId);
    }

    @PatchMapping("/{sessionId}")
    Session updateSession(@PathVariable UUID sessionId, @RequestBody UpdateSessionRequest req) {
        return sessions.update(sessionId, req);
    }

    @GetMapping("/{sessionId}/steps")
    Map<String, List<Step>> listSteps(
            @PathVariable UUID sessionId,
            @RequestParam(name = "since_seq", required = false) Integer sinceSeq) {
        sessions.get(sessionId); // 404 for an unknown session rather than an empty list
        return Map.of("steps", steps.listBySession(sessionId, sinceSeq));
    }
}
