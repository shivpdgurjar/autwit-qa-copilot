package com.autwit.copilot.planning;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One finding from an analysis round. {@code kind} is {@code conflict} (a contradiction the
 * tester must confirm) or {@code clarification} (missing/ambiguous info to answer).
 * {@code sources} is the grounding — a list of {@code {doc_title, quote}} maps; {@code options}
 * is the distinct candidate values for a conflict (empty for a clarification).
 */
public record AnalysisFinding(
        UUID findingId,
        String kind,
        int seq,
        String title,
        String detail,
        List<Map<String, Object>> sources,
        List<String> options) {
}
