package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Outcome;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Service behaviour uses the summary repository contract, without starting a database. */
class SummariserParagraphTest {
  private final DocumentSummaries store = mock(DocumentSummaries.class);
  private final JobRuntime runtime = mock(JobRuntime.class);
  private final UUID document = UUID.randomUUID();
  private final UUID paragraph = UUID.randomUUID();
  private final UUID section = UUID.randomUUID();
  private final UUID chapter = UUID.randomUUID();
  private Summariser summariser;

  @BeforeEach
  void setUp() {
    Map<String, AgentDefinition> definitions =
        Stream.of(
                Summariser.PARAGRAPH,
                Summariser.SPAN,
                Summariser.SECTION,
                Summariser.CHAPTER,
                Summariser.DOCUMENT)
            .collect(
                Collectors.toMap(
                    name -> name,
                    name ->
                        new AgentDefinition(
                            name,
                            "summary",
                            "fast",
                            List.of(),
                            List.of(),
                            List.of(),
                            1,
                            1,
                            "Summarise the supplied text.")));
    summariser = new Summariser(store, runtime, () -> new AgentRegistry(definitions), 12);
    when(runtime.run(any(), anyString(), any(), any(), any(), isNull(), any(), any()))
        .thenReturn(new Outcome(Outcome.Ending.ANSWERED, "A retained claim.", 1, 1, ""));
    when(store.hierarchy(document))
        .thenReturn(
            List.of(
                new DocumentStore.StoredChapter(
                    chapter,
                    new StructuralRef.Synthetic(),
                    null,
                    List.of(
                        new DocumentStore.StoredSection(
                            section, new StructuralRef.Synthetic(), null)))));
    when(store.sectionParagraphSummaries(section)).thenReturn(List.of("Gemini Decision 1"));
  }

  @Test
  void short_text_is_retained_exactly_without_a_paragraph_call_or_child_log() {
    waiting("Gemini Decision 1");
    var result = summariser.summarise(document, "Tracker", Budget.of(20), () -> false);
    assertEquals(Outcome.Ending.ANSWERED, result.ending());
    verify(store).attachSummary(paragraph, "Gemini Decision 1");
    verify(runtime, never())
        .run(
            argThat(agent -> agent.name().equals(Summariser.PARAGRAPH)),
            anyString(),
            any(),
            any(),
            any(),
            isNull(),
            any(),
            any());
    assertEquals(3, result.modelCalls(), "Only section, chapter and document model calls remain");
  }

  @Test
  void exactly_eighty_words_still_uses_the_model() {
    String text = ParagraphPackingTest.words(80);
    waiting(text);
    summariser.summarise(document, "Text", Budget.of(20), () -> false);
    verify(runtime)
        .run(
            argThat(agent -> agent.name().equals(Summariser.PARAGRAPH)),
            eq(text),
            any(),
            any(),
            any(),
            isNull(),
            any(),
            any());
    verify(store).attachSummary(paragraph, "A retained claim.");
  }

  @Test
  void cancelled_work_does_not_write_a_literal_checkpoint() {
    waiting("4.85");
    assertEquals(
        Outcome.Ending.CANCELLED,
        summariser.summarise(document, "Rates", Budget.of(20), () -> true).ending());
    verify(store, never()).attachSummary(any(), any());
    verifyNoInteractions(runtime);
  }

  @Test
  void a_stale_revision_writer_refuses_a_literal_checkpoint() {
    waiting("4.85");
    doThrow(new IllegalStateException("stale revision"))
        .when(store)
        .attachSummary(paragraph, "4.85");
    assertThrows(
        IllegalStateException.class,
        () -> summariser.summarise(document, "Rates", Budget.of(20), () -> false));
    verify(store, never()).attachSectionSummary(any(), any());
    verifyNoInteractions(runtime);
  }

  @Test
  void checkpoint_failure_stops_without_spending_a_model_call() {
    waiting("4.85");
    doThrow(new IllegalStateException("checkpoint refused"))
        .when(store)
        .attachSummary(paragraph, "4.85");
    assertThrows(
        IllegalStateException.class,
        () -> summariser.summarise(document, "Rates", Budget.of(20), () -> false));
    verifyNoInteractions(runtime);
  }

  @Test
  void resuming_completed_work_does_not_rewrite_or_call_a_model() {
    when(store.unsummarised(document)).thenReturn(List.of());
    when(store.documentSummary(document)).thenReturn("Already complete.");
    assertEquals(
        0, summariser.summarise(document, "Rates", Budget.of(20), () -> false).modelCalls());
    verify(store, never()).attachSummary(any(), any());
    verifyNoInteractions(runtime);
  }

  private void waiting(String text) {
    when(store.unsummarised(document))
        .thenReturn(List.of(new DocumentStore.UnsummarisedParagraph(paragraph, 1, text)));
  }
}
