package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.files.Grant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ceiling on its own, away from a run.
 *
 * <p>What a run does with it is {@code JobRuntimeTest}'s subject — that a cap
 * moved mid-run is picked up at the next boundary, and that an uncapped run does
 * not stop. This file is the two states and the arithmetic between them, which
 * is where a comparison off by one would otherwise be visible only as an agent
 * quietly getting one turn more or less than its file asks for.
 */
class TurnCapTest {

    @Test
    void a_cap_permits_exactly_the_turns_it_names() {
        TurnCap cap = TurnCap.of(3);

        assertFalse(cap.stops(0));
        assertFalse(cap.stops(2), "two taken, one to go");
        assertTrue(cap.stops(3), "a cap of three permits three, so the third one done is done");
        assertTrue(cap.stops(4), "and anything past it, however it got there");
    }

    @Test
    void an_uncapped_run_is_never_stopped_by_a_cap_it_does_not_have() {
        TurnCap cap = TurnCap.none();

        assertFalse(cap.capped());
        assertFalse(cap.stops(0));
        assertFalse(cap.stops(1_000_000), "no number is the number");
    }

    /** The distinction the type exists for, asserted rather than described: no
     *  cap is a state, and asking it for a number is a mistake it names instead
     *  of a large one it invents. */
    @Test
    void an_uncapped_run_has_no_number_of_turns_to_report() {
        TurnCap cap = TurnCap.none();

        IllegalStateException none = assertThrows(IllegalStateException.class, cap::turns);

        assertTrue(none.getMessage().contains("no turn cap"), none.getMessage());
        assertEquals("no cap", cap.describe(),
                "and what it renders is the absence, not a ceiling");
    }

    @Test
    void a_cap_of_no_turns_is_refused_at_every_door() {
        assertThrows(IllegalArgumentException.class, () -> TurnCap.of(0));
        assertThrows(IllegalArgumentException.class, () -> TurnCap.of(-1));
        TurnCap cap = TurnCap.of(4);
        assertThrows(IllegalArgumentException.class, () -> cap.changeTo(0));
        assertThrows(IllegalArgumentException.class, () -> cap.changeTo(-1));
        assertEquals(4, cap.turns(), "and a refused change left the cap where it was");
    }

    @Test
    void a_cap_that_is_raised_stops_a_run_it_used_to_stop() {
        TurnCap cap = TurnCap.of(2);
        assertTrue(cap.stops(2));

        cap.changeTo(5);

        assertFalse(cap.stops(2));
        assertFalse(cap.stops(4));
        assertTrue(cap.stops(5));
    }

    /** Lowering below the turns already taken is the case with nowhere obvious
     *  to go, and it goes to "stop": the run has had more than it is now allowed
     *  and the next boundary is where that is acted on. */
    @Test
    void a_cap_lowered_under_what_a_run_has_taken_stops_it() {
        TurnCap cap = TurnCap.of(40);
        assertFalse(cap.stops(11));

        cap.changeTo(4);

        assertTrue(cap.stops(11));
        assertEquals(4, cap.turns());
    }

    @Test
    void a_cap_that_is_lifted_stops_being_a_cap_rather_than_becoming_a_bigger_one() {
        TurnCap cap = TurnCap.of(2);

        cap.lift();

        assertFalse(cap.capped());
        assertFalse(cap.stops(99));
        assertThrows(IllegalStateException.class, cap::turns);
    }

    @Test
    void a_lifted_cap_can_be_put_back() {
        TurnCap cap = TurnCap.none();

        cap.changeTo(3);

        assertTrue(cap.capped());
        assertTrue(cap.stops(3));
    }

    /** The agent's own file is where every run starts, and this is the only
     *  place that reads it. */
    @Test
    void a_definitions_cap_is_the_number_its_frontmatter_names() {
        TurnCap cap = TurnCap.from(definitionWith(7));

        assertTrue(cap.capped());
        assertEquals(7, cap.turns());
    }

    /** The plural is generated, so "1 turns" is the failure to guard against;
     *  it is the same word {@code JobRuntime} puts in its cancellation sentence,
     *  which is why it lives here and not in two places. */
    @Test
    void a_cap_describes_itself_in_words_a_sentence_can_take() {
        assertEquals("1 step", TurnCap.of(1).describe());
        assertEquals("2 steps", TurnCap.of(2).describe());
        assertEquals("100 steps", TurnCap.of(100).describe());
        assertEquals("step", TurnCap.stepWord(1));
        assertEquals("steps", TurnCap.stepWord(0));
        assertEquals("steps", TurnCap.stepWord(2));
    }

    /** A run reads its cap on its own thread while an operator writes it from
     *  another. Not a proof of the memory model — one cannot be written — but
     *  the write really is seen by a later read on a different thread, which is
     *  the shape a raise-mid-run depends on. */
    @Test
    void a_cap_written_on_one_thread_is_read_on_another() throws Exception {
        TurnCap cap = TurnCap.of(2);

        Thread operator = new Thread(() -> cap.changeTo(9));
        operator.start();
        operator.join();

        assertFalse(cap.stops(8), "the raise was seen");
        assertTrue(cap.stops(9));
    }

    // --- the three levels, narrowest winning --------------------------------------

    @Test
    void an_agent_that_nothing_overrides_is_capped_at_what_its_file_asks_for() {
        TurnCap chosen = TurnCap.chosen(null, null, definitionWith(4));

        assertEquals(4, chosen.turns());
    }

    @Test
    void a_conversations_cap_beats_the_agents_own() {
        TurnCap conversation = TurnCap.of(30);

        TurnCap chosen = TurnCap.chosen(null, conversation, definitionWith(4));

        assertSame(conversation, chosen, "the run reads the object the caller can move");
        assertEquals(30, chosen.turns());
    }

    @Test
    void a_runs_own_cap_beats_the_conversations() {
        TurnCap run = TurnCap.of(2);

        TurnCap chosen = TurnCap.chosen(run, TurnCap.of(30), definitionWith(4));

        assertSame(run, chosen);
        assertEquals(2, chosen.turns());
    }

    /** Narrowest wins even when the narrowest is the widest thing it can say:
     *  "let this one run" is a decision at the run level and it beats a number
     *  set at either of the two above it. */
    @Test
    void a_run_that_says_it_has_no_cap_is_not_given_the_conversations() {
        TurnCap chosen = TurnCap.chosen(TurnCap.none(), TurnCap.of(30), definitionWith(4));

        assertFalse(chosen.capped());
    }

    @Test
    void a_conversation_that_says_it_has_no_cap_is_not_given_the_agents() {
        TurnCap chosen = TurnCap.chosen(null, TurnCap.none(), definitionWith(4));

        assertFalse(chosen.capped());
    }

    private static AgentDefinition definitionWith(int maxTurns) {
        return new AgentDefinition("counter", "counts", "fast", List.of(), List.of(),
                List.<Grant>of(), maxTurns, 8, "body");
    }
}
