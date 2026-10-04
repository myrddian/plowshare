package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryPage;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The diagnostic read: its evidence, its bounds and its project fence. */
class ConversationTrajectoryToolTest {

  private static final Home LEDGER = Home.of("ledger");
  private static final String ID = "cnv_1";

  private ConversationStore conversations;
  private EntryStore entries;
  private ObjectMapper json;
  private ConversationTrajectoryTool tool;

  @BeforeEach
  void setUp() {
    conversations = mock(ConversationStore.class);
    entries = mock(EntryStore.class);
    json = new ObjectMapper().findAndRegisterModules();
    tool = new ConversationTrajectoryTool(conversations, entries);
  }

  @Test
  void the_schema_names_the_bounded_paged_log_read() {
    assertEquals(ConversationTrajectoryTool.NAME, tool.schema().name());
    assertEquals(List.of("conversation"), tool.schema().parameters().get("required"));
    @SuppressWarnings("unchecked")
    var properties = (java.util.Map<String, Object>) tool.schema().parameters().get("properties");
    assertEquals(
        List.of("conversation", "handle", "ordinal", "tail", "offset", "limit"),
        properties.keySet().stream().toList());
    @SuppressWarnings("unchecked")
    var limit = (java.util.Map<String, Object>) properties.get("limit");
    assertEquals(ConversationTrajectoryTool.MOST_ENTRIES, limit.get("maximum"));
  }

  @Test
  void a_page_is_the_whole_log_shape_and_the_limit_is_capped() throws Exception {
    when(conversations.find(ID)).thenReturn(Optional.of(conversation(ID, LEDGER)));
    EntryPage page =
        new EntryPage(
            List.of(
                new EntryPage.Row(
                    7,
                    2,
                    EntryKind.ANSWER,
                    "",
                    0,
                    null,
                    null,
                    null,
                    List.of(new EntryPage.Asked("call_1", "run", "{\"cmd\":\"pwd\"}", 13)),
                    null,
                    Instant.parse("2026-09-16T00:00:00Z"),
                    14L,
                    "primary",
                    "gpt-oss-120b",
                    "called_tools")),
            42);
    when(entries.pageOfLog(ID, 3, ConversationTrajectoryTool.MOST_ENTRIES)).thenReturn(page);

    JsonNode result =
        json.readTree(tool.run("{\"conversation\":\"cnv_1\",\"offset\":3,\"limit\":999}", LEDGER));

    assertEquals(42, result.path("total").asInt());
    assertEquals(3, result.path("offset").asInt());
    assertEquals(ConversationTrajectoryTool.MOST_ENTRIES, result.path("limit").asInt());
    JsonNode row = result.path("entries").get(0);
    assertEquals("answer", row.path("kind").asText());
    assertEquals("run", row.path("toolCalls").get(0).path("name").asText());
    assertEquals("gpt-oss-120b", row.path("wireModel").asText());
    assertEquals("called_tools", row.path("completion").asText());
  }

  @Test
  void tail_jumps_to_the_final_page_without_the_model_knowing_an_ordinal() throws Exception {
    when(conversations.find(ID)).thenReturn(Optional.of(conversation(ID, LEDGER)));
    when(entries.pageOfLog(ID, 0, 1)).thenReturn(new EntryPage(List.of(row(1)), 87));
    when(entries.pageOfLog(ID, 67, ConversationTrajectoryTool.MOST_ENTRIES))
        .thenReturn(new EntryPage(List.of(row(87)), 87));

    JsonNode result =
        json.readTree(tool.run("{\"conversation\":\"cnv_1\",\"tail\":true,\"limit\":20}", LEDGER));

    assertEquals(87, result.path("total").asInt());
    assertEquals(67, result.path("offset").asInt());
    assertEquals(87, result.path("entries").get(0).path("ordinal").asInt());
  }

  @Test
  void a_conversation_in_another_project_is_indistinguishable_from_one_not_here() {
    when(conversations.find(ID))
        .thenReturn(Optional.of(conversation(ID, Home.of("elsewhere"))))
        .thenReturn(Optional.empty());

    String foreign = tool.run("{\"conversation\":\"cnv_1\"}", LEDGER);
    String missing = tool.run("{\"conversation\":\"cnv_1\"}", LEDGER);

    assertEquals(missing, foreign);
    assertTrue(foreign.contains("available"), foreign);
    assertFalse(foreign.contains("elsewhere"), foreign);
    verify(entries, never())
        .pageOfLog(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void a_handle_reads_the_exact_persisted_result_from_the_target_conversation() {
    UUID handle = UUID.fromString("6b1f0c2a-9d4e-4f18-8a71-2c5e0b7d3f96");
    when(conversations.find(ID)).thenReturn(Optional.of(conversation(ID, LEDGER)));
    when(entries.redeem(ID, handle))
        .thenReturn(
            Optional.of(
                new EntryRecord(
                    ID,
                    4,
                    EntryKind.TOOL_RESULT,
                    "the exact result including its tail",
                    "call_1",
                    List.of(),
                    null,
                    handle,
                    1,
                    Instant.EPOCH,
                    12L,
                    null,
                    null)));

    assertEquals(
        "the exact result including its tail",
        tool.run("{\"conversation\":\"cnv_1\",\"handle\":\"" + handle + "\"}", LEDGER));
    verify(entries, never())
        .pageOfLog(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void malformed_and_meaningless_windows_are_tool_results() {
    assertTrue(tool.run("not json", LEDGER).contains("not valid JSON"));
    assertTrue(
        tool.run("{\"conversation\":\"cnv_1\",\"offset\":-1}", LEDGER).contains("0 or later"));
    assertTrue(tool.run("{\"conversation\":\"cnv_1\",\"limit\":0}", LEDGER).contains("1 or more"));
    assertTrue(
        tool.run("{\"conversation\":\"cnv_1\",\"limit\":2.5}", LEDGER).contains("whole number"));
    assertTrue(
        tool.run("{\"conversation\":\"cnv_1\",\"tail\":\"yes\"}", LEDGER)
            .contains("true or false"));
  }

  private static ConversationRecord conversation(String id, Home home) {
    return new ConversationRecord(
        id,
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

  private static EntryPage.Row row(int ordinal) {
    return new EntryPage.Row(
        ordinal,
        1,
        EntryKind.ANSWER,
        "answer",
        6,
        null,
        null,
        null,
        List.of(),
        null,
        Instant.EPOCH,
        1L);
  }
}
