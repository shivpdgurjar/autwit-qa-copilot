package com.autwit.copilot.orchestrator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.autwit.copilot.config.AutwitProperties;
import com.autwit.copilot.orchestrator.dto.Envelope;
import com.autwit.copilot.orchestrator.dto.InvokeRequest;
import com.autwit.copilot.orchestrator.dto.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * SKILL_CONTRACT §10. Replays the fixtures, which are "the contract's executable
 * form — if the real orchestrator diverges from them, one of the two sides is wrong
 * and the fixtures are the tiebreaker."
 *
 * <p>This is what makes BUILD_BRIEF steps 1–7 buildable before the orchestrator
 * session ships anything. Only step 8 swaps it out.
 *
 * <p>Fixtures live in main/resources rather than test/resources as BUILD_BRIEF §4
 * suggests. Step 5's done-when is "fixture session renders end-to-end", which needs
 * the API actually running under this profile — and test resources are not on the
 * runtime classpath. main/resources is also visible to tests, so nothing is lost.
 */
@Component
@Profile("fake")
public class FakeOrchestratorClient implements OrchestratorClient {

    private static final Logger log = LoggerFactory.getLogger(FakeOrchestratorClient.class);

    private static final String DIR = "fixtures/orchestrator/";

    /**
     * Message → fixture. Ordered: first match wins, so put the specific before the
     * general ("fulfilled" would otherwise swallow "ready for member" cases).
     */
    private static final Map<String, String> ROUTES = new LinkedHashMap<>();

    static {
        ROUTES.put("dedupe", "invoke_events_dedupe.json");
        ROUTES.put("slow", "invoke_slow.json");
        ROUTES.put("fail", "invoke_failed.json");
        ROUTES.put("partial", "invoke_partial.json");
        ROUTES.put("ready for member", "invoke_ready_for_member.json");
        ROUTES.put("fulfil", "invoke_fulfilled.json");
        ROUTES.put("created order", "invoke_order_created.json");
    }

    private static final String DEFAULT_FIXTURE = "invoke_order_created.json";

    private final ObjectMapper mapper;
    private final AutwitProperties props;
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public FakeOrchestratorClient(ObjectMapper mapper, AutwitProperties props) {
        this.mapper = mapper;
        this.props = props;
        log.warn("FakeOrchestratorClient active — replaying fixtures from {}. "
                + "This profile must never be enabled against a real environment.", DIR);
    }

    @Override
    public Envelope invoke(InvokeRequest.Invoke request) {
        return replay(fixtureFor(request.message()), request.runId());
    }

    @Override
    public Envelope execute(String skillName, InvokeRequest.Execute request) {
        return replay(fixtureFor(skillName), request.runId());
    }

    @Override
    public Catalog skills() {
        var doc = load("skills_catalog.json");
        var skills = mapper.convertValue(doc.get("skills"), mapper.getTypeFactory()
                .constructCollectionType(List.class, Skill.class));
        return new Catalog((String) doc.get("catalog_version"), (List<Skill>) skills);
    }

    /** Exposed so tests can drive a specific fixture without wording a message just so. */
    public Envelope replayFixture(String fixture, String runId) {
        return replay(fixture, runId);
    }

    private Envelope replay(String fixture, String runId) {
        var doc = load(fixture);
        simulate(doc, fixture);

        var envelope = mapper.convertValue(doc, Envelope.class);
        // The fixture cannot know the run_id; the real orchestrator echoes ours back.
        return new Envelope(runId, envelope.status(), envelope.startedAt(), envelope.endedAt(),
                envelope.durationMs(), envelope.invocations(), envelope.artifacts(), envelope.snapshots(),
                envelope.events(), envelope.findings(), envelope.notes(), envelope.subjectsDiscovered(),
                envelope.cursorsAdvanced(), envelope.milestone(), envelope.error());
    }

    /**
     * Honours the fixture's {@code _simulate} block.
     *
     * <p>The slow path sleeps for the configured timeout rather than the fixture's
     * sleep_ms and then raises Timeout. Sleeping the fixture's real 10 minutes would
     * make TimeoutTest untestable; sleeping the timeout means a test can set
     * autwit.orchestrator.timeout to ~1s and still exercise the worker's genuine
     * timed_out path rather than a mocked one.
     */
    @SuppressWarnings("unchecked")
    private void simulate(Map<String, Object> doc, String fixture) {
        var sim = (Map<String, Object>) doc.get("_simulate");
        if (sim == null) {
            return;
        }

        if (sim.get("sleep_ms") instanceof Number sleepMs) {
            var timeout = props.orchestrator().timeout();
            long napMs = Math.min(sleepMs.longValue(), timeout.toMillis());
            log.info("Fixture {} simulates a slow call: sleeping {}ms against a {} timeout",
                    fixture, napMs, timeout);
            try {
                Thread.sleep(napMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OrchestratorException.Timeout("Interrupted while simulating a slow call", e);
            }
            throw new OrchestratorException.Timeout(
                    "Orchestrator did not respond within %s (simulated by %s)".formatted(timeout, fixture), null);
        }

        if (Boolean.TRUE.equals(sim.get("error"))) {
            var problem = mapper.convertValue(doc, Problem.class);
            throw new OrchestratorException.Failed(
                    "Orchestrator returned %s: %s".formatted(problem.code(), problem.detail()), problem, null);
        }
    }

    private static String fixtureFor(String message) {
        if (message == null) {
            return DEFAULT_FIXTURE;
        }
        var lower = message.toLowerCase();
        return ROUTES.entrySet().stream()
                .filter(e -> lower.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(DEFAULT_FIXTURE);
    }

    private Map<String, Object> load(String fixture) {
        return cache.computeIfAbsent(fixture, name -> {
            var resource = new ClassPathResource(DIR + name);
            if (!resource.exists()) {
                throw new IllegalStateException(
                        "Fixture %s%s is missing. SKILL_CONTRACT §10 lists the set the fake must cover."
                                .formatted(DIR, name));
            }
            try (var in = resource.getInputStream()) {
                return mapper.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read fixture " + name, e);
            }
        });
    }
}
