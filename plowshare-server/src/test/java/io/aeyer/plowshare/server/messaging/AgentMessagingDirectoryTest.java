package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.Transcript;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.messaging.MessageService.DefinitionAddress;
import io.aeyer.plowshare.server.messaging.MessageService.Instance;
import io.aeyer.plowshare.server.messaging.MessagingRepository.DefaultAvailability;
import io.aeyer.plowshare.server.messaging.MessagingRepository.InstanceListing;
import io.aeyer.plowshare.server.messaging.MessagingRepository.InstanceState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentMessagingDirectoryTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Home HOME = Home.of("source");
  private static final String ACCOUNT = "owner";
  private static final String CONVERSATION = "cnv_Sender";

  private final MessagingRepository repository = mock(MessagingRepository.class);
  private final ConversationStore conversations = mock(ConversationStore.class);
  private final MessageService.Definitions definitions = mock(MessageService.Definitions.class);
  private final AgentDefinition sender =
      new AgentDefinition(
          "sender", "sender", "model", List.of(), List.of(), List.of(), 4, 8, "Send");
  private final RunExtras.Context context =
      new RunExtras.Context(sender, CONVERSATION, null, Transcript.NONE, HOME, ACCOUNT);
  private final RelayMessaging messaging =
      new RelayMessaging(
          repository,
          mock(AllowanceRepository.class),
          conversations,
          mock(FiringStore.class),
          mock(MessageInboxes.class),
          UnitOfWork.NONE,
          mock(MessageService.Voice.class),
          definitions,
          ignored -> {},
          () -> Instant.EPOCH);

  @BeforeEach
  void sourceConversation() {
    when(conversations.ownerOf(CONVERSATION)).thenReturn(Optional.of(ACCOUNT));
    var row = mock(ConversationRecord.class);
    when(row.home()).thenReturn(HOME);
    when(conversations.find(CONVERSATION)).thenReturn(Optional.of(row));
  }

  @Test
  void definitionDiscoveryDoesNotCreateInstancesOrProbeIndividualRecipients() throws Exception {
    when(definitions.exported(ACCOUNT, HOME.project()))
        .thenReturn(
            List.of(
                new DefinitionAddress("analyst", "analyst"),
                new DefinitionAddress("reviewer", "reviewer")));
    JsonNode page = list("{\"limit\":1}");
    var first = page.path("items").get(0);
    assertEquals("analyst", first.path("definition").asText());
    assertEquals("analyst", first.path("to").asText());
    assertEquals("source", first.path("project").asText());
    assertTrue(first.path("messageable").asBoolean());
    assertEquals(1, page.path("next_offset").asInt());
    verify(definitions).exported(ACCOUNT, HOME.project());
    verifyNoMoreInteractions(definitions);
    verify(repository).byConversationAgent(CONVERSATION, "sender");
    verify(repository).activeInstances(ACCOUNT, HOME.project());
    verify(repository).defaultAvailability(ACCOUNT, HOME.project(), List.of("analyst"));
    verifyNoMoreInteractions(repository);
  }

  @Test
  void definition_discovery_reports_default_queue_and_available_instance_slots() throws Exception {
    when(definitions.exported(ACCOUNT, HOME.project()))
        .thenReturn(
            List.of(
                new DefinitionAddress("analyst", "analyst"),
                new DefinitionAddress("analyst", "analyst_alias"),
                new DefinitionAddress("reviewer", "reviewer")));
    when(repository.defaultAvailability(ACCOUNT, HOME.project(), List.of("analyst", "reviewer")))
        .thenReturn(Map.of("analyst", new DefaultAvailability(true, 256)));
    when(repository.activeInstances(ACCOUNT, HOME.project())).thenReturn(255);

    var items = list("{}").path("items");
    assertEquals("QUEUE_FULL", items.get(0).path("code").asText());
    assertEquals("analyst", items.get(1).path("definition").asText());
    assertEquals("analyst_alias", items.get(1).path("to").asText());
    assertEquals("QUEUE_FULL", items.get(1).path("code").asText());
    assertEquals("INSTANCE_LIMIT", items.get(2).path("code").asText());
    assertFalse(items.get(0).path("messageable").asBoolean());
    assertFalse(items.get(1).path("messageable").asBoolean());
    assertFalse(items.get(2).path("messageable").asBoolean());
    verify(repository).activeInstances(ACCOUNT, HOME.project());
  }

  @Test
  void deniedProjectIsWithheldAndUsesTheSameCodeAsSending() throws Exception {
    assertEquals("ROUTE_DENIED", list("{\"project\":\"other\"}").path("code").asText());
    verifyNoInteractions(definitions, repository);

    bindSender();
    var refused =
        JSON.readTree(
            messaging
                .tool(context)
                .run("{\"to_project\":\"other\",\"to\":\"analyst\",\"body\":\"Review\"}", HOME));
    assertEquals("ROUTE_DENIED", refused.path("code").asText());
    assertFalse(refused.has("message"));
    verifyNoInteractions(definitions);
  }

  @Test
  void instanceDiscoveryUsesVisibleOwnedPageAndReportsSendLifecycleCodes() throws Exception {
    var ended = listed("ended", false, 0);
    var full = listed("full", true, 256);
    when(repository.listing(ACCOUNT, HOME.project(), false, 0, 21))
        .thenReturn(List.of(listed("hidden", true, 0), ended, full, listed("ready", true, 0)));
    messaging.useLogVisibility((conversation, account) -> !conversation.equals("cnv_hidden"));

    var items = list("{\"kind\":\"instance\"}").path("items");
    assertEquals(3, items.size());
    assertEquals("INSTANCE_ENDED", items.get(0).path("code").asText());
    assertEquals("QUEUE_FULL", items.get(1).path("code").asText());
    assertEquals("ins_ready", items.get(2).path("to").asText());
    assertEquals("persistent", items.get(2).path("instance_kind").asText());
    assertTrue(items.get(2).path("messageable").asBoolean());
    assertFalse(items.get(1).path("messageable").asBoolean());
    verify(repository).byConversationAgent(CONVERSATION, "sender");
    verify(repository).activeInstances(ACCOUNT, HOME.project());
    verify(repository).listing(ACCOUNT, HOME.project(), false, 0, 21);
    verifyNoMoreInteractions(repository);

    bindSender();
    when(repository.instance("ins_ended")).thenReturn(Optional.of(ended.instance()));
    when(repository.instance("ins_full")).thenReturn(Optional.of(full.instance()));
    when(repository.pendingCount("ins_full")).thenReturn(256);
    for (int index = 0; index < 2; index++) {
      var candidate = items.get(index);
      String arguments = "{\"to\":\"" + candidate.path("to").asText() + "\",\"body\":\"Review\"}";
      var refused = JSON.readTree(messaging.tool(context).run(arguments, HOME));
      assertEquals(candidate.path("code"), refused.path("code"));
    }
  }

  @Test
  void malformedInputAndMissingSendGrantCannotEnumerateTheDirectory() throws Exception {
    for (String arguments :
        List.of(
            "{\"account\":\"another\"}", "{\"offset\":1.5}", "{\"kind\":null}", "{\"limit\":51}")) {
      assertEquals("INVALID_ARGUMENT", list(arguments).path("code").asText());
    }
    var refused = JSON.readTree(messaging.directory(context, false).run("{}", HOME));
    assertEquals("SEND_NOT_GRANTED", refused.path("code").asText());
    verifyNoInteractions(definitions, repository);
  }

  @Test
  void a_foreign_instance_is_indistinguishable_from_a_missing_one_on_send() throws Exception {
    bindSender();
    var foreign =
        new Instance(
            "ins_foreign",
            "someone-else",
            HOME.project(),
            "reviewer",
            "cnv_foreign",
            "persistent",
            true);
    when(repository.instance(foreign.id())).thenReturn(Optional.of(foreign));
    var send = messaging.tool(context);
    var missing = JSON.readTree(send.run("{\"to\":\"ins_missing\",\"body\":\"Review\"}", HOME));
    assertEquals("TARGET_UNAVAILABLE", missing.path("code").asText());
    assertEquals(
        missing, JSON.readTree(send.run("{\"to\":\"ins_foreign\",\"body\":\"Review\"}", HOME)));
  }

  private JsonNode list(String arguments) throws Exception {
    return JSON.readTree(messaging.directory(context, true).run(arguments, HOME));
  }

  private void bindSender() {
    var bound =
        new Instance("ins_Sender", ACCOUNT, HOME.project(), "sender", CONVERSATION, "caller", true);
    when(repository.byConversationAgent(CONVERSATION, "sender")).thenReturn(List.of(bound));
    when(repository.instance(bound.id())).thenReturn(Optional.of(bound));
  }

  private static InstanceListing listed(String suffix, boolean active, int pending) {
    var instance =
        new Instance(
            "ins_" + suffix,
            ACCOUNT,
            HOME.project(),
            "reviewer",
            "cnv_" + suffix,
            "persistent",
            active);
    return new InstanceListing(
        instance, new InstanceState(false, false, Instant.EPOCH), false, pending, Optional.empty());
  }
}
