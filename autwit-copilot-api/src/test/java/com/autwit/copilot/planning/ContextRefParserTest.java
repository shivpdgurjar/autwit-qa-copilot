package com.autwit.copilot.planning;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The forms a tester actually pastes. These are the contract — a shape that is not here is
 * not supported, and the point of parsing server-side is that this list is testable rather
 * than being a regex buried in a component.
 */
class ContextRefParserTest {

    @Test
    void acceptsBareJiraKeysAndPageIds() {
        var refs = ContextRefParser.parse(List.of("PAY-2481", "CAN-1201", "123456789"));

        assertThat(refs.jiraKeys()).containsExactly("PAY-2481", "CAN-1201");
        assertThat(refs.confluencePageIds()).containsExactly("123456789");
        assertThat(refs.rejected()).isEmpty();
    }

    @Test
    void normalisesLowercaseKeysAndTrimsWhitespace() {
        // Pasting a list out of a spreadsheet or chat brings both along.
        var refs = ContextRefParser.parse(List.of("  pay-2481 ", "\tcan-1201"));

        assertThat(refs.jiraKeys()).containsExactly("PAY-2481", "CAN-1201");
        assertThat(refs.rejected()).isEmpty();
    }

    @Test
    void resolvesAConfluencePageUrl() {
        var refs = ContextRefParser.parse(List.of(
                "https://acuver.atlassian.net/wiki/spaces/OES/pages/123456789/Cancellation+Design"));

        assertThat(refs.confluencePageIds()).containsExactly("123456789");
        assertThat(refs.jiraKeys()).isEmpty();
        assertThat(refs.rejected()).isEmpty();
    }

    @Test
    void resolvesTheOlderViewpageQueryForm() {
        var refs = ContextRefParser.parse(List.of(
                "https://confluence.example.com/pages/viewpage.action?pageId=987654321"));

        assertThat(refs.confluencePageIds()).containsExactly("987654321");
    }

    @Test
    void resolvesAJiraBrowseUrlAndABoardLink() {
        var refs = ContextRefParser.parse(List.of(
                "https://acuver.atlassian.net/browse/PAY-2481",
                "https://acuver.atlassian.net/jira/software/projects/CAN/boards/3?selectedIssue=CAN-1201"));

        assertThat(refs.jiraKeys()).containsExactly("PAY-2481", "CAN-1201");
        assertThat(refs.rejected()).isEmpty();
    }

    @Test
    void deduplicatesAcrossFormsAndRepeats() {
        // The same ticket pasted twice, once bare and once as a link, is one ticket.
        var refs = ContextRefParser.parse(List.of(
                "PAY-2481", "https://acuver.atlassian.net/browse/PAY-2481", "pay-2481"));

        assertThat(refs.jiraKeys()).containsExactly("PAY-2481");
    }

    @Test
    void preservesTheOrderEntriesWereGivenIn() {
        var refs = ContextRefParser.parse(List.of("CAN-1201", "PAY-2481", "AAA-1"));
        assertThat(refs.jiraKeys()).containsExactly("CAN-1201", "PAY-2481", "AAA-1");
    }

    @Test
    void skipsBlanksSilently() {
        // Pasting a list leaves trailing newlines; those are not user error worth reporting.
        var refs = ContextRefParser.parse(List.of("PAY-1", "", "   ", "\n"));

        assertThat(refs.jiraKeys()).containsExactly("PAY-1");
        assertThat(refs.rejected()).isEmpty();
    }

    @Test
    void rejectsAConfluenceShortLinkWithAnActionableReason() {
        // A tiny link identifies the page to Confluence, not to us — resolving it would mean
        // calling Confluence, which parsing deliberately does not do.
        var refs = ContextRefParser.parse(List.of("https://acuver.atlassian.net/wiki/x/AbCdEf"));

        assertThat(refs.confluencePageIds()).isEmpty();
        assertThat(refs.rejected()).singleElement().satisfies(r -> {
            assertThat(r.input()).contains("/wiki/x/");
            assertThat(r.reason()).contains("short link");
            // The reason has to tell them what to do instead, not just that it failed.
            assertThat(r.reason()).contains("/pages/<id>/");
        });
    }

    @Test
    void rejectsATitleOnlyConfluencePath() {
        var refs = ContextRefParser.parse(List.of(
                "https://confluence.example.com/display/OES/Cancellation+Design"));

        assertThat(refs.rejected()).singleElement()
                .satisfies(r -> assertThat(r.reason()).contains("by title, not by id"));
    }

    @Test
    void rejectsGibberishWithoutTakingTheBatchDown() {
        var refs = ContextRefParser.parse(List.of("PAY-1", "not a ref", "123", "https://example.com/x"));

        // Everything usable still resolves — one bad paste must not cost the rest.
        assertThat(refs.jiraKeys()).containsExactly("PAY-1");
        assertThat(refs.confluencePageIds()).containsExactly("123");
        assertThat(refs.rejected()).extracting(ContextRefParser.Rejected::input)
                .containsExactly("not a ref", "https://example.com/x");
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertThat(ContextRefParser.parse(null).jiraKeys()).isEmpty();
        assertThat(ContextRefParser.parse(List.of()).rejected()).isEmpty();
    }
}
