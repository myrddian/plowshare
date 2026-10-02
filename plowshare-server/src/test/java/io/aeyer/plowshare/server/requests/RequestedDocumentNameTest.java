package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

class RequestedDocumentNameTest {

    @Test
    void an_absent_name_files_the_document_under_the_filename_it_arrived_as() {
        assertEquals("paper.pdf", RequestedDocumentName.in(null, "paper.pdf"));
    }

    @Test
    void a_name_the_caller_chose_wins_over_the_filename() {
        assertEquals("The Paper", RequestedDocumentName.in("The Paper", "paper.pdf"));
    }

    @Test
    void a_blank_name_is_refused_rather_than_falling_back_to_the_filename() {
        // A caller that meant to name this document and sent the field empty
        // would otherwise have it filed somewhere they did not choose, and
        // never be told -- and a name is what a re-ingest matches on.
        String said = assertThrows(CallerFault.class,
                () -> RequestedDocumentName.in("   ", "paper.pdf")).getMessage();

        assertTrue(said.startsWith("the `name` field is blank."), said);
    }
}
