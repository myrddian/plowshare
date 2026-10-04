package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorpusTest {

  private static final UUID ID = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");

  private final DocumentStore documents = mock(DocumentStore.class);
  private final RetrievalService retrieval = mock(RetrievalService.class);

  @Test
  void a_document_the_corpus_holds_comes_back_as_the_row_the_store_answered_with() {
    DocumentStore.StoredDocument held = held();
    when(documents.find(ID)).thenReturn(Optional.of(held));

    assertEquals(ID, Corpus.theOneToAsk(documents, ID));
    assertSame(held, Corpus.theOneToRead(documents, ID));
  }

  @Test
  void a_document_the_corpus_does_not_hold_is_a_not_found_on_both_reads() {
    when(documents.find(ID)).thenReturn(Optional.empty());

    assertThrows(NotFoundFault.class, () -> Corpus.theOneToAsk(documents, ID));
    assertThrows(NotFoundFault.class, () -> Corpus.theOneToRead(documents, ID));
  }

  @Test
  void the_ask_and_the_read_say_different_things_about_the_same_absence() {
    // Two endpoints, two ways out. The ask points at the citations listing
    // and the read points at GET /v1/documents and its `q`; a shared
    // sentence would be wrong on one of them.
    when(documents.find(ID)).thenReturn(Optional.empty());

    String asked =
        assertThrows(NotFoundFault.class, () -> Corpus.theOneToAsk(documents, ID)).getMessage();
    String read =
        assertThrows(NotFoundFault.class, () -> Corpus.theOneToRead(documents, ID)).getMessage();

    assertEquals(2, Set.of(asked, read).size());
    assertTrue(asked.contains("/v1/documents/citations"), asked);
    assertTrue(read.contains("GET /v1/documents lists everything"), read);
  }

  @Test
  void a_chunk_the_corpus_does_not_hold_says_which_id_here_does_not_last() {
    when(documents.chunk(ID)).thenReturn(Optional.empty());

    String said =
        assertThrows(NotFoundFault.class, () -> Corpus.theChunk(documents, ID)).getMessage();

    assertTrue(said.contains("a re-ingest may not preserve"), said);
  }

  @Test
  void a_document_with_no_summary_vector_is_not_scored_as_zero() {
    // Zero is a real reading -- what a paper unrelated to both the claim
    // and its negation scores -- so an unembedded document answered with
    // one would be indistinguishable from a genuine result.
    when(retrieval.stance(ID, "the claim")).thenReturn(Optional.empty());

    String said =
        assertThrows(NotFoundFault.class, () -> Corpus.theStance(retrieval, ID, "the claim"))
            .getMessage();

    assertTrue(said.contains("there is no summary vector for document " + ID), said);
  }

  @Test
  void a_stance_that_can_be_read_comes_back_as_the_reading() {
    DocumentStore.Stance read = new DocumentStore.Stance(0.8, 0.2);
    when(retrieval.stance(ID, "the claim")).thenReturn(Optional.of(read));

    assertSame(read, Corpus.theStance(retrieval, ID, "the claim"));
  }

  private static DocumentStore.StoredDocument held() {
    return new DocumentStore.StoredDocument(
        ID,
        "paper.pdf",
        "A Paper",
        "hash",
        "text",
        12L,
        Instant.parse("2026-09-11T00:00:00Z"),
        "operator",
        "what it argues",
        null);
  }
}
