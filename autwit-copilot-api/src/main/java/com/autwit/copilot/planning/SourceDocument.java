package com.autwit.copilot.planning;

import java.time.Instant;
import java.util.UUID;

/**
 * One item in a project's corpus, normalised to extracted text regardless of origin
 * (uploaded file, pasted text, or a Jira/Confluence item fetched over MCP).
 *
 * @param externalRef Jira key / Confluence page id / filename; null for a raw paste.
 * @param docRole     what the document IS (requirement/architecture/existing tests/domain
 *                    rules), as opposed to {@code sourceType}, which is where it came from.
 *                    Drives how the generator weights it.
 * @param selected    only selected documents feed generation — the tester's include toggle.
 * @param contentHash sha256 over the §6.1 canonical form of {@code textContent}; dedupes a
 *                    re-fetch/re-upload of the same item within the project.
 */
public record SourceDocument(
        UUID documentId,
        UUID projectId,
        SourceType sourceType,
        DocRole docRole,
        String externalRef,
        String title,
        String mime,
        String textContent,
        boolean selected,
        String contentHash,
        Instant createdAt) {
}
