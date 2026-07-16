package com.autwit.copilot.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.Artifact;
import com.autwit.copilot.artifact.ArtifactService;
import com.autwit.copilot.artifact.CreateArtifactRequest;
import com.autwit.copilot.session.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ArtifactController {

    private final ArtifactService artifacts;
    private final SessionService sessions;

    public ArtifactController(ArtifactService artifacts, SessionService sessions) {
        this.artifacts = artifacts;
        this.sessions = sessions;
    }

    /**
     * Tester-supplied artifact. 201 rather than 202: this touches no orchestrator and
     * is a local insert, so it is not a run (invariant 2).
     */
    @PostMapping("/sessions/{sessionId}/artifacts")
    ResponseEntity<Artifact> createArtifact(
            @PathVariable UUID sessionId,
            @Valid @RequestBody CreateArtifactRequest req) {

        var artifact = artifacts.create(sessionId, req);
        return ResponseEntity
                .created(URI.create("/api/v1/artifacts/" + artifact.artifactId()))
                .body(artifact);
    }

    /** Metadata only — never bodies. */
    @GetMapping("/sessions/{sessionId}/artifacts")
    Map<String, List<Artifact>> listArtifacts(
            @PathVariable UUID sessionId,
            @RequestParam(name = "artifact_type", required = false) String artifactType,
            @RequestParam(name = "milestone_id", required = false) UUID milestoneId,
            @RequestParam(name = "logical_name", required = false) String logicalName) {

        sessions.get(sessionId);
        return Map.of("artifacts", artifacts.list(sessionId, artifactType, milestoneId, logicalName));
    }

    @GetMapping("/artifacts/{artifactId}")
    Artifact getArtifact(@PathVariable UUID artifactId) {
        return artifacts.get(artifactId);
    }

    /** Raw body with its native content type, for download and for the drawer's viewers. */
    @GetMapping("/artifacts/{artifactId}/raw")
    ResponseEntity<byte[]> getArtifactRaw(@PathVariable UUID artifactId) {
        var format = artifacts.formatOf(artifactId);
        var body = artifacts.rawBody(artifactId);
        return ResponseEntity.ok()
                .header("Content-Type", format.contentType())
                .body(body);
    }
}
