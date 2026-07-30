package com.autwit.copilot.planning;

import com.autwit.copilot.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit test — TextExtractor has no dependencies, so no Postgres needed. */
class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor();

    @Test
    void normalizeFoldsNewlinesAndToleratesEmpty() {
        assertThat(extractor.normalize("a\r\nb\r\n")).isEqualTo("a\nb");
        assertThat(extractor.normalize("  x  ")).isEqualTo("x");
        // PLAN-1: a truncated Jira body arrives empty; normalize must not throw (extract does).
        assertThat(extractor.normalize("")).isEmpty();
        assertThat(extractor.normalize(null)).isEmpty();
    }

    @Test
    void extractRejectsBinaryUntilPassTwo() {
        assertThatThrownBy(() -> extractor.extract("design.pdf", "application/pdf", "%PDF-1.7"))
                .isInstanceOf(ApiException.BadRequest.class)
                .hasMessageContaining("PDF/DOCX");
    }

    @Test
    void extractRejectsEmptyUploads() {
        assertThatThrownBy(() -> extractor.extract("spec", null, "   "))
                .isInstanceOf(ApiException.BadRequest.class)
                .hasMessageContaining("no text");
    }
}
