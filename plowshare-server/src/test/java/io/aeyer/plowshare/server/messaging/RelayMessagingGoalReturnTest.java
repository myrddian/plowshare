package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.SendMessageTool;
import io.aeyer.plowshare.server.agents.Transcript;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.messaging.MessageService.GoalAnswerClosed;
import io.aeyer.plowshare.server.messaging.MessageService.GoalAnswerPending;
import io.aeyer.plowshare.server.messaging.MessageService.GoalAnswerReady;
import io.aeyer.plowshare.server.messaging.MessageService.GoalReturn;
import io.aeyer.plowshare.server.messaging.MessageService.GoalReturnKind;
import io.aeyer.plowshare.server.messaging.MessageService.GoalReturnPublished;
import io.aeyer.plowshare.server.messaging.MessageService.GoalReturnUnavailable;
import io.aeyer.plowshare.server.messaging.MessageService.Instance;
import io.aeyer.plowshare.server.messaging.MessageService.Route;
import io.aeyer.plowshare.server.messaging.MessagingRepository.DeliveryState;
import io.aeyer.plowshare.server.messaging.MessagingRepository.GoalReplyPage;
import io.aeyer.plowshare.server.messaging.MessagingRepository.GoalReturnReply;
import io.aeyer.plowshare.server.messaging.MessagingRepository.PriorSend;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelayMessagingGoalReturnTest {
  private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
  private static final Home HOME = Home.of("project");
  private final Instance caller =
      new Instance(
          "ins_Caller", "owner", HOME.project(), "caller", "cnv_Caller", "persistent", true);
  private final Instance goal =
      new Instance("ins_Goal", "owner", HOME.project(), "goal", "cnv_Goal", "persistent", true);
  private final Instance originalSender =
      new Instance("ins_Source", "owner", HOME.project(), "source", "cnv_Source", "caller", true);
  private final Route original =
      new Route(
          "bdm_Source", originalSender.id(), caller.id(), null, false, false, false, null, false);
  private final Map<String, Route> routes = new HashMap<>();
  private final Map<String, MessageRecord> messages = new HashMap<>();
  private final Map<String, PriorSend> priorSends = new HashMap<>();
  private final MessagingRepository repository = mock(MessagingRepository.class);
  private final ConversationStore conversations = mock(ConversationStore.class);
  private final MessageInboxes inboxes = mock(MessageInboxes.class);
  private final GoalMessageInbox goalInbox = mock(GoalMessageInbox.class);
  private final AgentDefinition definition = mock(AgentDefinition.class);
  private final RelayMessaging messaging =
      new RelayMessaging(
          repository,
          mock(AllowanceRepository.class),
          conversations,
          mock(FiringStore.class),
          inboxes,
          UnitOfWork.NONE,
          mock(MessageService.Voice.class),
          (name, project) -> definition,
          ignored -> {},
          () -> NOW);

  @BeforeEach
  void storage() {
    messaging.useGoalInbox(goalInbox);
    routes.put(original.message(), original);
    for (Instance instance : List.of(caller, goal, originalSender)) {
      when(repository.instance(instance.id())).thenReturn(Optional.of(instance));
    }
    when(definition.name()).thenReturn(caller.agent());
    when(definition.bot()).thenReturn(true);
    when(definition.tools()).thenReturn(List.of(SendMessageTool.NAME));
    when(conversations.ownerOf(caller.conversation())).thenReturn(Optional.of(caller.account()));
    when(conversations.find(caller.conversation()))
        .thenReturn(
            Optional.of(
                new ConversationRecord(
                    caller.conversation(),
                    HOME,
                    Origin.MESSAGE,
                    ConversationLifecycle.ACTIVE,
                    null,
                    caller.agent(),
                    NOW,
                    null,
                    null,
                    null,
                    null)));
    when(repository.byConversationAgent(caller.conversation(), caller.agent()))
        .thenReturn(List.of(caller));
    when(repository.route(anyString()))
        .thenAnswer(call -> Optional.ofNullable(routes.get(call.getArgument(0))));
    when(repository.message(anyString()))
        .thenAnswer(call -> Optional.ofNullable(messages.get(call.getArgument(0))));
    when(repository.priorSend(anyString(), anyString()))
        .thenAnswer(
            call ->
                Optional.ofNullable(
                    priorSends.get(call.getArgument(0) + ":" + call.getArgument(1))));
    when(repository.finalReply(anyString()))
        .thenAnswer(
            call ->
                routes.values().stream()
                    .filter(
                        route -> route.finalReply() && call.getArgument(0).equals(route.replyTo()))
                    .map(Route::message)
                    .findFirst());
    when(repository.deliveryState(anyString())).thenReturn(new DeliveryState(false, null, null));
    when(repository.insertMessage(anyString(), anyString(), any()))
        .thenAnswer(
            call -> {
              var record =
                  new MessageRecord(
                      "bdm_Native" + messages.size(),
                      call.getArgument(0),
                      call.getArgument(1),
                      NOW);
              messages.put(record.id(), record);
              return record;
            });
    doAnswer(
            call -> {
              Route route = call.getArgument(0);
              routes.put(route.message(), route);
              String identity = call.getArgument(1);
              if (identity != null)
                priorSends.put(
                    route.sender() + ":" + identity,
                    new PriorSend(route.message(), call.getArgument(2)));
              return null;
            })
        .when(repository)
        .insertRoute(any(), any(), anyString(), anyInt(), anyBoolean());
    doAnswer(
            call -> {
              var route = routes.get(call.getArgument(0));
              Ending ending = call.getArgument(1);
              settle(route, route.ending() == null ? ending.name() : route.ending());
              return null;
            })
        .when(repository)
        .endRequest(anyString(), any());
    doAnswer(
            call -> {
              var route = routes.get(call.getArgument(0));
              settle(route, route.ending());
              return null;
            })
        .when(repository)
        .handled(anyString());
  }

  private void settle(Route route, String ending) {
    routes.put(
        route.message(),
        new Route(
            route.message(),
            route.sender(),
            route.recipient(),
            route.replyTo(),
            route.replyExpected(),
            route.finalReply(),
            route.generated(),
            ending,
            true,
            route.causation(),
            route.goalPurpose()));
  }

  private GoalReturn open(UUID invocation) {
    return messaging.openGoalReturn(
        caller.account(),
        caller.project(),
        caller.conversation(),
        definition,
        original.message(),
        goal.id(),
        invocation);
  }

  private String question(GoalReturn binding) {
    return assertInstanceOf(
            GoalReturnPublished.class,
            messaging.publishGoalReturn(
                binding,
                UUID.randomUUID(),
                "Choose a time.",
                GoalReturnKind.QUESTION,
                ignored -> {}))
        .message();
  }

  @Test
  void return_and_retry_use_one_final_native_reply_and_never_dispatch() {
    var invocation = UUID.randomUUID();
    var binding = open(invocation);
    assertEquals(binding, open(invocation));
    assertFalse(routes.get(binding.message()).handled());
    assertTrue(routes.get(binding.message()).replyExpected());
    var intent = UUID.randomUUID();
    var published =
        assertInstanceOf(
            GoalReturnPublished.class,
            messaging.publishGoalReturn(
                binding,
                intent,
                "Done.",
                GoalReturnKind.RESULT,
                message -> {
                  assertTrue(routes.get(binding.message()).handled());
                  assertEquals(message, repository.finalReply(binding.message()).orElseThrow());
                }));
    assertEquals(
        published,
        messaging.publishGoalReturn(
            binding, intent, "Done.", GoalReturnKind.RESULT, ignored -> {}));
    var delivery = messaging.delivery(published.message(), caller.account());
    assertTrue(delivery.finalReply());
    assertEquals(binding.message(), delivery.replyTo());
    assertEquals("Done.", delivery.body());
    assertThrows(MessageRefused.class, () -> messaging.delivery(published.message(), "other"));
    verifyNoInteractions(inboxes, goalInbox);
  }

  @Test
  void an_explicit_blocker_preserves_the_final_reply_after_the_goal_resumes() {
    var binding = open(UUID.randomUUID());
    var blocked =
        assertInstanceOf(
            GoalReturnPublished.class,
            messaging.publishGoalReturn(
                binding,
                UUID.randomUUID(),
                "The Goal needs access.",
                GoalReturnKind.BLOCKED,
                ignored -> {}));
    assertFalse(routes.get(binding.message()).handled());
    assertFalse(routes.get(blocked.message()).finalReply());
    assertEquals(binding.message(), routes.get(blocked.message()).replyTo());
    var complete =
        assertInstanceOf(
            GoalReturnPublished.class,
            messaging.publishGoalReturn(
                binding,
                UUID.randomUUID(),
                "The Goal verified the result.",
                GoalReturnKind.RESULT,
                ignored -> {}));
    assertEquals(Optional.of(complete.message()), repository.finalReply(binding.message()));
  }

  @Test
  void closing_a_launch_fences_both_answer_intake_and_continuation() {
    var binding = open(UUID.randomUUID());
    var question = question(binding);
    var context =
        new RunExtras.Context(
            definition,
            caller.conversation(),
            "session",
            mock(Transcript.class),
            HOME,
            caller.account());
    settle(routes.get(binding.message()), Ending.CANCELLED.name());
    var refused =
        messaging.send(
            context,
            new SendMessageTool.Request(
                null, "At noon.", question, false, true, "persistent", null),
            HOME);
    assertTrue(refused.contains("no longer open"));
    assertInstanceOf(GoalAnswerClosed.class, messaging.goalAnswer(binding, question));
    assertFalse(
        messaging.withGoalAnswer(
            binding,
            question,
            "bdm_Answer",
            () -> {
              throw new AssertionError("closed request authorized a continuation");
            }));
    verifyNoInteractions(goalInbox, inboxes);
  }

  @Test
  void only_the_exact_final_reverse_reply_can_resolve_a_question() {
    var binding = open(UUID.randomUUID());
    var question = question(binding);
    assertInstanceOf(GoalAnswerPending.class, messaging.goalAnswer(binding, question));
    var context =
        new RunExtras.Context(
            definition,
            caller.conversation(),
            "session",
            mock(Transcript.class),
            HOME,
            caller.account());
    var partial =
        messaging.send(
            context,
            new SendMessageTool.Request(
                null, "Partial.", question, false, false, "persistent", null),
            HOME);
    assertTrue(partial.contains("Use a final reply"));
    assertInstanceOf(GoalAnswerPending.class, messaging.goalAnswer(binding, question));
    verifyNoInteractions(goalInbox);
    when(goalInbox.stageGoalAnswer(any(), any())).thenReturn(true);
    messaging.send(
        context,
        new SendMessageTool.Request(null, "At noon.", question, false, true, "persistent", null),
        HOME);
    var answer = assertInstanceOf(GoalAnswerReady.class, messaging.goalAnswer(binding, question));
    assertEquals("At noon.", answer.message().body());
    assertInstanceOf(GoalAnswerClosed.class, messaging.goalAnswer(binding, original.message()));
    verify(goalInbox, never()).stage(any(), anyString());
    verifyNoInteractions(inboxes);
  }

  @Test
  void revoked_caller_and_closed_question_leave_no_pending_native_wake() {
    var binding = open(UUID.randomUUID());
    var question = question(binding);
    messaging.settleGoalQuestion(binding, question);
    assertInstanceOf(GoalAnswerClosed.class, messaging.goalAnswer(binding, question));
    when(definition.tools()).thenReturn(List.of());
    assertInstanceOf(
        GoalReturnUnavailable.class,
        messaging.publishGoalReturn(
            binding, UUID.randomUUID(), "Done.", GoalReturnKind.RESULT, ignored -> {}));
    verifyNoInteractions(inboxes, goalInbox);
  }

  @Test
  void definitive_withholding_closes_the_launch_without_disclosing_a_result() {
    var binding = open(UUID.randomUUID());
    messaging.withholdGoalReturn(
        binding,
        () -> assertEquals(Ending.UNAVAILABLE.name(), routes.get(binding.message()).ending()));
    assertTrue(routes.get(binding.message()).handled());
    assertTrue(repository.finalReply(binding.message()).isEmpty());
    verifyNoInteractions(inboxes, goalInbox);
  }

  @Test
  void launching_conversation_must_be_the_retained_original_recipient() {
    assertThrows(
        MessageRefused.class,
        () ->
            messaging.openGoalReturn(
                caller.account(),
                caller.project(),
                goal.conversation(),
                definition,
                original.message(),
                goal.id(),
                UUID.randomUUID()));
    assertTrue(messages.isEmpty());
  }

  @Test
  void live_bound_reader_inherits_exact_goal_log_and_refuses_a_same_account_sibling()
      throws Exception {
    var binding = open(UUID.randomUUID());
    var question = question(binding);
    var information = mock(InformationJobs.class);
    var extras = new MessagingRunExtras(messaging, conversations, () -> null, () -> information);
    var sibling =
        new Instance(
            "ins_Sibling",
            caller.account(),
            HOME.project(),
            caller.agent(),
            "cnv_Sibling",
            "persistent",
            true);
    when(repository.instance(sibling.id())).thenReturn(Optional.of(sibling));
    when(repository.byConversationAgent(sibling.conversation(), caller.agent()))
        .thenReturn(List.of(sibling));
    when(conversations.ownerOf(sibling.conversation())).thenReturn(Optional.of(caller.account()));
    when(conversations.find(sibling.conversation()))
        .thenReturn(
            Optional.of(
                new ConversationRecord(
                    sibling.conversation(),
                    HOME,
                    Origin.MESSAGE,
                    ConversationLifecycle.ACTIVE,
                    null,
                    sibling.agent(),
                    NOW,
                    null,
                    null,
                    null,
                    null)));
    when(repository.goalReturnReplies(binding, null, GoalReturnReadTool.PAGE_SIZE + 1))
        .thenReturn(
            Optional.of(
                new GoalReplyPage(
                    false,
                    null,
                    List.of(
                        new GoalReturnReply(
                            question,
                            binding.message(),
                            GoalReturnKind.QUESTION,
                            false,
                            true,
                            null,
                            "Choose a time.")))));

    var arguments = "{\"message\":\"" + binding.message() + "\"}";
    var reader = returnReader(extras, caller);
    var body = new ObjectMapper().readTree(reader.run(arguments, HOME));
    assertEquals(binding.message(), body.path("message").textValue());
    assertEquals("awaiting", body.path("state").textValue());
    var reply = body.path("replies").get(0);
    assertEquals(question, reply.path("id").textValue());
    assertEquals(binding.message(), reply.path("reply_to").textValue());
    assertEquals("QUESTION", reply.path("kind").textValue());
    assertTrue(reply.path("reply_expected").booleanValue());
    assertEquals("Choose a time.", reply.path("body").textValue());
    assertTrue(returnReader(extras, sibling).run(arguments, HOME).contains("MESSAGE_REFUSED"));
    verify(repository).goalReturnReplies(binding, null, GoalReturnReadTool.PAGE_SIZE + 1);
    verify(information).inherit(goal.conversation(), caller.conversation(), caller.account());

    doThrow(new NotFoundFault("Goal inputs became unavailable"))
        .when(information)
        .inherit(goal.conversation(), caller.conversation(), caller.account());
    var withheld = reader.run(arguments, HOME);
    assertTrue(withheld.contains("MESSAGE_REFUSED"));
    assertFalse(withheld.contains("Choose a time."));
    assertTrue(
        reader
            .run("{\"message\":\"" + question + "\",\"caller\":\"ins_Sibling\"}", HOME)
            .contains("INVALID_ARGUMENT"));
    verifyNoInteractions(inboxes, goalInbox);
  }

  private AgentTool returnReader(MessagingRunExtras extras, Instance source) {
    var context =
        new RunExtras.Context(
            definition,
            source.conversation(),
            "session",
            mock(Transcript.class),
            HOME,
            source.account());
    return extras.forRun(context).tools().stream()
        .filter(tool -> tool.schema().name().equals("goal_return_read"))
        .findFirst()
        .orElseThrow();
  }
}
