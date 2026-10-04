package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.api.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Model reads preserve WS response evidence and cannot choose another run's home. */
class CapabilityReadToolsTest {
  private static final Home HOME = Home.of("ledger");
  private static final UUID DOCUMENT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID PARAGRAPH = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final ConversationStore conversations = mock(ConversationStore.class);
  private final EntryStore entries = mock(EntryStore.class);
  private final CitationStore citations = mock(CitationStore.class);
  private final RetrievalService retrieval = mock(RetrievalService.class);

  private com.fasterxml.jackson.databind.JsonNode tree(Object value) throws Exception {
    return json.readTree(json.writeValueAsString(value));
  }

  static ConversationRecord conversation(Home home) {
    return new ConversationRecord(
        "cnv_1",
        home,
        Origin.TURN,
        ConversationLifecycle.ACTIVE,
        null,
        null,
        Instant.EPOCH,
        null,
        Budget.of(5),
        null,
        "incident");
  }

  @Test
  void retrieval_keeps_paragraph_coordinates_and_all_ancestors() throws Exception {
    var chunk =
        new DocumentStore.ChunkWithAncestors(
            UUID.randomUUID(),
            "source data",
            PARAGRAPH,
            3,
            "the paragraph",
            new DocumentStore.Placement.InNoSection(),
            DOCUMENT,
            "paper.pdf",
            "A Paper",
            "summary");
    var rows = List.of(new DocumentStore.Retrieved(chunk, .25));
    when(retrieval.retrieve("claim", DOCUMENT, 10)).thenReturn(rows);
    String result =
        new RetrievalTools.Retrieve(retrieval)
            .run("{\"question\":\" claim \",\"document\":\"" + DOCUMENT + "\"}", HOME);
    assertEquals(tree(RetrieveResponse.of("claim", DOCUMENT, 10, rows)), json.readTree(result));
    verify(retrieval).retrieve("claim", DOCUMENT, 10);
  }

  @Test
  void ranking_keeps_coverage_even_when_no_document_is_rankable() throws Exception {
    var rows = new RetrievalService.Ranking(List.of(), new DocumentStore.Ranking(0, 7));
    when(retrieval.rank("claim", 20)).thenReturn(rows);
    var result =
        json.readTree(new RetrievalTools.Rank(retrieval).run("{\"question\":\"claim\"}", HOME));
    assertEquals(tree(DocumentRankingResponse.of("claim", 20, rows)), result);
    assertEquals(7, result.path("unranked").asInt());
  }

  @Test
  void outline_keeps_the_document_view_and_missing_is_not_an_empty_outline() throws Exception {
    DocumentStore documents = mock(DocumentStore.class);
    var document =
        new DocumentStore.StoredDocument(
            DOCUMENT,
            "paper.pdf",
            "A Paper",
            "hash",
            "textHash",
            12,
            Instant.EPOCH,
            "operator",
            "summary",
            null);
    when(documents.find(DOCUMENT)).thenReturn(Optional.of(document));
    var hierarchy =
        List.of(
            new DocumentStore.StoredChapter(
                UUID.randomUUID(),
                new StructuralRef.Synthetic(),
                null,
                List.of(
                    new DocumentStore.StoredSection(
                        UUID.randomUUID(),
                        new StructuralRef.Named("Real section"),
                        "section summary"))));
    when(documents.hierarchy(DOCUMENT)).thenReturn(hierarchy);
    var tool = new RetrievalTools.Outline(documents);
    assertEquals(
        tree(DocumentDetailResponse.of(document, hierarchy)),
        json.readTree(tool.run("{\"document\":\"" + DOCUMENT + "\"}", HOME)));
    when(documents.find(DOCUMENT)).thenReturn(Optional.empty());
    assertThrows(NotFoundFault.class, () -> tool.run("{\"document\":\"" + DOCUMENT + "\"}", HOME));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "1.5", "4294967297", "\"many\""})
  void malformed_retrieval_limits_never_reach_the_service(String limit) {
    String result =
        new RetrievalTools.Retrieve(retrieval)
            .run("{\"question\":\"claim\",\"limit\":" + limit + "}", HOME);
    assertFalse(result.startsWith("{"), result);
    verifyNoInteractions(retrieval);
  }

  @Test
  void citations_filter_by_run_home_and_preserve_stale_standing() throws Exception {
    var rows =
        List.of(
            new CitationStore.Cited(
                UUID.randomUUID(),
                null,
                DOCUMENT,
                "paper.pdf",
                3,
                "cnv_1",
                2,
                "librarian",
                Instant.EPOCH,
                null,
                "A Paper"));
    when(citations.inHome(HOME, DOCUMENT, 20)).thenReturn(rows);
    var result =
        json.readTree(
            new RetrievalTools.Citations(citations, conversations)
                .run("{\"document\":\"" + DOCUMENT + "\"}", HOME));
    assertEquals(tree(CitationsResponse.of("document", 20, rows)), result);
    verify(citations).inHome(HOME, DOCUMENT, 20);
    verify(citations, never()).recent(anyInt());
    verify(citations, never()).of(any(), anyInt());
  }

  @Test
  void citations_of_a_foreign_conversation_are_indistinguishable_from_missing() {
    when(conversations.find("cnv_1"))
        .thenReturn(Optional.of(conversation(Home.global())))
        .thenReturn(Optional.empty());
    var tool = new RetrievalTools.Citations(citations, conversations);
    assertEquals(
        tool.run("{\"conversation\":\"cnv_1\"}", HOME),
        tool.run("{\"conversation\":\"cnv_1\"}", HOME));
    verifyNoInteractions(citations);
  }

  @Test
  void citations_of_an_owned_conversation_keep_the_conversation_view() throws Exception {
    when(conversations.find("cnv_1")).thenReturn(Optional.of(conversation(HOME)));
    when(citations.madeIn("cnv_1", 4)).thenReturn(List.of());
    var tool = new RetrievalTools.Citations(citations, conversations);
    assertEquals(
        tree(CitationsResponse.of("conversation", 4, List.of())),
        json.readTree(tool.run("{\"conversation\":\"cnv_1\",\"limit\":4}", HOME)));
    assertTrue(
        tool.run("{\"conversation\":\"cnv_1\",\"document\":\"" + DOCUMENT + "\"}", HOME)
            .contains("not both"));
    assertTrue(tool.run("{\"project\":null}", HOME).contains("tier"));
    verify(citations, times(1)).madeIn(anyString(), anyInt());
  }

  @Test
  void lexical_search_keeps_hit_provenance_paging_and_retention_coverage() throws Exception {
    var rows =
        new LogSearch(
            List.of(
                new LogSearch.Hit(
                    "cnv_1",
                    7,
                    2,
                    EntryKind.TOOL_RESULT,
                    .4,
                    "a [claim]",
                    9000,
                    12,
                    PARAGRAPH,
                    Instant.EPOCH)),
            8,
            new LogSearch.Reach(50, 2, 3));
    var view = LogSearchView.of(rows, 3, 20);
    when(entries.searchView(HOME, "claim", 3, 20, "lexical", null)).thenReturn(view);
    var result =
        json.readTree(
            new ConversationSearchTool(entries)
                .run(
                    "{\"question\":\" claim \",\"mode\":\"lexical\",\"offset\":3,\"limit\":999}",
                    HOME));
    assertEquals(tree(view), result);
    verify(entries).searchView(HOME, "claim", 3, 20, "lexical", null);
  }

  @Test
  void search_defaults_are_shared_and_service_failure_is_not_zero_hits() throws Exception {
    var view = LogSearchView.of(LogSearch.NONE, 0, 10);
    when(entries.searchView(Home.global(), "claim", 0, 10, "hybrid", null)).thenReturn(view);
    var tool = new ConversationSearchTool(entries);
    assertEquals(tree(view), json.readTree(tool.run("{\"question\":\"claim\"}", Home.global())));
    when(entries.searchView(Home.global(), "claim", 0, 10, "hybrid", null))
        .thenThrow(new IllegalStateException("archive unavailable"));
    assertThrows(
        IllegalStateException.class, () -> tool.run("{\"question\":\"claim\"}", Home.global()));
    assertThrows(
        IllegalArgumentException.class,
        () -> tool.run("{\"question\":\"claim\",\"project\":null}", HOME));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"question\":\"claim\",\"offset\":-1}",
        "{\"question\":\"claim\",\"limit\":0}",
        "{\"question\":\"claim\",\"limit\":2.5}",
        "{\"question\":\"claim\",\"limit\":4294967297}",
        "{\"question\":\" \"}"
      })
  void invalid_search_arguments_do_not_query_the_archive(String arguments) {
    assertThrows(
        RuntimeException.class, () -> new ConversationSearchTool(entries).run(arguments, HOME));
    verifyNoInteractions(entries);
  }

  @Test
  void index_and_conversation_list_use_the_run_home() throws Exception {
    Archive archive = mock(Archive.class);
    when(archive.index(HOME)).thenReturn(List.of());
    when(conversations.inHome(eq(HOME), any())).thenReturn(List.of(conversation(HOME)));
    assertEquals(
        tree(List.of()), json.readTree(new ArchiveReadTools.Index(archive).run("{}", HOME)));
    assertEquals(
        tree(List.of(ConversationView.of(conversation(HOME)))),
        json.readTree(new ArchiveReadTools.Conversations(conversations).run("{}", HOME)));
    assertTrue(
        new ArchiveReadTools.Index(archive).run("{\"project\":null}", HOME).contains("tier"));
    verify(archive).index(HOME);
  }

  @Test
  void chat_checks_home_before_reading_and_preserves_the_projected_page() throws Exception {
    when(conversations.find("cnv_1"))
        .thenReturn(Optional.of(conversation(Home.global())))
        .thenReturn(Optional.of(conversation(HOME)));
    var tool = new ArchiveReadTools.Chat(conversations, entries);
    assertTrue(tool.run("{\"conversation\":\"cnv_1\"}", HOME).contains("available"));
    verifyNoInteractions(entries);
    var page = new EntryPage(List.of(), 21);
    when(entries.pageOfProjection("cnv_1", 3, 100)).thenReturn(page);
    assertEquals(
        tree(EntryPageView.of(page, 3, 100)),
        json.readTree(tool.run("{\"conversation\":\"cnv_1\",\"offset\":3,\"limit\":1000}", HOME)));
  }

  @Test
  void context_checks_home_before_turn_reads_and_prices_the_bound_session() throws Exception {
    TurnStore turns = mock(TurnStore.class);
    Conversations rules = mock(Conversations.class);
    ConversationContextTool.Pricing pricing = mock(ConversationContextTool.Pricing.class);
    when(conversations.find("cnv_1"))
        .thenReturn(Optional.of(conversation(Home.global())))
        .thenReturn(Optional.of(conversation(HOME)));
    when(turns.forConversation("cnv_1")).thenReturn(List.of());
    when(rules.whoAnswered("cnv_1", List.of())).thenReturn("reader");
    var tool =
        new ConversationContextTool(conversations, turns, () -> rules, pricing)
            .inSession("actual-session");
    assertTrue(tool.run("{\"conversation\":\"cnv_1\"}", HOME).contains("available"));
    verifyNoInteractions(turns, rules, pricing);
    assertEquals(
        tree(ContextView.of(List.of(), null)),
        json.readTree(tool.run("{\"conversation\":\"cnv_1\"}", HOME)));
    verify(pricing).price("cnv_1", "reader", "actual-session");
    assertTrue(
        tool.run("{\"conversation\":\"cnv_1\",\"session\":\"forged\"}", HOME).contains("omit"));
    verify(pricing, times(1)).price(anyString(), anyString(), anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"document_search", "document_retrieve", "document_citations"})
  void paragraph_evidence_tools_make_an_answer_eligible_for_citation_recording(String tool) {
    assertTrue(definition(List.of(tool)).canCite());
  }

  @Test
  void summaries_and_archive_reads_do_not_enable_citation_recording() {
    assertFalse(
        definition(List.of("document_rank", "document_outline", "conversation_search")).canCite());
  }

  static AgentDefinition definition(List<String> tools) {
    return new AgentDefinition(
        "reader", "reads", "fast", tools, List.of(), List.of(), 10, 20, "Read.");
  }
}
