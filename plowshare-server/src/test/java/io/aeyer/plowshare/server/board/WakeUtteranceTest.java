package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The harness's one wake line — spec 2026-09-29 §6. The topic's label and title are an author's
 * words inside the harness's voice, so they are rendered as quoted data: the label bracketed,
 * the title in double quotes, and anything in either that would close its quotes escaped.
 */
class WakeUtteranceTest {

    private static BoardTopic topic(String title, String label) {
        return new BoardTopic("bdt_1", "payments", null, "bdt_1", 0, title, label, "enzo",
                BoardTopic.BY_PERSON, "enzo", null, BoardTopic.OPEN, null, 20, 0, 2, null,
                Instant.EPOCH, null);
    }

    @Test
    void the_label_and_title_are_quoted_as_data() {
        assertEquals("Board · [BAD SPEC] \"sync between devices\" · woken because @critic"
                + " mentioned you · 3 unread · use board_read.",
                WakeUtterance.of(topic("sync between devices", "BAD SPEC"),
                        WakeRules.Reason.MENTION, "critic", 3));
    }

    @Test
    void a_quote_or_bracket_in_the_authors_words_cannot_close_its_quotes() {
        assertEquals("Board · [A\\] B] \"say \\\"done\\\" \\\\ now\" · woken because a new topic"
                + " opened · 1 unread · use board_read.",
                WakeUtterance.of(topic("say \"done\" \\ now", "A] B"),
                        WakeRules.Reason.OPENED, "", 1));
    }
}
