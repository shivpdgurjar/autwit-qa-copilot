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

    @Override
    public TestPlanResult generateTestPlan(TestPlanRequest request) {
        var key = request.featureKey() == null || request.featureKey().isBlank()
                ? "PAY-2481" : request.featureKey().trim();
        var scenarios = List.of(
                new Scenario("TC-01", "Retry succeeds within max attempts", "High", key),
                new Scenario("TC-02", "Retry exhausts max attempts and marks charge failed", "High", key),
                new Scenario("TC-03", "Idempotency key prevents duplicate charge on retry", "High", "Design doc v3"),
                new Scenario("TC-04", "Retry respects exponential backoff window", "Medium", "RFC-014"),
                new Scenario("TC-05", "Webhook timeout triggers manual reconciliation", "Medium", key));
        var provenance = new LinkedHashMap<String, Object>();
        provenance.put("sources", sourceRefs(request));
        provenance.put("doc_version", "v3");
        return new TestPlanResult(
                "Validates automatic retry behavior for failed payment charge attempts, including "
                        + "exponential backoff timing, idempotency guarantees, and fallback to manual "
                        + "reconciliation when retries are exhausted.",
                "Covers the retry orchestrator, idempotency key store, and webhook timeout handler "
                        + "described in the linked design doc. Excludes upstream fraud-check logic.",
                scenarios, provenance, "resp-fake-plan-" + key);
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
                int seed = (Math.abs(s.id().hashCode()) + i * 7);
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
