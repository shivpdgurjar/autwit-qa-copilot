package com.autwit.copilot.artifact;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the canonical form SKILL_CONTRACT §6.1 leaves undefined.
 *
 * <p>The expected hashes below were computed with Python's hashlib, not with this
 * class, so they assert the definition rather than the implementation. That matters:
 * these vectors are what a Python or Node orchestrator can be checked against, and a
 * test that hashed with the code under test would agree with any bug it contained.
 */
class ContentHasherTest {

    /** Mirrors the application.yml Jackson config that reaches ContentHasher at runtime. */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final ContentHasher hasher = new ContentHasher(MAPPER);

    @Test
    void jsonKeysAreSortedRegardlessOfInputOrder() {
        var inOrder = new LinkedHashMap<String, Object>();
        inOrder.put("a", 1);
        inOrder.put("b", 2);

        var reversed = new LinkedHashMap<String, Object>();
        reversed.put("b", 2);
        reversed.put("a", 1);

        assertThat(hasher.canonicalJson(reversed)).isEqualTo("{\"a\":1,\"b\":2}");
        assertThat(hasher.hash(ArtifactFormat.JSON, reversed))
                .isEqualTo("sha256:43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777")
                .isEqualTo(hasher.hash(ArtifactFormat.JSON, inOrder));
    }

    @Test
    void nestedKeysAreSortedToo() {
        Map<String, Object> nested = Map.of("outer", new LinkedHashMap<>(Map.of("z", 1, "a", 2)));
        assertThat(hasher.canonicalJson(nested)).isEqualTo("{\"outer\":{\"a\":2,\"z\":1}}");
    }

    @Test
    void arrayOrderIsPreserved() {
        // Sorting keys must not imply sorting arrays: row order is data.
        assertThat(hasher.canonicalJson(List.of(3, 1, 2))).isEqualTo("[3,1,2]");
    }

    @Test
    void moneyScaleSurvivesCanonicalisation() {
        // The reason use-big-decimal-for-floats is set. As a Double this renders
        // 1200.0 and hashes differently -- see the next test.
        Map<String, Object> body = Map.of("total_amount", new BigDecimal("1200.00"));

        assertThat(hasher.canonicalJson(body)).isEqualTo("{\"total_amount\":1200.00}");
        assertThat(hasher.hash(ArtifactFormat.JSON, body))
                .isEqualTo("sha256:9265ceec3116b656a0e2c1711c4d4c61a923ad4f4b415e56c1be43f314bdd3f1");
    }

    @Test
    void differingScaleIsADifferentHash() {
        // Guards the failure this is all designed to prevent: 1200.00 and 1200.0 are
        // the same number and different evidence. If these ever collide, scale is
        // being destroyed somewhere in the pipeline.
        var twoDp = hasher.hash(ArtifactFormat.JSON, Map.of("total_amount", new BigDecimal("1200.00")));
        var oneDp = hasher.hash(ArtifactFormat.JSON, Map.of("total_amount", new BigDecimal("1200.0")));

        assertThat(twoDp).isNotEqualTo(oneDp);
        assertThat(oneDp).isEqualTo("sha256:42ccde2e5bb1f12b2cae0d0b06eea6dcd7dcffbf0be20ea7e9936fbffcf2b782");
    }

    @Test
    void parsingWithTheAppsMapperPreservesScale() throws Exception {
        // The path a real request takes: JSON text -> Jackson -> hasher.
        Object parsed = MAPPER.readValue("{\"total_amount\":1200.00}", Object.class);

        assertThat(hasher.hash(ArtifactFormat.JSON, parsed))
                .as("a body that arrives as 1200.00 must hash as 1200.00")
                .isEqualTo("sha256:9265ceec3116b656a0e2c1711c4d4c61a923ad4f4b415e56c1be43f314bdd3f1");
    }

    @Test
    void textIsHashedAsRawUtf8() {
        assertThat(hasher.hash(ArtifactFormat.TEXT, "hello"))
                .isEqualTo("sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void xmlIsHashedAsRawUtf8WithoutNormalisation() {
        assertThat(hasher.hash(ArtifactFormat.XML, "<order id=\"XXXX\"/>"))
                .isEqualTo("sha256:a8a15f1ddca30f0f91fce79c75592113165a4a43857bd94f14d26a7584f75443");
    }

    @Test
    void binaryIsHashedOverDecodedBytesNotTheBase64Text() {
        var base64 = java.util.Base64.getEncoder().encodeToString(new byte[] {0, 1, 2, 3});

        assertThat(hasher.hash(ArtifactFormat.BINARY, base64))
                .isEqualTo("sha256:054edec1d0211f624fed0cbca9d4f9400b0e491c43742af2c5b0abebf0c990d8");
        assertThat(hasher.canonicalBytes(ArtifactFormat.BINARY, base64))
                .containsExactly(0, 1, 2, 3);
    }

    @Test
    void matchIsCaseAndWhitespaceInsensitive() {
        assertThat(hasher.matches("sha256:ABC", " sha256:abc ")).isTrue();
        assertThat(hasher.matches("sha256:abc", "sha256:abd")).isFalse();
        assertThat(hasher.matches(null, "sha256:abc")).isFalse();
    }

    @Test
    void aNonStringBodyForATextFormatIsRejected() {
        assertThatThrownBy(() -> hasher.hash(ArtifactFormat.XML, Map.of("not", "a string")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a string body");
    }

    @Test
    void invalidBase64ForBinaryIsRejected() {
        assertThatThrownBy(() -> hasher.hash(ArtifactFormat.BINARY, "not!valid!base64!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid base64");
    }
}
