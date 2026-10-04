package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.aeyer.plowshare.server.documents.CitationStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestedCitationScopeTest {

  private static final String DOCUMENT = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

  private final CitationStore citations = mock(CitationStore.class);

  @Test
  void naming_neither_is_the_whole_corpus_newest_first() {
    RequestedCitationScope scope = RequestedCitationScope.in(null, null);

    assertEquals("all", scope.scope());
    scope.from(citations, 7);

    verify(citations).recent(7);
  }

  @Test
  void naming_a_conversation_asks_what_that_conversation_cited() {
    RequestedCitationScope scope = RequestedCitationScope.in("  cnv_1 ", null);

    assertEquals("conversation", scope.scope());
    scope.from(citations, 7);

    // Stripped, because an id pasted out of a listing arrives with
    // whatever the paste brought -- which is what the endpoint has always
    // done to this field.
    verify(citations).madeIn("cnv_1", 7);
  }

  @Test
  void naming_a_document_asks_what_has_cited_it() {
    RequestedCitationScope scope = RequestedCitationScope.in(null, DOCUMENT);

    assertEquals("document", scope.scope());
    scope.from(citations, 7);

    verify(citations).of(UUID.fromString(DOCUMENT), 7);
  }

  @Test
  void a_document_that_is_not_an_id_is_refused_when_the_read_is_made_and_not_before() {
    // The parse waits, deliberately: the endpoint refuses both-scopes and
    // then a limit under one before it ever reads this field as an id, so
    // a request that gets two of those wrong is told about the first.
    RequestedCitationScope scope = RequestedCitationScope.in(null, "nope");

    assertEquals("document", scope.scope());
    assertThrows(CallerFault.class, () -> scope.from(citations, 7));
    verifyNoInteractions(citations);
  }

  @Test
  void both_scopes_at_once_is_refused_rather_than_one_silently_winning() {
    // Both were sent on purpose, and answering one of them would be this
    // server deciding which the caller meant.
    String said =
        assertThrows(CallerFault.class, () -> RequestedCitationScope.in("cnv_1", DOCUMENT))
            .getMessage();

    assertTrue(said.startsWith("`conversation` and `document` are two different questions"), said);
    verifyNoInteractions(citations);
  }
}
