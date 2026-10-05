package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.information.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InformationToolTest {
  private static final UUID REVISION = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant INGESTED = Instant.parse("2026-10-02T07:59:00.123456Z");
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final InformationAccess access = mock(InformationAccess.class);
  private final InformationJobs inputs = mock(InformationJobs.class);
  private final InformationCatalogue catalogue = mock(InformationCatalogue.class);
  private final RetrievalService retrieval = mock(RetrievalService.class);
  private final InformationContext context =
      new InformationContext("alice", InformationContext.Selection.personal());
  private final List<UUID> read = new ArrayList<>();

  private InformationTool reader() {
    when(access.forRun("alice", Home.global())).thenReturn(context);
    when(inputs.reads("cnv_reader", context)).thenReturn(read::add);
    when(retrieval.scoped(access, context)).thenReturn(retrieval);
    return new InformationTool(false, () -> catalogue)
        .withRetrieval(retrieval)
        .forRun(access, inputs, "alice", "cnv_reader");
  }

  @Test
  void rank_serializes_real_document_timestamps_and_retains_input_dependencies() throws Exception {
    var document =
        new DocumentStore.StoredDocument(
            REVISION,
            "paper.pdf",
            "A paper",
            "hash",
            "textHash",
            12,
            INGESTED,
            "alice",
            "summary",
            null);
    when(retrieval.rank("UFO sightings", 10))
        .thenReturn(
            new RetrievalService.Ranking(
                List.of(new DocumentStore.Ranked(document, .25)), new DocumentStore.Ranking(1, 2)));
    String result =
        reader()
            .run(
                "{\"operation\":\"rank\",\"query\":\"UFO sightings\",\"limit\":10}", Home.global());
    assertTrue(result.startsWith("{"), result);
    var ranked = json.readTree(result);
    assertEquals(
        REVISION.toString(), ranked.path("documents").get(0).path("document").path("id").asText());
    assertEquals(
        INGESTED.toString(),
        ranked.path("documents").get(0).path("document").path("ingestedAt").asText());
    assertEquals(.25, ranked.path("documents").get(0).path("distance").asDouble());
    assertEquals(2, ranked.path("corpus").path("unranked").asInt());
    assertEquals(List.of(REVISION), read);
    verify(inputs, times(2)).requireLog("cnv_reader", "alice");
  }

  @Test
  void search_reuses_generated_summaries_without_replacing_exact_evidence() throws Exception {
    var chunk =
        new DocumentStore.ChunkWithAncestors(
            UUID.randomUUID(),
            "Exact source quotation.",
            UUID.randomUUID(),
            2,
            "Prepared paragraph summary",
            new DocumentStore.Placement.InNoSection(),
            REVISION,
            "paper.pdf",
            "A paper",
            "Prepared document summary");
    when(retrieval.retrieve("query", REVISION, 3))
        .thenReturn(List.of(new DocumentStore.Retrieved(chunk, .2)));
    when(catalogue.locateWindow(context, REVISION, chunk.chunkText()))
        .thenReturn(
            new io.aeyer.plowshare.protocol.Information.Window(
                REVISION,
                true,
                null,
                100,
                100 + chunk.chunkText().length(),
                500,
                chunk.chunkText(),
                "document",
                "text"));
    var tool = reader();
    var result =
        json.readTree(
            tool.run(
                "{\"operation\":\"search\",\"query\":\"query\",\"revision\":\"" + REVISION + "\"}",
                Home.global()));
    var hit = result.get(0);
    assertEquals(chunk.chunkText(), hit.path("text").asText());
    assertEquals(100, hit.path("start").asInt());
    assertEquals(
        "Prepared paragraph summary", hit.path("context").path("paragraph_summary").asText());
    assertEquals(
        "Prepared document summary", hit.path("context").path("document_summary").asText());
    assertTrue(hit.path("context").path("section").isNull());
    assertEquals(List.of(REVISION), read);
    verify(catalogue).locateWindow(context, REVISION, chunk.chunkText());
  }

  @Test
  void catalogue_and_processing_status_serialize_temporal_values() throws Exception {
    var revision = revision(List.of());
    when(catalogue.list(context, 20, 0)).thenReturn(List.of(revision));
    when(catalogue.status(context, REVISION)).thenReturn(revision);
    var tool = reader();
    var listed = json.readTree(tool.run("{\"operation\":\"list\"}", Home.global()));
    assertEquals(INGESTED.toString(), listed.get(0).path("created_at").asText());
    var status =
        json.readTree(
            tool.run(
                "{\"operation\":\"status\",\"revision\":\"" + REVISION + "\"}", Home.global()));
    assertEquals(INGESTED.toString(), status.path("created_at").asText());
    verify(catalogue).requireReadable(context, REVISION);
    assertEquals(List.of(REVISION, REVISION), read);
  }

  @Test
  void readiness_fence_binds_the_run_account_and_retains_ready_input_dependencies()
      throws Exception {
    var step =
        json.convertValue(
            Map.of("generation", 1, "stage", "extract", "state", "ready"),
            io.aeyer.plowshare.protocol.Information.Step.class);
    var revision = revision(List.of(step));
    when(catalogue.status(context, REVISION)).thenReturn(revision);
    var tool = reader();
    var result =
        json.readTree(
            tool.run(
                "{\"operation\":\"await\",\"sources\":[{\"revision\":\""
                    + REVISION
                    + "\"}],\"waitMs\":0}",
                Home.global()));
    assertTrue(result.path("complete").asBoolean());
    assertEquals(1, result.path("ready").asInt());
    assertEquals(List.of(REVISION), read);
    verify(catalogue).requireReadable(context, REVISION);
  }

  @Test
  void code_navigation_uses_run_identity_and_retains_symbol_input_dependencies() throws Exception {
    var code =
        context.withCorpus(io.aeyer.plowshare.server.information.InformationContext.Corpus.CODE);
    var tool = reader();
    when(inputs.reads("cnv_reader", code)).thenReturn(read::add);
    var projection =
        new io.aeyer.plowshare.protocol.Information.Outline(
            REVISION,
            "typescript",
            "extracted-text:utf16",
            "retained",
            "source",
            0,
            "ready",
            null,
            "hash",
            "parser",
            0,
            List.of(),
            false);
    when(catalogue.outline(code, REVISION, 0, 50)).thenReturn(projection);
    var symbol =
        json.convertValue(
            Map.of(
                "revision",
                REVISION,
                "name",
                "load",
                "kind",
                "function",
                "start_line",
                1,
                "end_line",
                1,
                "start_offset",
                0,
                "end_offset",
                10),
            io.aeyer.plowshare.protocol.Information.Symbol.class);
    when(catalogue.symbols(code, "load", null, 0, 20))
        .thenReturn(
            new io.aeyer.plowshare.protocol.Information.Symbols(
                "load", 0, false, List.of(symbol), "extracted-text:utf16", "retained", "source"));
    var outline =
        json.readTree(
            tool.run(
                "{\"operation\":\"outline\",\"corpus\":\"code\",\"revision\":\""
                    + REVISION
                    + "\",\"owner\":\"bob\"}",
                Home.global()));
    assertEquals("ready", outline.path("status").asText());
    var matches =
        json.readTree(
            tool.run(
                "{\"operation\":\"symbols\",\"corpus\":\"code\",\"query\":\"load\"}",
                Home.global()));
    assertEquals("load", matches.path("symbols").get(0).path("name").asText());
    assertEquals(List.of(REVISION, REVISION), read);
    verify(catalogue).outline(code, REVISION, 0, 50);
    verifyNoInteractions(retrieval);
  }

  private io.aeyer.plowshare.protocol.Information.Revision revision(
      List<io.aeyer.plowshare.protocol.Information.Step> steps) {
    return json.convertValue(
        Map.ofEntries(
            Map.entry("id", REVISION),
            Map.entry("resource_id", REVISION),
            Map.entry("ordinal", 1),
            Map.entry("generation", 1),
            Map.entry("allowance_total", 10),
            Map.entry("created_at", INGESTED),
            Map.entry("source_name", "paper.pdf"),
            Map.entry("title", "A paper"),
            Map.entry("kind", "source"),
            Map.entry("document_type", "document"),
            Map.entry("document_subtype", "text"),
            Map.entry("availability", "active"),
            Map.entry("tags", List.of()),
            Map.entry("steps", steps)),
        io.aeyer.plowshare.protocol.Information.Revision.class);
  }
}
