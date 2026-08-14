package com.autwit.copilot.report;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.ArtifactFormat;
import com.autwit.copilot.artifact.ArtifactService;
import com.autwit.copilot.compare.ComparisonRepository;
import com.autwit.copilot.compare.Finding;
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

        // Group findings under their comparison so each comparison can drill down to the
        // concrete field-level diffs (entity, column, before -> after) that its counts
        // summarise away. Findings with no comparisonId are session-level and stay out.
        var byComparison = new java.util.LinkedHashMap<UUID, List<Finding>>();
        for (var f : allFindings) {
            if (f.comparisonId() != null) {
                byComparison.computeIfAbsent(f.comparisonId(), k -> new java.util.ArrayList<>()).add(f);
            }
        }

        var context = new Context();
        context.setVariable("session", session);
        context.setVariable("notes", notes);
        context.setVariable("steps", detail.steps());
        context.setVariable("milestones", detail.milestones());
        context.setVariable("snapshots", snapshots.listBySession(sessionId));
        context.setVariable("comparisons", comparisons.listBySession(sessionId));
        context.setVariable("findings", allFindings);
        context.setVariable("findingsByComparison", byComparison);
        context.setVariable("findingCounts", findings.countsBySeverity(sessionId));
        context.setVariable("eventCount", events.countBySession(sessionId));
        context.setVariable("artifactCount", detail.counts().artifacts());
        context.setVariable("generatedAt", java.time.Instant.now().toString());
        context.setVariable("fmt", FORMAT);

        // Severity order for the findings table -- worst first, because a report nobody
        // reads past the first screen must lead with the thing that matters.
        context.setVariable("severityOrder", List.of("critical", "high", "medium", "low", "info"));
        return context;
    }

    private static final Format FORMAT = new Format();

    /**
     * Template-side formatting helpers. Kept null-safe and lenient on type: timestamps
     * arrive as Instant or as an ISO String depending on the source, and a report must
     * never blow up on a missing value -- it renders an em dash instead.
     */
    public static final class Format {
        private static final java.time.format.DateTimeFormatter HUMAN =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                        .withZone(java.time.ZoneOffset.UTC);

        /** An Instant or ISO-8601 string rendered as a readable UTC timestamp. */
        public String time(Object v) {
            if (v == null) {
                return "—";
            }
            try {
                var instant = (v instanceof java.time.Instant i) ? i : java.time.Instant.parse(String.valueOf(v));
                return HUMAN.format(instant);
            } catch (RuntimeException e) {
                return String.valueOf(v);
            }
        }

        /** Any value for a table cell; null becomes an em dash, never the string "null". */
        public String value(Object v) {
            return v == null ? "—" : String.valueOf(v);
        }

        /**
         * A finding message split into bullet points. The analyser is asked to write each
         * explanation as one claim per line; deterministic findings are a single line and
         * become a single bullet. Any leading list marker is stripped so the template owns
         * the bullet, not the text.
         */
        public List<String> bullets(String message) {
            if (message == null || message.isBlank()) {
                return List.of();
            }
            var out = new java.util.ArrayList<String>();
            for (var raw : message.split("\\R")) {
                var line = raw.strip().replaceFirst("^[-*•]\\s*", "").strip();
                if (!line.isEmpty()) {
                    out.add(line);
                }
            }
            return out.isEmpty() ? List.of(message.strip()) : out;
        }

        /** The same points on one line for a Markdown table cell, which cannot hold a list. */
        public String inlineBullets(String message) {
            var points = bullets(message);
            if (points.isEmpty()) {
                return "—";
            }
            return points.size() == 1 ? points.get(0) : "• " + String.join(" • ", points);
        }
    }

    private UUID store(UUID sessionId, UUID stepId, UUID runId, String name, ArtifactFormat format,
            String body) {
        return artifacts.persist(sessionId, stepId, null, runId, "final_report", "copilot", name,
                format, body, null, null, Map.of("generated_at", java.time.Instant.now().toString()))
                .artifactId();
    }
}
