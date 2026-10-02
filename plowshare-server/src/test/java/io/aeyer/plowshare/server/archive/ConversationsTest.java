package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The resumption rule and the pricing rule, both tested without a database.
 *
 * <p>Each reads turns and then decides; only the reading needs Postgres, and it
 * is {@link ConversationStore}'s already. What is asserted here is the
 * decision, which is why these are plain records and not a container.
 */
class ConversationsTest {

    private static TurnRecord answeredBy(int ordinal, String agent) {
        return new TurnRecord("c1", ordinal, "asked", "answered",
                Outcome.Ending.ANSWERED, 10, agent, null);
    }

    @Test
    void continues_as_the_agent_that_answered_when_the_caller_names_nobody() {
        // The endpoint's whole convenience: leaving 'agent' out carries on as
        // whoever answered last, which is what a person means by "continue".
        assertEquals("interlocutor",
                Conversations.continuedAs("c1", null,
                        List.of(answeredBy(1, "scribe"), answeredBy(2, "interlocutor"))));
    }

    @Test
    void accepts_the_caller_naming_the_agent_that_actually_answered() {
        assertEquals("interlocutor",
                Conversations.continuedAs("c1", "interlocutor",
                        List.of(answeredBy(1, "interlocutor"))));
    }

    @Test
    void refuses_continuing_as_a_different_agent_than_the_one_that_answered() {
        // A resumption opens with that run's whole history, in the shape that
        // agent's own tools produced, so continuing as another agent would put
        // one agent's working in front of another under a different prompt.
        CallerFault refused = assertThrows(CallerFault.class,
                () -> Conversations.continuedAs("c1", "scribe",
                        List.of(answeredBy(1, "interlocutor"))));

        assertEquals(true, refused.getMessage().contains("interlocutor"));
        assertEquals(true, refused.getMessage().contains("scribe"));
    }

    @Test
    void takes_the_caller_at_its_word_when_no_turn_records_who_answered() {
        // True of every turn written before this server had a column for it.
        assertEquals("scribe",
                Conversations.continuedAs("c1", "scribe", List.of(answeredBy(1, null))));
    }

    @Test
    void refuses_when_nothing_recorded_an_agent_and_the_caller_named_none() {
        // Neither side knows, so the caller is asked rather than guessed for.
        assertThrows(CallerFault.class,
                () -> Conversations.continuedAs("c1", null, List.of(answeredBy(1, null))));
    }

    // --- who priced this conversation's next prompt ---------------------------------

    @Test
    void the_conversations_own_agent_wins_over_what_the_turns_say() {
        // A machine's log names its agent on the row before it has had a turn
        // at all, and goes on naming it after: the column is what agent that
        // conversation is, not merely a guess for when the turns are silent.
        assertEquals("curator",
                Conversations.answeredBy("curator",
                        List.of(answeredBy(1, "scribe"), answeredBy(2, "interlocutor"))));
    }

    @Test
    void with_no_name_on_the_record_the_last_turn_that_names_an_agent_wins() {
        assertEquals("interlocutor",
                Conversations.answeredBy(null,
                        List.of(answeredBy(1, "scribe"), answeredBy(2, "interlocutor"))));
    }

    @Test
    void with_neither_a_named_record_nor_a_turn_that_says_the_answer_is_null() {
        // A person's conversation, opened and not yet spoken into: there is no
        // prefix to price and none is invented.
        assertNull(Conversations.answeredBy(null, List.of(answeredBy(1, null))));
    }

    // --- the sentence a missing conversation is refused with -----------------

    /**
     * The phrase the caller reads is the whole tail, so a reading whose
     * sentence does not end in "&lt;reading&gt; to read" has a way to say so.
     *
     * <p>{@code ConversationController.turns} and {@code .compactions} are
     * those two readings, and each spelled its own {@code ArchiveException}
     * inline for exactly this reason. The sentence they produce is pinned here
     * character for character, because it is the thing that had to survive
     * their conversion — a client reads it, and {@code ConversationTurnsHandler}
     * now says it too.
     */
    @Test
    void the_whole_trailing_phrase_is_the_callers_to_write() {
        Conversations rules = over(Optional.empty());

        ArchiveException refused = assertThrows(ArchiveException.class,
                () -> rules.requireExistsOrThereIsNo("cnv_nope", "history to read back"));

        assertEquals("no conversation has the id cnv_nope, so there is no history to read back",
                refused.getMessage());
    }

    /** The other of the two, and the one no {@code reading} could have built. */
    @Test
    void the_seams_of_a_transcript_are_a_phrase_and_not_a_noun() {
        Conversations rules = over(Optional.empty());

        ArchiveException refused = assertThrows(ArchiveException.class,
                () -> rules.requireExistsOrThereIsNo(
                        "cnv_nope", "transcript to read the seams of"));

        assertEquals("no conversation has the id cnv_nope, so there is no transcript to read"
                + " the seams of", refused.getMessage());
    }

    /**
     * {@code requireExists} is the same sentence with {@code " to read"}
     * appended, and this is what holds it to that.
     *
     * <p>The four readings that call it — chat, trajectory, context, projection
     * — say these exact words today, and expressing one method in terms of the
     * other is only safe while that stays true.
     */
    @Test
    void requireExists_is_the_same_builder_with_to_read_on_the_end() {
        Conversations rules = over(Optional.empty());

        ArchiveException refused = assertThrows(ArchiveException.class,
                () -> rules.requireExists("cnv_nope", "chat"));

        assertEquals("no conversation has the id cnv_nope, so there is no chat to read",
                refused.getMessage());
    }

    /** A conversation that exists is not refused by either door. */
    @Test
    void a_conversation_that_exists_passes_both_doors() {
        Conversations rules = over(Optional.of(new ConversationRecord("cnv_1", Home.global(),
                Origin.TURN, ConversationLifecycle.ACTIVE, null, null,
                Instant.parse("2026-09-11T00:00:00Z"), null, null, null, null)));

        rules.requireExists("cnv_1", "chat");
        rules.requireExistsOrThereIsNo("cnv_1", "history to read back");
    }

    /** The rules over a store answering {@code found} for every id. */
    private static Conversations over(Optional<ConversationRecord> found) {
        ConversationStore conversations = mock(ConversationStore.class);
        when(conversations.find("cnv_nope")).thenReturn(Optional.empty());
        when(conversations.find("cnv_1")).thenReturn(found);
        return new Conversations(conversations, mock(TurnStore.class));
    }
}
