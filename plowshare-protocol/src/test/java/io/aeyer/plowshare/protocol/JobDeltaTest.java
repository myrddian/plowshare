package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The wire shape of a delta, pinned because another language reads it.
 *
 * <p>{@code plowshare-tui} parses these by hand, in TypeScript, against no
 * schema — so the exact spelling Jackson gives an enum is a contract and not an
 * implementation detail. <b>Guessing it is the failure this file exists to
 * prevent</b>: a client reading {@code "thinking"} where the server writes
 * {@code "THINKING"} shows nothing at all and reports no error, because a delta
 * it cannot read is indistinguishable from a delta that never came.
 */
class JobDeltaTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void the_part_is_written_in_the_case_the_enum_declares() throws Exception {
        assertEquals("{\"job\":\"job_1\",\"part\":\"THINKING\",\"text\":\"Let \"}",
                json.writeValueAsString(JobDelta.thinking("job_1", "Let ")));
        assertEquals("{\"job\":\"job_1\",\"part\":\"ANSWER\",\"text\":\"Bor\"}",
                json.writeValueAsString(JobDelta.answering("job_1", "Bor")));
    }

    @Test
    void it_carries_no_kind_which_is_what_keeps_it_out_of_an_event_handler()
            throws Exception {
        // The console reads pushes with an if/else-if over `kind` and no else.
        // A delta that grew one would enter that chain; carrying none is what
        // makes it fall out of it without being mentioned.
        String written = json.writeValueAsString(JobDelta.answering("job_1", "x"));
        assertFalse(written.contains("kind"),
                "a delta grew a 'kind' field, which puts it into the console's event"
                        + " handler: " + written);
    }

    @Test
    void it_carries_no_sequence_number() throws Exception {
        // Deltas are droppable by design, so a number that made the holes
        // countable would invite somebody to try to fill them -- the same
        // reasoning JobEvent gives, reached from the other direction.
        String written = json.writeValueAsString(JobDelta.thinking("job_1", "x"));
        assertFalse(written.contains("seq") || written.contains("index"),
                "a delta grew a sequence number: " + written);
    }

    @Test
    void a_delta_reads_back_as_what_was_written() throws Exception {
        JobDelta sent = JobDelta.thinking("job_1", "reasoning");
        assertEquals(sent, json.readValue(json.writeValueAsString(sent), JobDelta.class));
    }

    @Test
    void the_two_factories_differ_only_in_their_part() {
        assertEquals(JobDelta.Part.THINKING, JobDelta.thinking("j", "t").part());
        assertEquals(JobDelta.Part.ANSWER, JobDelta.answering("j", "t").part());
        assertTrue(JobDelta.Part.values().length == 2,
                "a third part would need a decision in every renderer that draws these");
    }
}
