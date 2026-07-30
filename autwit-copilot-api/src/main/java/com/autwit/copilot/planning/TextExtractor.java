package com.autwit.copilot.planning;

import com.autwit.copilot.common.ApiException;
import org.springframework.stereotype.Component;

/**
 * Turns an uploaded/pasted source into the plain text a generation reads.
 *
 * <p><b>Pass 1 is text-native:</b> Markdown, plain text, CSV, HTML and raw paste arrive as
 * text (the UI reads text files client-side and posts their content), so extraction is
 * validation + normalisation, not parsing. Binary formats (PDF, DOCX) are <b>deferred to pass
 * 2</b> — they need server-side parsing (Tika) and are rejected here with a clear message
 * rather than stored as mojibake. The {@code text_content} column is already the right shape
 * for pass 2, so that widening lives entirely in this class.
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
