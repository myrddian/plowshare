package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InformationCodecTest {
  private static final String ID = "00000000-0000-0000-0000-000000000001";
  private static final String OTHER = "00000000-0000-0000-0000-000000000002";
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON = SdkJson.mapper();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"revision\":1}",
        "{\"revision\":\"wrong\"}",
        "{\"revision\":\"00000000-0000-0000-0000-000000000001\",\"offset\":1.5}",
        "{\"revision\":\"00000000-0000-0000-0000-000000000001\",\"offset\":\"0\"}",
        "{\"revision\":\"00000000-0000-0000-0000-000000000001\",\"limit\":32769}",
        "{\"revision\":\"00000000-0000-0000-0000-000000000001\",\"offset\":-1}",
        "{\"revision\":\"00000000-0000-0000-0000-000000000001\",\"owner\":\"someone\"}",
        "{\"revision\":\"00000000-0000-0000-0000-000000000001\",\"scope\":{\"kind\":\"personal\",\"includeShared\":\"false\"}}"
      })
  void malformed_input_is_rejected_before_becoming_a_command(String input) {
    assertThrows(IOException.class, () -> InformationCodec.decode("read", input));
  }

  @Test
  void selectors_tags_and_automatic_grouping_have_explicit_contracts() throws Exception {
    var request =
        (InformationRequest.Tags)
            InformationCodec.decode(
                "tags",
                JSON.writeValueAsString(
                    Map.of(
                        "revision",
                        ID,
                        "requestId",
                        OTHER,
                        "tags",
                        List.of("  MiXeD   Tag ", "mixed tag"))));
    assertEquals(List.of("mixed tag"), request.tags());
    assertThrows(UnsupportedOperationException.class, () -> request.tags().add("other"));
    var encoded = JSON.readTree(InformationCodec.encode(request));
    assertFalse(encoded.has("selection"));
    assertFalse(encoded.has("action"));
    assertEquals("personal", encoded.path("scope").path("kind").asText());
    assertThrows(
        IOException.class,
        () ->
            InformationCodec.decode(
                "tags.groups",
                JSON.writeValueAsString(Map.of("revision", ID, "requestId", OTHER))));
    var automatic =
        InformationCodec.decode(
            "tags.groups",
            "{\"revision\":\"" + ID + "\",\"requestId\":\"" + OTHER + "\",\"groups\":null}");
    assertTrue(JSON.readTree(InformationCodec.encode(automatic)).has("groups"));
    assertTrue(JSON.readTree(InformationCodec.encode(automatic)).get("groups").isNull());
  }

  @Test
  void report_metadata_is_validated_and_keeps_the_existing_flat_wire_shape() throws Exception {
    var report =
        (InformationRequest.ReportRecord)
            InformationCodec.decode(
                "record.report",
                JSON.writeValueAsString(
                    Map.of(
                        "requestId",
                        ID,
                        "name",
                        "Finding",
                        "text",
                        "Exact narrative.",
                        "objectives",
                        List.of("Check the claim"))));
    assertEquals(List.of("Check the claim"), report.details().objectives());
    assertTrue(report.details().findings().isEmpty());
    var encoded = JSON.readTree(InformationCodec.encode(report));
    assertTrue(encoded.has("objectives"));
    assertFalse(encoded.has("details"));
    assertThrows(
        IOException.class,
        () ->
            InformationCodec.decode(
                "record.report",
                JSON.writeValueAsString(
                    Map.of(
                        "requestId",
                        ID,
                        "name",
                        "Finding",
                        "text",
                        "Text",
                        "findings",
                        List.of(
                            Map.of(
                                "id",
                                "one",
                                "objective",
                                "absent",
                                "claim",
                                "claim",
                                "rationale",
                                "reason",
                                "verdict",
                                "holds"))))));
  }

  @Test
  void scoped_search_decodes_ancestry_lists_and_rejects_legacy_or_partial_results()
      throws Exception {
    var request = InformationCodec.decode("search", "{\"query\":\"question\",\"limit\":10}");
    String wire =
        """
        [{"chunk":{"chunkId":"00000000-0000-0000-0000-000000000003",
        "chunkText":"passage","paragraphId":"00000000-0000-0000-0000-000000000002",
        "paragraphOrdinal":1,"paragraphSummary":null,"placement":{
        "sectionId":"00000000-0000-0000-0000-000000000004",
        "sectionTitle":{"synthetic":true},"sectionSummary":null,
        "chapterId":"00000000-0000-0000-0000-000000000005",
        "chapterTitle":{"synthetic":false,"title":"Chapter"},"chapterSummary":null},
        "documentId":"00000000-0000-0000-0000-000000000001","sourceName":"paper.pdf",
        "documentTitle":"A paper","documentSummary":null},"distance":0.5}]
        """;
    var result = (InformationResponse.SearchResult) InformationCodec.response(request, wire);
    assertEquals("passage", result.value().getFirst().chunk().chunkText());
    assertTrue(result.value().getFirst().chunk().placement().sectionTitle().synthetic());
    assertEquals("Chapter", result.value().getFirst().chunk().placement().chapterTitle().title());
    assertThrows(UnsupportedOperationException.class, () -> result.value().clear());
    for (String invalid :
        List.of(
            "{\"query\":\"question\",\"document\":null,\"limit\":10,\"hits\":[]}",
            wire.replace("\"distance\":0.5", "\"distance\":\"near\""),
            wire.replace("\"paragraphOrdinal\":1", "\"paragraphOrdinal\":null"),
            wire.replace("\"synthetic\":true", "\"synthetic\":true,\"title\":\"invented\""),
            wire.replace("\"sectionId\":\"00000000-0000-0000-0000-000000000004\",", ""))) {
      assertThrows(IOException.class, () -> InformationCodec.response(request, invalid));
    }
    var empty = (InformationResponse.SearchResult) InformationCodec.response(request, "[]");
    assertTrue(empty.value().isEmpty());
  }

  @Test
  void response_primitives_and_identity_are_required_without_a_mutation_retry() throws Exception {
    var read = InformationCodec.decode("read", JSON.writeValueAsString(Map.of("revision", ID)));
    var window =
        "{\"revision\":\"" + ID + "\",\"start\":0,\"end\":4,\"total\":4,\"text\":\"Text\"}";
    var result = (InformationResponse.Window) InformationCodec.response(read, window);
    assertEquals("Text", result.value().text());
    assertEquals("Text", JSON.valueToTree(result).path("text").asText());
    assertThrows(
        IOException.class, () -> InformationCodec.response(read, window.replace(ID, OTHER)));
    var change =
        InformationCodec.decode(
            "finalise", JSON.writeValueAsString(Map.of("revision", ID, "requestId", OTHER)));
    assertThrows(IOException.class, () -> InformationCodec.response(change, "{}"));
    assertThrows(
        IOException.class, () -> InformationCodec.response(change, "{\"finalised\":false}"));
    assertThrows(
        IOException.class, () -> InformationCodec.response(change, "{\"finalised\":\"true\"}"));
    assertEquals(
        new InformationResponse.Finalised(true),
        InformationCodec.response(change, "{\"finalised\":true}"));
  }
}
