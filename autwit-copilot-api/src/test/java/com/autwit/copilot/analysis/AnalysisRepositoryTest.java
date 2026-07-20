package com.autwit.copilot.analysis;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2 aggregate behaviour — the two properties the design leans on: the payload_hash
 * idempotency guard, and the optimistic-lock session bump.
 */
class AnalysisRepositoryTest extends AbstractPostgresIT {

    @Autowired
    private AnalysisRepository repo;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID sessionId;
    private String analysisId;

    @BeforeEach
    void seed() {
        sessionId = UUID.randomUUID();
        analysisId = "analysis-" + UUID.randomUUID();
        jdbc.update("insert into autwit.session(session_id, correlation_id, tester_id, env) values (?,?,?,?)",
                sessionId, "corr-" + sessionId, "priya", "qa2");
        repo.createSession(new AnalysisSession(analysisId, sessionId, "ord-1", "LIFECYCLE_COMPARISON",
                null, 0, "pv-1", "rv-1", 0, null, null));
    }

    private static StateEnvelope state(int seq, String label, String hashDistinguisher) {
        return new StateEnvelope(seq, label, StateType.ORDER_SNAPSHOT, "stage", SourceSystem.ORDER_DB,
                Instant.parse("2026-07-16T09:14:00Z"), null, Map.of("k", hashDistinguisher));
    }

    @Test
    void aStateAppendsAndIsReadBackInOrder() {
        repo.appendState(analysisId, state(1, "s1", "a"), "sha256:a", "{\"k\":\"a\"}");
        repo.appendState(analysisId, state(2, "s2", "b"), "sha256:b", "{\"k\":\"b\"}");

        assertThat(repo.listStates(analysisId)).extracting(AnalysisRepository.StoredState::sequence)
                .containsExactly(1, 2);
        assertThat(repo.stateCount(analysisId)).isEqualTo(2);
    }

    @Test
    void reSelectingUnchangedEvidenceIsIdempotent() {
        // Same evidence → same §6.1 hash. The second append is a dedupe, not a second row.
        assertThat(repo.appendState(analysisId, state(1, "s1", "a"), "sha256:same", "{\"k\":\"a\"}"))
                .as("first insert wins").isTrue();
        assertThat(repo.appendState(analysisId, state(2, "s2-different-seq-and-label", "a"),
                "sha256:same", "{\"k\":\"a\"}"))
                .as("same payload_hash → dropped").isFalse();
        assertThat(repo.stateCount(analysisId)).isEqualTo(1);
    }

    @Test
    void aRealCollisionOnSequenceIsNotSwallowed() {
        // Different payload, same sequence → an assembly bug, must surface, not dedupe.
        repo.appendState(analysisId, state(1, "s1", "a"), "sha256:a", "{\"k\":\"a\"}");
        assertThatThrownBy(() ->
                repo.appendState(analysisId, state(1, "s2", "b"), "sha256:b", "{\"k\":\"b\"}"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void theSessionBumpIsOptimisticallyLocked() {
        assertThat(repo.bumpSession(analysisId, 0, 3, "resp-1")).as("version 0 matches → wins").isTrue();

        var after = repo.findSession(analysisId).orElseThrow();
        assertThat(after.version()).isEqualTo(1);
        assertThat(after.lastSequence()).isEqualTo(3);
        assertThat(after.latestResponseId()).isEqualTo("resp-1");

        assertThat(repo.bumpSession(analysisId, 0, 9, "resp-stale"))
                .as("stale version 0 → lost race, no write").isFalse();
        assertThat(repo.findSession(analysisId).orElseThrow().lastSequence())
                .as("the stale writer did not clobber").isEqualTo(3);
    }

    @Test
    void deletingTheSessionCascadesToStates() {
        repo.appendState(analysisId, state(1, "s1", "a"), "sha256:a", "{\"k\":\"a\"}");
        jdbc.update("delete from autwit.analysis_session where analysis_id = ?", analysisId);
        assertThat(repo.stateCount(analysisId)).isZero();
    }
}
