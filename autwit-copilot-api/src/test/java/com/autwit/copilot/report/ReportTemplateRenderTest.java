package com.autwit.copilot.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.autwit.copilot.compare.Comparison;
import com.autwit.copilot.compare.Finding;
import com.autwit.copilot.compare.PartResult;
import com.autwit.copilot.session.Milestone;
import com.autwit.copilot.session.Session;
import com.autwit.copilot.session.Step;
import com.autwit.copilot.snapshot.Snapshot;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the two report templates directly, with no database and no Spring context,
 * so a template expression typo fails fast in a plain unit test rather than only at
 * {@code /end} time. It mirrors {@link com.autwit.copilot.report.ReportTemplateConfig}
 * (a TEXT resolver scoped to {@code *-md} ahead of the HTML one) and uses the Spring
 * template engine so evaluation is SpEL, exactly as in production.
 *
 * <p>The full end-to-end path — real snapshots, a real diff, the artifact round-trip —
 * is covered by {@code CanonicalSessionTest}; this locks in the redesign's structure.
 */
class ReportTemplateRenderTest {

    private static final UUID CID = UUID.randomUUID();
    private static final UUID SID = UUID.randomUUID();
    private static final Instant T = Instant.parse("2026-08-14T12:00:00Z");

    private static SpringTemplateEngine engine() {
        var html = new ClassLoaderTemplateResolver();
        html.setPrefix("templates/");
        html.setSuffix(".html");
        html.setTemplateMode(TemplateMode.HTML);
        html.setCharacterEncoding("UTF-8");
        html.setOrder(1);

        var md = new ClassLoaderTemplateResolver();
        md.setPrefix("templates/");
        md.setSuffix(".txt");
        md.setTemplateMode(TemplateMode.TEXT);
        md.setCharacterEncoding("UTF-8");
        md.setResolvablePatterns(Set.of("*-md"));
        md.setOrder(0);

        var engine = new SpringTemplateEngine();
        engine.addTemplateResolver(md);
        engine.addTemplateResolver(html);
        return engine;
    }

    /** A session with one comparison that carries both a headline finding and a field diff. */
    private static Context context() {
        var financial = new Finding(UUID.randomUUID(), SID, CID, null, "critical", "financial",
                "oms.orders", "order-1", "order_total_equals_line_items", "1200.00", "1450.00",
                // A multi-line explanation renders as bullets, one point per line.
                "- Order total is 1450.00\n- Sum of line items is 1200.00\n- Discrepancy of 250.00 (rewards excluded)", T);
        var changed = new Finding(UUID.randomUUID(), SID, CID, null, "info", "changed",
                "oms.orders", "order-1", "status", "NEW", "FULFILLED",
                "oms.orders.status changed from NEW to FULFILLED for order-1.", T);
        var findings = List.of(financial, changed);

        var part = PartResult.of("oms.orders", 0, 0, 1, 2, List.of("updated_at"));
        var comparison = new Comparison(CID, SID, null, null, UUID.randomUUID(), UUID.randomUUID(),
                "financial_validation", Map.of(), "fail", "1 of 3 parts changed; 1 finding; 1 ignored column",
                null, List.of(part), T, List.of(), Map.of());

        var snapshot = new Snapshot(UUID.randomUUID(), SID, null, null, "order_created", "oms",
                Map.of(), "complete", T, "abc123",
                List.of(new Snapshot.Part("oms.orders", UUID.randomUUID(), 3, "deadbeefcafebabe0011")));

        var milestone = new Milestone(UUID.randomUUID(), SID, null, "order_created", 1, "complete",
                T, snapshot.snapshotId(), Map.of("producerTime", "2026-08-14T12:00:00Z"), "looks good");

        var okStep = new Step(UUID.randomUUID(), SID, 1, "user_utterance", "I created order 33AT",
                "user", "succeeded", T, T, null, Map.of(), null);
        var failStep = new Step(UUID.randomUUID(), SID, 2, "skill_invocation", "compare snapshots",
                "copilot", "failed", T, T, null, Map.of(), null);

        var session = new Session(SID, "autwit-qa2-abc123", "priya", "qa2", "Order flow check",
                "ended", "standard", T, T, null, Map.of(), Map.of("order_id", "33AT0804110748"));

        var ctx = new Context();
        ctx.setVariable("session", session);
        ctx.setVariable("notes", "Checked the order flow end to end.");
        ctx.setVariable("steps", List.of(okStep, failStep));
        ctx.setVariable("milestones", List.of(milestone));
        ctx.setVariable("snapshots", List.of(snapshot));
        ctx.setVariable("comparisons", List.of(comparison));
        ctx.setVariable("findings", findings);
        ctx.setVariable("findingsByComparison", Map.of(CID, findings));
        ctx.setVariable("findingCounts", Map.of("critical", 1, "info", 1));
        ctx.setVariable("eventCount", 14L);
        ctx.setVariable("artifactCount", 6);
        ctx.setVariable("generatedAt", T.toString());
        ctx.setVariable("fmt", new ReportRenderer.Format());
        ctx.setVariable("severityOrder", List.of("critical", "high", "medium", "low", "info"));
        return ctx;
    }

    /** Set -Dreport.dump.dir=&lt;dir&gt; to eyeball the rendered report; no-op in normal runs. */
    private static void dump(String name, String body) {
        var dir = System.getProperty("report.dump.dir");
        if (dir == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(dir, name), body);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void htmlReportRendersTheRedesignedSections() {
        var html = engine().process("report", context());
        dump("sample-report.html", html);
        dump("sample-report.md", engine().process("report-md", context()));

        assertThat(html)
                .contains("<!DOCTYPE html>")
                .contains("href=\"#findings\"")          // table of contents
                .contains("id=\"snapshots\"")            // new snapshots section
                .contains("<th>Field</th>")              // findings now carry the column
                .contains("order_total_equals_line_items")
                .contains("1200.00").contains("1450.00") // before -> after rendered, not buried in prose
                .contains("Field-level detail")          // per-comparison drill-down
                .contains("updated_at")                  // ignored columns still surfaced
                .contains("critical")                    // severity breakdown chip / table
                .contains("row-fail")                    // the failed timeline step is marked
                .contains("<ul class=\"msg\">")          // messages render as bullets
                .contains("Sum of line items is 1200.00"); // one bullet per explanation line
    }

    @Test
    void markdownReportMirrorsTheHtml() {
        var md = engine().process("report-md", context());

        assertThat(md)
                .contains("# AutWit session report")
                .contains("## Snapshots")
                .contains("| Field |")                   // findings table gained the column
                .contains("| **critical** |")            // severity still bolded in the table
                .contains("1200.00").contains("1450.00") // before -> after in markdown too
                .contains("Field-level detail")
                .contains("• Sum of line items is 1200.00"); // multi-point message inlined as bullets
    }
}
