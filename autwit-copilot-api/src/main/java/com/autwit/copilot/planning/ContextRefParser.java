package com.autwit.copilot.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns whatever a tester types into Jira keys and Confluence page ids.
 *
 * <p>Step 2 previously only accepted what its own search returned, so adding a ticket you
 * already knew the key of, or a page someone linked you in chat, meant searching for it by
 * keyword and hoping it came back. This normalises a bare key, a bare page id, or a pasted
 * URL into the same two lists {@code fetchContext} already takes.
 *
 * <p>Parsing lives here rather than in the browser so the same forms work from the API and
 * from CI, and so the accepted shapes are covered by tests rather than by a regex in a
 * component. Nothing here calls Confluence: a URL is resolved only if it carries the id.
 * That is why tiny links ({@code /wiki/x/AbCdEf}) and title-only paths
 * ({@code /display/SPACE/Title}) are rejected rather than guessed at — they identify a page
 * to Confluence, not to us.
 */
public final class ContextRefParser {

    /** PROJ-123. Jira keys are uppercase; lowercase input is accepted and normalised. */
    private static final Pattern JIRA_KEY = Pattern.compile("^[A-Z][A-Z0-9_]*-\\d+$");

    private static final Pattern PAGE_ID = Pattern.compile("^\\d+$");

    /** .../wiki/spaces/OES/pages/123456789/Some+Title — the id follows /pages/. */
    private static final Pattern URL_PAGE_PATH = Pattern.compile("/pages/(\\d+)");

    /** .../pages/viewpage.action?pageId=123456789 — the older query form. */
    private static final Pattern URL_PAGE_QUERY = Pattern.compile("[?&]pageId=(\\d+)");

    /** .../browse/PAY-123 */
    private static final Pattern URL_BROWSE = Pattern.compile("/browse/([A-Za-z][A-Za-z0-9_]*-\\d+)");

    /** .../boards/1?selectedIssue=PAY-123 — what "copy link" gives you from a board. */
    private static final Pattern URL_SELECTED_ISSUE =
            Pattern.compile("[?&]selectedIssue=([A-Za-z][A-Za-z0-9_]*-\\d+)");

    private ContextRefParser() {
    }

    /**
     * @param jiraKeys          resolved Jira keys, de-duplicated, input order preserved
     * @param confluencePageIds resolved Confluence page ids, same
     * @param rejected          entries nothing could be made of, each with why
     */
    public record Refs(List<String> jiraKeys, List<String> confluencePageIds, List<Rejected> rejected) {
    }

    public record Rejected(String input, String reason) {
    }

    /**
     * Classifies free-form entries. Order is preserved and duplicates collapse, so pasting a
     * list twice — or a key that search already found — costs nothing.
     */
    public static Refs parse(List<String> entries) {
        var jira = new ArrayList<String>();
        var pages = new ArrayList<String>();
        var rejected = new ArrayList<Rejected>();

        for (var raw : entries == null ? List.<String>of() : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            var entry = raw.trim();

            if (looksLikeUrl(entry)) {
                var pageId = firstGroup(URL_PAGE_PATH, entry, firstGroup(URL_PAGE_QUERY, entry, null));
                if (pageId != null) {
                    addOnce(pages, pageId);
                    continue;
                }
                var issue = firstGroup(URL_BROWSE, entry, firstGroup(URL_SELECTED_ISSUE, entry, null));
                if (issue != null) {
                    addOnce(jira, issue.toUpperCase());
                    continue;
                }
                rejected.add(new Rejected(entry, describeUnresolvableUrl(entry)));
                continue;
            }

            var upper = entry.toUpperCase();
            if (JIRA_KEY.matcher(upper).matches()) {
                addOnce(jira, upper);
            } else if (PAGE_ID.matcher(entry).matches()) {
                addOnce(pages, entry);
            } else {
                rejected.add(new Rejected(entry,
                        "not a Jira key (PROJ-123), a Confluence page id, or a link containing one"));
            }
        }
        return new Refs(jira, pages, rejected);
    }

    /** A reason a tester can act on, rather than a generic "invalid". */
    private static String describeUnresolvableUrl(String entry) {
        if (entry.matches("(?i).*/wiki/x/[A-Za-z0-9]+.*")) {
            return "Confluence short links do not contain the page id — open the link and copy "
                    + "the full URL, which has /pages/<id>/ in it";
        }
        if (entry.contains("/display/")) {
            return "this Confluence link identifies the page by title, not by id — use the "
                    + "\"Copy link\" URL, which has /pages/<id>/ in it";
        }
        return "no Jira key or Confluence page id found in this link";
    }

    private static boolean looksLikeUrl(String entry) {
        return entry.contains("://") || entry.startsWith("/") || entry.contains("/browse/")
                || entry.contains("/wiki/");
    }

    private static void addOnce(List<String> target, String value) {
        if (!target.contains(value)) {
            target.add(value);
        }
    }

    private static String firstGroup(Pattern pattern, String input, String fallback) {
        var m = pattern.matcher(input);
        return m.find() ? m.group(1) : fallback;
    }
}
