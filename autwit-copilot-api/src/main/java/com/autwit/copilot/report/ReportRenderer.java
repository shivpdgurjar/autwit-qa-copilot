package com.autwit.copilot.report;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.ArtifactFormat;
import com.autwit.copilot.artifact.ArtifactService;
import com.autwit.copilot.compare.ComparisonRepository;
import com.autwit.copilot.compare.FindingRepository;
import com.autwit.copilot.events.EventIngestService;
import com.autwit.copilot.session.SessionService;
import com.autwit.copilot.snapshot.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders the session report (BUILD_BRIEF §8 step 7: "/end produces a downloadable
 * html").
 *
 * <p>Stored as an artifact like everything else, so it survives retention: the purge
 * sweep skips final_report and diff_report deliberately — the bodies go, the
 * conclusions stay.
 *
 * <p>Rendered as a run because it reads the whole session and is not instant, which
 * invariant 2 says makes it a run. It touches no orchestrator.
 */
@Service
public class ReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(ReportRenderer.class);

    private final TemplateEngine templates;
    private final SessionService sessions;
    private final SnapshotRepository snapshots;
    private final ComparisonRepository comparisons;
    private final FindingRepository findings;
    private final EventIngestService events;
    private final ArtifactService artifacts;

    public ReportRenderer(TemplateEngine templates, SessionService sessions, SnapshotRepository snapshots,
            ComparisonRepository comparisons, FindingRepository findings, EventIngestService events,
            ArtifactService artifacts) {
        this.templates = templates;
        this.sessions = sessions;
        this.snapshots = snapshots;
        this.comparisons = comparisons;
        this.findings = findings;
        this.events = events;
        this.artifacts = artifacts;
    }

    /**
     * @param format html, md, or both
     * @return the artifact ids created
     */
    @Transactional
    public List<UUID> render(UUID sessionId, UUID stepId, UUID runId, String format, String notes) {
        var context = buildContext(sessionId, notes);
        var created = new java.util.ArrayList<UUID>();

        if (!"md".equals(format)) {
            created.add(store(sessionId, stepId, runId, "report.html", ArtifactFormat.HTML,
                    templates.process("report", context)));
        }
        if ("md".equals(format) || "both".equals(format)) {
            created.add(store(sessionId, stepId, runId, "report.md", ArtifactFormat.MD,
                    templates.process("report-md", context)));
        }

        log.info("Rendered {} report artifact(s) for session {}", created.size(), sessionId);
        return created;
    }

    private Context buildContext(UUID sessionId, String notes) {
        var session = sessions.get(sessionId);
        var detail = sessions.detail(sessionId);
        var allFindings = findings.listBySession(sessionId, null, null);

        var context = new Context();
        context.setVariable("session", session);
        context.setVariable("notes", notes);
        context.setVariable("steps", detail.steps());
        context.setVariable("milestones", detail.milestones());
        context.setVariable("snapshots", snapshots.listBySession(sessionId));
        context.setVariable("comparisons", comparisons.listBySession(sessionId));
        context.setVariable("findings", allFindings);
        context.setVariable("findingCounts", findings.countsBySeverity(sessionId));
        context.setVariable("eventCount", events.countBySession(sessionId));
        context.setVariable("artifactCount", detail.counts().artifacts());
        context.setVariable("generatedAt", java.time.Instant.now().toString());

        // Severity order for the findings table -- worst first, because a report nobody
        // reads past the first screen must lead with the thing that matters.
        context.setVariable("severityOrder", List.of("critical", "high", "medium", "low", "info"));
        return context;
    }

    private UUID store(UUID sessionId, UUID stepId, UUID runId, String name, ArtifactFormat format,
            String body) {
        return artifacts.persist(sessionId, stepId, null, runId, "final_report", "copilot", name,
                format, body, null, null, Map.of("generated_at", java.time.Instant.now().toString()))
                .artifactId();
    }
}
