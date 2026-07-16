package com.autwit.copilot.run;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.ArtifactFormat;
import com.autwit.copilot.artifact.ArtifactService;
import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.compare.FindingRepository;
import com.autwit.copilot.compare.Severity;
import com.autwit.copilot.events.EventIngestService;
import com.autwit.copilot.orchestrator.dto.ArtifactDescriptor;
import com.autwit.copilot.orchestrator.dto.Envelope;
import com.autwit.copilot.session.MilestoneRepository;
import com.autwit.copilot.session.SessionRepository;
import com.autwit.copilot.session.StepRepository;
import com.autwit.copilot.snapshot.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a result envelope, in one transaction, exactly as BUILD_BRIEF §6 lays out.
 *
 * <p>All of it lands or none of it does. A snapshot whose parts committed but whose
 * events did not would be indistinguishable from a real gap in the event stream —
 * which is precisely the bug class this product exists to find.
 */
@Service
public class EnvelopePersister {

    private static final Logger log = LoggerFactory.getLogger(EnvelopePersister.class);

    private final ArtifactService artifacts;
    private final SnapshotRepository snapshots;
    private final EventIngestService events;
    private final FindingRepository findings;
    private final StepRepository steps;
    private final MilestoneRepository milestones;
    private final SessionRepository sessions;
    private final RunRepository runs;

    public EnvelopePersister(ArtifactService artifacts, SnapshotRepository snapshots, EventIngestService events,
            FindingRepository findings, StepRepository steps, MilestoneRepository milestones,
            SessionRepository sessions, RunRepository runs) {
        this.artifacts = artifacts;
        this.snapshots = snapshots;
        this.events = events;
        this.findings = findings;
        this.steps = steps;
        this.milestones = milestones;
        this.sessions = sessions;
        this.runs = runs;
    }

    /**
     * @return empty when the run went terminal while we were working — cancelled, or
     *         reaped — in which case nothing is persisted. BUILD_BRIEF §6: "On late
     *         result: if a run went terminal (cancelled/timed_out) and the
     *         orchestrator's response arrives afterwards, discard it. Do not persist."
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result persist(Run run, Envelope envelope, UUID milestoneId) {

        var summary = new LinkedHashMap<String, Object>();

        // client_ref -> the artifact_id we assign. §6.1: the ref is a within-response
        // handle so parts can point at artifacts before real UUIDs exist.
        Map<String, UUID> byRef = new HashMap<>();

        for (var descriptor : envelope.artifactsOrEmpty()) {
            byRef.put(descriptor.clientRef(), persistArtifact(run, descriptor, milestoneId));
        }
        summary.put("artifacts", byRef.size());

        UUID snapshotId = null;
        for (var descriptor : envelope.snapshotsOrEmpty()) {
            snapshotId = persistSnapshot(run, descriptor, milestoneId, byRef);
        }
        if (snapshotId != null) {
            summary.put("snapshot_id", snapshotId.toString());
        }

        // Events are attributed to the event_batch artifact when the orchestrator sent
        // one, so the raw batch stays auditable alongside the parsed rows (§6.3).
        var batchArtifactId = byRef.entrySet().stream()
                .filter(e -> isEventBatch(envelope, e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);

        int newEvents = events.ingest(run.sessionId(), batchArtifactId, milestoneId, envelope.eventsOrEmpty());
        summary.put("events_new", newEvents);
        summary.put("events_returned", envelope.eventsOrEmpty().size());

        for (var f : envelope.findingsOrEmpty()) {
            findings.insert(run.sessionId(), null, run.stepId(), f.severity(), f.category(), f.partKey(),
                    f.entityKey(), f.field(), f.beforeValue(), f.afterValue(), f.message());
        }

        // §5: partial means some artifacts landed and some didn't. Snapshot partial, run
        // succeeded, and a finding -- "Don't silently drop this."
        //
        // §5 calls this a "warn finding", but warn is a Verdict and not a Severity (see
        // compare/Severity). medium is the nearest real value: a partial snapshot is not
        // cosmetic, because comparing against it can manufacture findings that look like
        // product bugs.
        if (envelope.isPartial()) {
            findings.insert(run.sessionId(), null, run.stepId(), Severity.MEDIUM, "capture", null, null, null,
                    null, null,
                    "Orchestrator returned status=partial: the snapshot is incomplete and any comparison "
                            + "against it may report missing rows that are artefacts of the capture, not bugs.");
        }
        summary.put("findings", envelope.findingsOrEmpty().size() + (envelope.isPartial() ? 1 : 0));

        // §5: notes are the running-analysis channel. Persisted as step(kind=analysis),
        // rendered in chat rather than the timeline.
        for (var note : envelope.notesOrEmpty()) {
            steps.insert(run.sessionId(), "analysis", note.text(), "agent", "succeeded", run.stepId(),
                    Map.of("text", note.text(), "at", String.valueOf(note.at())));
        }
        summary.put("notes", envelope.notesOrEmpty().size());

        sessions.mergeSubjects(run.sessionId(), envelope.subjectsDiscoveredOrEmpty());

        if (milestoneId != null) {
            milestones.complete(milestoneId, snapshotId, envelope.cursorsAdvancedOrEmpty(),
                    envelope.isPartial() ? "partial" : "complete");
        }

        // Claim the run LAST. Everything above is wasted work if this returns false, but
        // doing it first would mean a crash mid-persist left a run marked succeeded with
        // nothing behind it. The transaction unwinds either way; this ordering makes the
        // late-result check the final gate rather than an early guess.
        if (!runs.succeed(run.runId(), run.workerId(), summary)) {
            log.info("Run {} went terminal while the orchestrator was working; discarding the result "
                    + "({} artifacts, {} events)", run.runId(), byRef.size(), envelope.eventsOrEmpty().size());
            throw new LateResultException(run.runId());
        }

        steps.updateStatus(run.stepId(), "succeeded");
        return new Result(snapshotId, byRef.size(), newEvents, summary);
    }

    private UUID persistArtifact(Run run, ArtifactDescriptor d, UUID milestoneId) {
        if (d.externalUri() != null) {
            // SKILL_CONTRACT §7 is v0.2. The field exists on the DTO so that adding it
            // later is not a contract renegotiation; accepting it today would mean
            // storing a reference we have no code to resolve.
            throw new ApiException.BadRequest("external_uri_unsupported",
                    "Artifact %s carries external_uri, which is v0.2 (SKILL_CONTRACT §7). v0.1 is inline-only."
                            .formatted(d.clientRef()));
        }

        return artifacts.persist(
                run.sessionId(), run.stepId(), milestoneId, run.runId(),
                d.artifactType(), d.sourceSystem(), d.logicalName(), ArtifactFormat.of(d.format()),
                d.body(), d.contentHash(), d.rowCount(), d.meta()).artifactId();
    }

    private UUID persistSnapshot(Run run, com.autwit.copilot.orchestrator.dto.SnapshotDescriptor d,
            UUID milestoneId, Map<String, UUID> byRef) {

        var snapshotId = snapshots.insert(run.sessionId(), milestoneId, run.stepId(), d.label(), d.scope(),
                d.scopeDef(), d.status() != null ? d.status() : "complete", null);

        for (var part : d.partsOrEmpty()) {
            var artifactId = byRef.get(part.artifactRef());
            if (artifactId == null) {
                // A part pointing at an artifact that is not in this envelope. Not
                // recoverable and not ignorable: the snapshot would be silently short a
                // part, and comparison would later report it as removed -- a phantom bug.
                throw new ApiException.BadRequest("dangling_artifact_ref",
                        "Snapshot part %s references artifact_ref '%s', which is not present in the envelope."
                                .formatted(part.partKey(), part.artifactRef()));
            }
            snapshots.insertPart(snapshotId, part.partKey(), artifactId);
        }
        return snapshotId;
    }

    private static boolean isEventBatch(Envelope envelope, String clientRef) {
        return envelope.artifactsOrEmpty().stream()
                .anyMatch(a -> clientRef.equals(a.clientRef()) && "event_batch".equals(a.artifactType()));
    }

    public record Result(UUID snapshotId, int artifacts, int newEvents, Map<String, Object> summary) {
    }

    /** Unwinds the persist transaction when the run is no longer ours to complete. */
    public static class LateResultException extends RuntimeException {
        public LateResultException(UUID runId) {
            super("Run " + runId + " went terminal before its result arrived; result discarded");
        }
    }
}
