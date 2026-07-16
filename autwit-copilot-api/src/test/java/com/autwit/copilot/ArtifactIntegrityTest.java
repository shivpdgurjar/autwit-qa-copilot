package com.autwit.copilot;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.autwit.copilot.artifact.ArtifactFormat;
import com.autwit.copilot.artifact.ArtifactRepository;
import com.autwit.copilot.artifact.ArtifactService;
import com.autwit.copilot.artifact.ContentHasher;
import com.autwit.copilot.artifact.CreateArtifactRequest;
import com.autwit.copilot.common.ApiException;
import com.autwit.copilot.session.CreateSessionRequest;
import com.autwit.copilot.session.SessionService;
import com.autwit.copilot.support.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BUILD_BRIEF §9 ArtifactIntegrityTest:
 * <ul>
 *   <li>content_hash mismatch → rejected
 *   <li>body XOR external_uri enforced (one_body)
 *   <li>purge nulls the body, keeps the row, GET returns 410
 * </ul>
 */
class ArtifactIntegrityTest extends AbstractPostgresIT {

    @Autowired
    private ArtifactService artifacts;
    @Autowired
    private ArtifactRepository repository;
    @Autowired
    private SessionService sessions;
    @Autowired
    private ContentHasher hasher;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID sessionId;

    @BeforeEach
    void newSession() {
        sessionId = sessions.create(new CreateSessionRequest("priya", "qa2", null, null, null, null, null))
                .sessionId();
    }

    private static CreateArtifactRequest request(Object body, String contentHash) {
        return new CreateArtifactRequest(
                "rdbms_table", "oms_pg", "orders", ArtifactFormat.JSON, body,
                1, contentHash, null, null, Map.of("pk_columns", List.of("order_id")));
    }

    private static final Object BODY = List.of(Map.of("order_id", "XXXX", "status", "CREATED"));

    // ---------------------------------------------------------------- content_hash

    @Test
    void aCorrectContentHashIsAccepted() {
        var declared = hasher.hash(ArtifactFormat.JSON, BODY);

        var artifact = artifacts.create(sessionId, request(BODY, declared));

        assertThat(artifact.contentHash()).isEqualTo(declared);
    }

    @Test
    void aMismatchedContentHashIsRejected() {
        assertThatThrownBy(() -> artifacts.create(sessionId, request(BODY, "sha256:deadbeef")))
                .isInstanceOf(ApiException.BadRequest.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("content_hash_mismatch"));
    }

    @Test
    void aTruncatedBodyIsRejectedEvenThoughItStillParses() {
        // The failure this check exists for. Both of these are valid JSON; only the
        // hash distinguishes a real 2-row dump from a half-written one.
        var full = List.of(Map.of("order_id", "A"), Map.of("order_id", "B"));
        var truncated = List.of(Map.of("order_id", "A"));
        var hashOfFull = hasher.hash(ArtifactFormat.JSON, full);

        assertThatThrownBy(() -> artifacts.create(sessionId, request(truncated, hashOfFull)))
                .isInstanceOf(ApiException.BadRequest.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void anOmittedContentHashIsComputedServerSide() {
        var artifact = artifacts.create(sessionId, request(BODY, null));

        assertThat(artifact.contentHash())
                .isEqualTo(hasher.hash(ArtifactFormat.JSON, BODY))
                .startsWith("sha256:");
    }

    @Test
    void sizeBytesReflectsTheCanonicalBody() {
        var artifact = artifacts.create(sessionId, request(BODY, null));

        assertThat(artifact.sizeBytes())
                .isEqualTo(hasher.canonicalBytes(ArtifactFormat.JSON, BODY).length);
    }

    @Test
    void anRdbmsTableWithoutPkColumnsIsRejected() {
        var noPk = new CreateArtifactRequest(
                "rdbms_table", "oms_pg", "orders", ArtifactFormat.JSON, BODY, 1, null, null, null, Map.of());

        assertThatThrownBy(() -> artifacts.create(sessionId, noPk))
                .isInstanceOf(ApiException.BadRequest.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("pk_columns_required"));
    }

    @Test
    void aNonRdbmsArtifactNeedsNoPkColumns() {
        var log = new CreateArtifactRequest(
                "log", "manual", "app.log", ArtifactFormat.TEXT, "boot ok", null, null, null, null, null);

        assertThatCode(() -> artifacts.create(sessionId, log)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- one_body

    @Test
    void bodyAndExternalUriTogetherAreRejected() {
        // Not reachable through the API -- CreateArtifactRequest has no external_uri
        // until v0.2. Asserted at the DB, which is where the guarantee actually lives.
        //
        // Deliberately does not assert WHICH constraint fires. This row violates both
        // (num_nonnulls=2 fails one_body's <=1 and body_present_unless_purged's =1),
        // and Postgres reports whichever it evaluates first -- an ordering it does not
        // promise. The purged variant below is what pins one_body specifically.
        assertThatThrownBy(() -> insertRaw("'[]'::jsonb", "'s3://autwit/tmp/a1.json'", "null"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("violates check constraint");
    }

    @Test
    void anArtifactWithNoBodyAndNoPurgeIsRejected() {
        assertThatThrownBy(() -> insertRaw("null", "null", "null"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("body_present_unless_purged");
    }

    @Test
    void oneBodyStillRejectsTheBothPresentCaseOnAPurgedRow() {
        // one_body is not merely shadowed by the purge check: it independently rejects
        // body+external_uri even when purged_at is set, which is what protects the
        // v0.2 external_uri path before it is built.
        //
        // Naming the constraint IS safe here, unlike the test above: purged_at
        // satisfies body_present_unless_purged, so one_body is the only thing that can
        // reject this row.
        assertThatThrownBy(() -> insertRaw("'[]'::jsonb", "'s3://autwit/tmp/a1.json'", "now()"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("one_body");
    }

    @Test
    void exactlyOneBodyIsAccepted() {
        assertThatCode(() -> insertRaw("'[]'::jsonb", "null", "null")).doesNotThrowAnyException();
        assertThatCode(() -> insertRaw("null", "'s3://autwit/tmp/a2.json'", "null")).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- purge

    @Test
    void purgeNullsTheBodyKeepsTheRowAndGetReturns410() {
        var artifactId = artifacts.create(sessionId, request(BODY, null)).artifactId();

        assertThat(repository.purge(artifactId)).isTrue();

        // 410, not 404: the artifact existed and its metadata still does.
        assertThatThrownBy(() -> artifacts.get(artifactId))
                .isInstanceOf(ApiException.Gone.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("artifact_purged"));

        // The row survives retention -- the trace stays intact.
        var ref = repository.findRef(artifactId).orElseThrow();
        assertThat(ref.bodyAvailable()).isFalse();
        assertThat(ref.purgedAt()).isNotNull();
        assertThat(ref.contentHash()).isNotNull();
        assertThat(ref.logicalName()).isEqualTo("orders");
        assertThat(ref.sizeBytes()).isPositive();

        assertThat(jdbc.queryForObject(
                "select count(*) from autwit.artifact where artifact_id = ? and body_jsonb is null",
                Integer.class, artifactId))
                .isEqualTo(1);
    }

    @Test
    void purgeIsIdempotent() {
        var artifactId = artifacts.create(sessionId, request(BODY, null)).artifactId();

        assertThat(repository.purge(artifactId)).isTrue();
        assertThat(repository.purge(artifactId)).as("already purged").isFalse();
    }

    @Test
    void aPurgedArtifactStillListsWithItsMetadata() {
        var artifactId = artifacts.create(sessionId, request(BODY, null)).artifactId();
        repository.purge(artifactId);

        assertThat(artifacts.list(sessionId, null, null, null))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.artifactId()).isEqualTo(artifactId);
                    assertThat(a.bodyAvailable()).isFalse();
                });
    }

    @Test
    void anUnknownArtifactIs404NotGone() {
        assertThatThrownBy(() -> artifacts.get(UUID.randomUUID()))
                .isInstanceOf(ApiException.NotFound.class);
    }

    /** Raw insert so the check constraints, not the service, are what is under test. */
    private void insertRaw(String bodyJsonb, String externalUri, String purgedAt) {
        jdbc.update("""
                insert into autwit.artifact
                  (session_id, artifact_type, source_system, logical_name, format,
                   body_jsonb, external_uri, purged_at, content_hash, size_bytes)
                values (?, 'rdbms_table', 'oms_pg', 'orders', 'json', %s, %s, %s, 'sha256:x', 2)
                """.formatted(bodyJsonb, externalUri, purgedAt), sessionId);
    }
}
