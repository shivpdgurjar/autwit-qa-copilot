package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Drives the whole planning wizard under the {@code fake} profile — the same role
 * {@link com.autwit.copilot.analysis.FakeFinancialAnalysisClient} plays for financial
 * analysis. Returns small but realistic canned data (shaped like the Payment-Retry wireframe)
 * so the UI and the tests exercise assemble → fetch → generate → persist without a live
 * orchestrator, an MCP server, or an OpenAI key.
 *
 * <p>Everything here is <b>deterministic</b> — derived from the inputs, never random — so a
 * test can assert exact rows and a re-run is stable.
 */
@Component
@Profile("fake")
public class FakePlanningClient implements PlanningClient {

    @Override
    public List<Candidate> jiraSearch(String featureKey, String query, String project, Integer maxResults) {
        var key = featureKey == null || featureKey.isBlank() ? "PAY-2481" : featureKey.trim();
        var candidates = new ArrayList<Candidate>();
        candidates.add(new Candidate(key, "%s — automatic backoff & idempotency".formatted(cap(query)),
                "jira", "In Progress", "Epic · In Progress · updated 2d ago", "https://jira.internal/browse/" + key));
        candidates.add(new Candidate(bump(key, 21), "Webhook reconciliation on retry timeout",
                "jira", "To Do", "Story · To Do · updated 5d ago", "https://jira.internal/browse/" + bump(key, 21)));
        candidates.add(new Candidate(bump(key, -91), "Idempotency key storage — TTL cleanup",
                "jira", "Done", "Sub-task · Done · updated 3w ago", "https://jira.internal/browse/" + bump(key, -91)));
        return limit(candidates, maxResults);
    }

    @Override
    public List<Candidate> confluenceSearch(String space, String query, Integer maxResults) {
        var sp = space == null || space.isBlank() ? "PAY" : space.trim();
        var candidates = List.of(
                new Candidate(sp + "-DESIGN", "%s — Design Doc v3".formatted(cap(query)),
                        "confluence", null, "Edited by R. Kessler · 4d ago", "https://confluence.internal/" + sp + "/design"),
                new Candidate(sp + "-RFC-014", "RFC-014: Exponential backoff strategy",
                        "confluence", null, "Edited by A. Chen · 2mo ago", "https://confluence.internal/" + sp + "/rfc-014"));
        return limit(new ArrayList<>(candidates), maxResults);
    }

    @Override
    public FetchResult fetchContext(List<String> jiraKeys, List<String> confluencePageIds) {
        var docs = new ArrayList<FetchedDocument>();
        var log = new ArrayList<LogLine>();
        int t = 1;
        for (var key : jiraKeys == null ? List.<String>of() : jiraKeys) {
            docs.add(new FetchedDocument("jira", key,
                    key + " — Payment retry logic",
                    "As a payment service, retry a failed charge with exponential backoff and an "
                            + "idempotency key so a retry never double-charges. Give up after N attempts "
                            + "and fall back to manual reconciliation.",
                    Instant.EPOCH));
            log.add(new LogLine(stamp(t++), "ok", "jira", key, "Fetched " + key + " — Payment retry logic"));
        }
        for (var pageId : confluencePageIds == null ? List.<String>of() : confluencePageIds) {
            docs.add(new FetchedDocument("confluence", pageId,
                    "Payment Retry — Design Doc v3",
                    "Design: the retry orchestrator, the idempotency key store (TTL 24h), and the "
                            + "webhook timeout handler. Backoff is 200ms · 2^attempt, capped at 8s.",
                    Instant.EPOCH));
            log.add(new LogLine(stamp(t++), "ok", "confluence", pageId, "Fetched page Payment Retry — Design Doc v3"));
        }
        log.add(new LogLine(stamp(t), "info", null, null,
                "Extracted " + docs.size() + " document(s) from combined context"));
        return new FetchResult(docs, log);
    }

    /**
     * The last request this fake was handed. Only meaningful under the {@code fake} profile;
     * it exists so a test can assert on what the runner ASSEMBLED (doc-role partitioning, the
     * domain key) rather than only on what came back, which the fake makes up anyway.
     */
    private volatile TestPlanRequest lastTestPlanRequest;

    public TestPlanRequest lastTestPlanRequest() {
        return lastTestPlanRequest;
    }

    @Override
    public TestPlanResult generateTestPlan(TestPlanRequest request) {
        this.lastTestPlanRequest = request;
        var key = request.featureKey() == null || request.featureKey().isBlank()
                ? "PAY-2481" : request.featureKey().trim();

        // Grouped by capability, with the detail a tester needs to execute a case — the
        // offline profile is the only path that exercises the whole stack, so a shallow fake
        // here would hide exactly the regression this upgrade is about.
        var capabilities = List.of(
                new Capability("Retry execution", "When a failed charge is retried, and what happens.",
                        List.of(
                                fakeCase("TC-01", "Retry succeeds within max attempts", "High",
                                        "Proves a transient failure recovers without tester intervention.",
                                        "Payment authorisation", List.of(key), List.of("REQ-01"),
                                        List.of("A charge that failed with a transient gateway error"),
                                        List.of("Trigger the retry job"),
                                        List.of("The charge status is CAPTURED",
                                                "Exactly one successful charge exists"),
                                        List.of("DATA-01"),
                                        Map.of("type", "api", "target", "POST /v1/charges/{id}/retry",
                                                "notes", "Covered by the AUTWIT payment facade")),
                                fakeCase("TC-02", "Retry exhausts max attempts and marks the charge failed",
                                        "High", "Proves the retry budget is bounded and terminal.",
                                        "Payment authorisation", List.of(key), List.of("REQ-01", "REQ-02"),
                                        List.of("A charge failing on every attempt"),
                                        List.of("Run the retry job until the budget is spent"),
                                        List.of("The charge status is FAILED",
                                                "No further retry is scheduled"),
                                        List.of("DATA-01"), null))),
                new Capability("Idempotency", "Retries must never double-charge a member.",
                        List.of(
                                fakeCase("TC-03", "Idempotency key prevents a duplicate charge on retry",
                                        "High", "Proves a replayed retry is absorbed, not re-executed.",
                                        "Payment authorisation", List.of("Design doc v3"), List.of("REQ-03"),
                                        List.of("A charge already captured under idempotency key K"),
                                        List.of("Replay the retry with the same key K"),
                                        List.of("The member is charged exactly once",
                                                "The response echoes the original charge id"),
                                        List.of("DATA-02"), null))),
                new Capability("Backoff and escalation", "Timing, and what happens when retries run out.",
                        List.of(
                                fakeCase("TC-04", "Retry respects the exponential backoff window", "Medium",
                                        "Proves retries are paced rather than hammering the gateway.",
                                        null, List.of("RFC-014"), List.of("REQ-04"),
                                        List.of("A charge with two prior failed attempts"),
                                        List.of("Observe the scheduled time of the third attempt"),
                                        List.of("The third attempt is scheduled at or after the "
                                                + "documented backoff interval"),
                                        List.of(), null),
                                fakeCase("TC-05", "Webhook timeout triggers manual reconciliation", "Medium",
                                        "Proves an ambiguous outcome escalates instead of silently retrying.",
                                        null, List.of(key), List.of("REQ-05"),
                                        List.of("A charge whose gateway webhook never arrives"),
                                        List.of("Wait past the webhook timeout"),
                                        List.of("The charge is queued for manual reconciliation",
                                                "No automatic retry is issued"),
                                        List.of(), null))));

        var provenance = new LinkedHashMap<String, Object>();
        provenance.put("sources", sourceRefs(request));
        provenance.put("doc_version", "v3");

        var scope = new TestPlan.Scope(
                List.of("Retry orchestrator", "Idempotency key store", "Webhook timeout handling"),
                List.of("Upstream fraud-check logic"));

        var architecture = Map.<String, Object>of(
                "summary", "Retry is an overlay on the existing payment authorisation flow.",
                "participants", List.of(
                        Map.of("name", "Retry orchestrator", "role", "schedules and bounds attempts"),
                        Map.of("name", "Payment gateway", "role", "executes the charge")),
                "lifecycle_phases", List.of(
                        Map.of("name", "Order capture", "description", "Order created"),
                        Map.of("name", "Payment authorisation", "description", "Charge attempted")),
                "feature_injection_points", List.of(Map.of(
                        "phase", "Payment authorisation",
                        "description", "Retry engages after a failed charge",
                        "sources", List.of(key))));

        var requirements = List.of(
                new TestPlan.Requirement("REQ-01", "A transiently failed charge is retried.",
                        "functional", List.of(key), "Failed charges are retried automatically.",
                        "Payment authorisation"),
                new TestPlan.Requirement("REQ-02", "Retries stop after the configured maximum.",
                        "validation", List.of(key), null, "Payment authorisation"),
                new TestPlan.Requirement("REQ-03", "A retry never produces a second charge.",
                        "data", List.of("Design doc v3"), null, null),
                new TestPlan.Requirement("REQ-04", "Retries follow an exponential backoff.",
                        "functional", List.of("RFC-014"), null, null),
                new TestPlan.Requirement("REQ-05", "An absent webhook escalates to manual reconciliation.",
                        "integration", List.of(key), null, null));

        var dataRequirements = List.of(
                new TestPlan.TestDataRequirement("DATA-01", "Failing charge",
                        "A charge the gateway rejects on the first attempt",
                        List.of("credit-card payment", "gateway returns a transient error"), null),
                new TestPlan.TestDataRequirement("DATA-02", "Captured charge with an idempotency key",
                        "An already-captured charge to replay against",
                        List.of("known idempotency key"), null));

        var result = new TestPlanResult(
                "Validates automatic retry behaviour for failed payment charge attempts, including "
                        + "exponential backoff timing, idempotency guarantees, and fallback to manual "
                        + "reconciliation when retries are exhausted.",
                scope, architecture, requirements, dataRequirements, capabilities,
                "Run retry execution first; idempotency and backoff both assume a charge that has "
                        + "already failed once.",
                List.of(Map.of("title", "Gateway sandbox flakiness",
                        "detail", "The sandbox intermittently returns 502.",
                        "mitigation", "Assert on the charge record, not the gateway response.")),
                List.of(Map.of("title", "Backoff ceiling not stated",
                        "detail", "No document gives the maximum backoff interval.",
                        "blocks_testing", false)),
                provenance, Map.of(), "resp-fake-plan-" + key);
        // payload mirrors what the real client keeps verbatim; the fake has no wire body, so
        // it is left empty and the typed fields above are the source of truth offline.
        return result;
    }

    private static Scenario fakeCase(String id, String title, String priority, String objective,
            String lifecyclePhase, List<String> sources, List<String> requirementIds,
            List<String> preconditions, List<String> steps, List<String> expectedResults,
            List<String> testDataRequirements, Map<String, Object> automationMapping) {
        return new Scenario(id, title, priority, objective, lifecyclePhase, sources, requirementIds,
                preconditions, steps, expectedResults, testDataRequirements, automationMapping);
    }

    @Override
    public TestDataResult generateTestData(TestDataRequest request) {
        var columns = List.of("transaction_id", "customer_id", "amount", "currency",
                "attempts", "backoff", "expected_status");
        var datasets = new ArrayList<Dataset>();
        for (var s : request.scenarios()) {
            int n = Math.max(1, request.rowsPerScenario());
            var rows = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < n; i++) {
                var row = new LinkedHashMap<String, Object>();
                // Deterministic, seeded by the scenario id + row index — no randomness.
                // floorMod + bound keeps seed small and positive so the products below never
                // overflow int (which produced negative amounts/backoff before).
                int seed = Math.floorMod(s.id().hashCode() + i * 7, 100_000);
                row.put("transaction_id", "txn_" + (1000 + seed % 9000));
                row.put("customer_id", "cus_" + (1000 + (seed * 3) % 9000));
                row.put("amount", String.format("%.2f", (199 + (seed * 13) % 49800) / 100.0));
                row.put("currency", new String[] {"USD", "EUR", "GBP"}[seed % 3]);
                row.put("attempts", 1 + seed % 5);
                row.put("backoff", (200 + (seed * 37) % 7800) + "ms");
                // TC-02 is the "exhausts attempts" scenario → mostly failed; others mostly succeed.
                boolean fail = s.id().endsWith("02") ? i % 3 != 2 : i % 7 == 0;
                row.put("expected_status", fail ? "failed" : "succeeded");
                rows.add(row);
            }
            datasets.add(new Dataset(s.id(), columns, rows));
        }
        return new TestDataResult(datasets, "resp-fake-data-" + request.scenarios().size());
    }

    @Override
    public AnalyzeResult analyzeDocuments(AnalyzeRequest request) {
        // The tester's answers carried forward. A finding whose title is already resolved is
        // NOT re-raised — so a second round after both are answered returns clean, driving the
        // whole reasoning loop deterministically for the demo and the tests.
        var resolved = new java.util.HashSet<String>();
        for (var r : request.resolutions() == null ? List.<ResolutionRef>of() : request.resolutions()) {
            if (r.point() != null) {
                resolved.add(r.point().trim().toLowerCase());
            }
        }

        var conflicts = new ArrayList<Finding>();
        var conflictTitle = "Retry attempt limit disagreement";
        if (!resolved.contains(conflictTitle.toLowerCase())) {
            conflicts.add(new Finding(conflictTitle,
                    "The ticket and the design doc give different maximum retry counts, so the "
                            + "'exhausts attempts' scenario cannot be pinned down.",
                    List.of(new Source("PAY-2481 — Payment retry logic", "give up after N attempts"),
                            new Source("Payment Retry — Design Doc v3", "retries, capped at 8s")),
                    List.of("3 attempts", "5 attempts")));
        }

        var clarifications = new ArrayList<Finding>();
        var clarifyTitle = "Idempotency key TTL vs. retry window";
        if (!resolved.contains(clarifyTitle.toLowerCase())) {
            clarifications.add(new Finding(clarifyTitle,
                    "The retry window can outlast the stated 24h key TTL; behaviour after the key "
                            + "expires mid-retry is undefined and the plan needs it.",
                    List.of(new Source("Payment Retry — Design Doc v3", "idempotency key store (TTL 24h)")),
                    List.of()));
        }

        int answered = resolved.size();
        return new AnalyzeResult(conflicts, clarifications, "resp-fake-analysis-" + answered);
    }

    // ---- helpers ---------------------------------------------------------------------

    private static List<String> sourceRefs(TestPlanRequest request) {
        var refs = new ArrayList<String>();
        if (request.featureKey() != null && !request.featureKey().isBlank()) {
            refs.add(request.featureKey().trim());
        }
        for (var d : request.sourceDocuments() == null ? List.<Doc>of() : request.sourceDocuments()) {
            refs.add(d.title());
        }
        return refs;
    }

    private static List<Candidate> limit(List<Candidate> in, Integer max) {
        return max == null || max <= 0 || max >= in.size() ? in : in.subList(0, max);
    }

    /** Bumps the numeric suffix of a key like PAY-2481 by delta, leaving the prefix intact. */
    private static String bump(String key, int delta) {
        int dash = key.lastIndexOf('-');
        if (dash < 0) {
            return key;
        }
        try {
            return key.substring(0, dash + 1) + (Integer.parseInt(key.substring(dash + 1)) + delta);
        } catch (NumberFormatException e) {
            return key;
        }
    }

    private static String cap(String s) {
        if (s == null || s.isBlank()) {
            return "Payment retry logic";
        }
        var t = s.trim();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static String stamp(int i) {
        return "00:0%d.%d".formatted(i / 10, i % 10);
    }
}
