package com.autwit.copilot.planning;

import java.io.ByteArrayInputStream;

import com.autwit.copilot.common.ApiException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

/**
 * Turns an uploaded/pasted source into the plain text a generation reads.
 *
 * <p>Two paths: {@link #extract} is for text that arrives already as text (a paste, or a
 * text file read client-side) — validation + normalisation only. {@link #extractFile} is for
 * uploaded <b>binary</b> documents (PDF, DOCX, XLSX, …) — the bytes reach the server and Tika
 * extracts their text (PDFBox for PDF, POI for the Office formats). Both land in the same
 * {@code text_content} column.
 */
@Component
public class TextExtractor {

    /** ~1MB of text per document — enough for a design doc, a guard against a runaway paste. */
    private static final int MAX_CHARS = 1_000_000;

    /**
     * @param filename display name (may carry the extension we sniff for binary formats)
     * @param mime     declared content type, or null
     * @param rawText  the text the UI posted (file content read client-side, or a paste)
     * @return the normalised text to persist
     */
    public String extract(String filename, String mime, String rawText) {
        if (looksBinary(filename, mime)) {
            throw new ApiException.BadRequest("unsupported_format",
                    "PDF/DOCX extraction is not in this pass yet — paste the text, or upload "
                            + "Markdown/plain text. (Binary parsing is pass 2.)");
        }
        if (rawText == null || rawText.isBlank()) {
            throw new ApiException.BadRequest("empty_document",
                    "The document '%s' has no text content.".formatted(filename == null ? "(pasted)" : filename));
        }
        if (rawText.length() > MAX_CHARS) {
            throw new ApiException.PayloadTooLarge(
                    "Document is %d chars; the limit is %d.".formatted(rawText.length(), MAX_CHARS));
        }
        return normalize(rawText);
    }

    /**
     * Extracts text from an uploaded document's raw bytes with Tika — PDF (PDFBox), DOCX/XLSX
     * and other Office formats (POI), plus text/HTML/CSV. Any upload flows through here, so the
     * UI no longer has to read files client-side or know which formats are text vs binary.
     *
     * @param filename the original name — a detection hint and the error label
     * @param mime     the declared content type, or null — also a detection hint
     * @param bytes    the raw file
     * @return the extracted, normalised text (capped at {@link #MAX_CHARS})
     */
    public String extractFile(String filename, String mime, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException.BadRequest("empty_document",
                    "The uploaded file '%s' is empty.".formatted(filename == null ? "(unnamed)" : filename));
        }
        var handler = new BodyContentHandler(-1); // no Tika-side limit; we cap the result below
        var metadata = new Metadata();
        if (filename != null && !filename.isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        }
        if (mime != null && !mime.isBlank()) {
            metadata.set(Metadata.CONTENT_TYPE, mime);
        }
        String raw;
        try (var in = new ByteArrayInputStream(bytes)) {
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
            raw = handler.toString();
        } catch (Exception e) {
            // Encrypted PDF, corrupt file, unsupported format, etc. — a clear 400, not a 500.
            throw new ApiException.BadRequest("unreadable_document",
                    "Could not extract text from '%s': %s".formatted(filename, e.getMessage()));
        }
        if (raw.length() > MAX_CHARS) {
            raw = raw.substring(0, MAX_CHARS);
        }
        var text = normalize(raw);
        if (text.isBlank()) {
            // A scanned/image-only PDF or an empty sheet extracts to nothing — we don't OCR.
            throw new ApiException.BadRequest("no_text_extracted",
                    "No text could be extracted from '%s' — it may be a scanned image or empty. "
                            .formatted(filename) + "Paste the text instead.");
        }
        return text;
    }

    /**
     * Normalises already-fetched text (Jira/Confluence bodies pulled over MCP) — newlines
     * folded so the same content hashes the same, no upload-time guards. Unlike {@link #extract},
     * <b>empty is allowed</b>: the aashari Jira server truncates very large issue bodies and
     * {@code fetch_context} then returns that document with empty text (KNOWN_ISSUES PLAN-1) —
     * the document still lands, it just contributes nothing to generation, rather than failing
     * the whole fetch.
     */
    public String normalize(String rawText) {
        return rawText == null ? "" : rawText.replace("\r\n", "\n").replace("\r", "\n").strip();
    }

    /** True for formats pass 1 cannot read as text. */
    public boolean looksBinary(String filename, String mime) {
        if (mime != null) {
            var m = mime.toLowerCase();
            if (m.contains("pdf") || m.contains("officedocument") || m.contains("msword")
                    || m.startsWith("application/octet-stream")) {
                return true;
            }
        }
        if (filename != null) {
            var f = filename.toLowerCase();
            return f.endsWith(".pdf") || f.endsWith(".docx") || f.endsWith(".doc");
        }
        return false;
    }
}
