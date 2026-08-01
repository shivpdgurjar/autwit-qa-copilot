package com.autwit.copilot.planning;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import com.autwit.copilot.common.ApiException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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

    // ---- extractFile (Tika): the pass-2 binary path -----------------------------------

    @Test
    void extractFileReadsPlainTextBytes() {
        var bytes = "retry with exponential backoff".getBytes(StandardCharsets.UTF_8);
        assertThat(extractor.extractFile("spec.txt", "text/plain", bytes))
                .isEqualTo("retry with exponential backoff");
    }

    @Test
    void extractFileReadsDocx() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("idempotency key prevents duplicate charge");
            doc.write(out);
        }
        assertThat(extractor.extractFile("spec.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", out.toByteArray()))
                .contains("idempotency key prevents duplicate charge");
    }

    @Test
    void extractFileReadsXlsx() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var wb = new XSSFWorkbook()) {
            var row = wb.createSheet("cases").createRow(0);
            row.createCell(0).setCellValue("TC-01");
            row.createCell(1).setCellValue("retry succeeds within max attempts");
            wb.write(out);
        }
        assertThat(extractor.extractFile("cases.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray()))
                .contains("TC-01")
                .contains("retry succeeds within max attempts");
    }

    @Test
    void extractFileRejectsAnUnreadableFile() {
        // Bytes that claim to be a PDF but aren't → a clean 400, not a 500.
        assertThatThrownBy(() -> extractor.extractFile("x.pdf", "application/pdf", new byte[] {1, 2, 3, 4}))
                .isInstanceOf(ApiException.BadRequest.class);
    }

    @Test
    void extractFileRejectsEmptyBytes() {
        assertThatThrownBy(() -> extractor.extractFile("x.docx", null, new byte[0]))
                .isInstanceOf(ApiException.BadRequest.class);
    }
}
