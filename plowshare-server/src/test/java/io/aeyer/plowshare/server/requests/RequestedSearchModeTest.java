package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

class RequestedSearchModeTest {

    @Test
    void every_mode_this_server_has_is_readable_in_any_case_and_with_any_padding() {
        for (RetrievalService.Mode mode : RetrievalService.Mode.values()) {
            assertEquals(mode, RequestedSearchMode.in(mode.name()));
            assertEquals(mode, RequestedSearchMode.in(" " + mode.name().toLowerCase() + " "));
        }
    }

    @Test
    void a_mode_nobody_recognises_is_refused_and_never_answered_as_hybrid() {
        // A caller comparing two answers to one question -- which is the whole
        // of what the field is for -- would otherwise be comparing an answer
        // against itself and concluding the two halves agree.
        String said = assertThrows(CallerFault.class,
                () -> RequestedSearchMode.in("semantic")).getMessage();

        assertTrue(said.startsWith("`mode` is 'semantic'; it must be one of "), said);
        assertTrue(said.contains("hybrid, vector, lexical"), said);
    }

    @Test
    void blank_is_refused_rather_than_read_as_a_field_nobody_sent() {
        // The endpoint's own distinction: a caller that meant to choose and
        // sent nothing has said something. Absence is the controller's null
        // branch and never reaches here.
        assertThrows(CallerFault.class, () -> RequestedSearchMode.in("  "));
    }

    @Test
    void the_refusal_enumerates_the_modes_rather_than_naming_them_twice() {
        // Read off the enum, so a fourth mode cannot ship with a message that
        // lists three.
        String said = assertThrows(CallerFault.class,
                () -> RequestedSearchMode.in("nope")).getMessage();

        for (RetrievalService.Mode mode : RetrievalService.Mode.values()) {
            assertTrue(said.contains(mode.name().toLowerCase()), said);
        }
    }
}
