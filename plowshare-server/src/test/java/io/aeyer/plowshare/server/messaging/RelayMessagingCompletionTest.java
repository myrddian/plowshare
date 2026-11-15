package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.messaging.MessageService.Instance;
import io.aeyer.plowshare.server.messaging.MessageService.Route;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Completion routing uses mocked storage; actual SQL constraints have a separate selected test. */
class RelayMessagingCompletionTest {
  private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
  private final MessagingRepository repository = mock(MessagingRepository.class);
  private final MessageInboxes inboxes = mock(MessageInboxes.class);
  private final Instance sender =
      new Instance("ins_Sender", "owner", "project", "sender", "cnv_Sender", "caller", true);
  private final Instance recipient =
      new Instance(
          "ins_Recipient", "owner", "project", "worker", "cnv_Recipient", "persistent", true);
  private final Map<String, Route> routes = new HashMap<>();
  private final List<MessageRecord> notices = new ArrayList<>();
  private final RelayMessaging messaging =
      new RelayMessaging(
          repository,
          mock(AllowanceRepository.class),
          mock(ConversationStore.class),
          mock(FiringStore.class),
          inboxes,
          UnitOfWork.NONE,
          mock(MessageService.Voice.class),
          (name, project) ->
              new AgentDefinition(
                  name, name, "model", List.of(), List.of(), List.of(), 4, 8, "Answer"),
          target -> {},
          () -> NOW);

  @BeforeEach
  void storage() {
    when(repository.instance(sender.id())).thenReturn(Optional.of(sender));
    when(repository.instance(recipient.id())).thenReturn(Optional.of(recipient));
    when(repository.route(anyString()))
        .thenAnswer(call -> Optional.ofNullable(routes.get(call.getArgument(0))));
    when(repository.finalReply(anyString()))
        .thenAnswer(
            call ->
                routes.values().stream()
                    .filter(
                        route -> call.getArgument(0).equals(route.replyTo()) && route.finalReply())
                    .map(Route::message)
                    .findFirst());
    when(repository.insertMessage(anyString(), anyString(), any()))
        .thenAnswer(
            call -> {
              MessageRecord record =
                  new MessageRecord(
                      "bdm_Notice" + notices.size(),
                      call.getArgument(0),
                      call.getArgument(1),
                      call.getArgument(2));
              notices.add(record);
              return record;
            });
    doAnswer(
            call -> {
              Route route = call.getArgument(0);
              routes.put(route.message(), route);
              return null;
            })
        .when(repository)
        .insertRoute(any(), any(), anyString(), anyInt(), anyBoolean());
    doAnswer(
            call -> {
              Route original = routes.get(call.getArgument(0));
              Ending ending = call.getArgument(1);
              routes.put(
                  original.message(),
                  new Route(
                      original.message(),
                      original.sender(),
                      original.recipient(),
                      original.replyTo(),
                      original.replyExpected(),
                      original.finalReply(),
                      original.generated(),
                      ending.name(),
                      true,
                      original.causation()));
              return null;
            })
        .when(repository)
        .endRequest(anyString(), any());
  }

  private Route request(boolean expected) {
    Route request =
        new Route(
            "bdm_Request", sender.id(), recipient.id(), null, expected, false, false, null, false);
    routes.put(request.message(), request);
    return request;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failed_recipient_notifies_sender_once_regardless_reply_expectation(boolean expected) {
    Route request = request(expected);
    Outcome failure =
        new Outcome(Ending.UNAVAILABLE, "SECRET partial output", 2, 3, "SECRET provider detail");
    messaging.complete(request.message(), failure);
    messaging.complete(request.message(), failure);

    assertEquals(1, notices.size());
    MessageRecord notice = notices.getFirst();
    assertEquals(sender.id(), notice.recipient());
    assertTrue(notice.body().contains("job had an error"));
    assertFalse(notice.body().contains("SECRET"));
    Route generated = routes.get(notice.id());
    assertTrue(generated.generated());
    assertTrue(generated.finalReply());
    assertFalse(generated.replyExpected());
    assertEquals("UNAVAILABLE", generated.ending());
    assertEquals(request.message(), generated.replyTo());
    assertTrue(routes.get(request.message()).handled());
    verify(inboxes).publish(eq(notice), eq(generated), eq(recipient), eq(sender), any(), any());
  }

  @ParameterizedTest
  @EnumSource(
      value = Ending.class,
      names = {"UNAVAILABLE", "SUB_AGENT_FAILED", "SESSION_GONE", "STUCK", "CALL_FAILURES"})
  void every_actual_job_error_has_a_generic_status_notice(Ending ending) {
    Route request = request(false);
    messaging.complete(
        request.message(), new Outcome(ending, "private output", 1, 1, "private detail"));
    assertEquals(1, notices.size());
    assertEquals(ending.name(), routes.get(notices.getFirst().id()).ending());
    assertFalse(notices.getFirst().body().contains("private"));
  }

  @ParameterizedTest
  @EnumSource(
      value = Ending.class,
      names = {"CANCELLED", "TURN_CAP", "CALL_BUDGET"})
  void non_error_one_way_outcomes_do_not_claim_the_recipient_had_an_error(Ending ending) {
    Route request = request(false);
    messaging.complete(request.message(), new Outcome(ending, "stopped", 1, 1, ""));
    assertTrue(notices.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void restart_recovery_notifies_once_without_replaying_handling(boolean expected) {
    Route request = request(expected);
    when(repository.interrupted()).thenReturn(List.of(request.message()));
    messaging.recover();
    messaging.recover();
    assertEquals(1, notices.size());
    assertEquals("UNAVAILABLE", routes.get(notices.getFirst().id()).ending());
    assertTrue(routes.get(request.message()).handled());
  }

  @Test
  void failure_after_explicit_final_reply_keeps_that_reply_and_appends_one_status_notice() {
    Route request = request(false);
    Route finalReply =
        new Route(
            "bdm_Explicit",
            recipient.id(),
            sender.id(),
            request.message(),
            false,
            true,
            false,
            null,
            false);
    routes.put(finalReply.message(), finalReply);
    messaging.complete(
        request.message(), new Outcome(Ending.UNAVAILABLE, "private", 2, 3, "private"));
    messaging.complete(
        request.message(), new Outcome(Ending.UNAVAILABLE, "private", 2, 3, "private"));

    assertEquals(1, notices.size());
    Route status = routes.get(notices.getFirst().id());
    assertFalse(status.finalReply());
    assertTrue(status.generated());
    assertEquals(finalReply.message(), messaging.finalReply(request.message()).orElseThrow());
    assertEquals("UNAVAILABLE", routes.get(request.message()).ending());
  }

  @Test
  void generated_nonfinal_status_cannot_request_a_reply_or_report_success() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Route(
                "bdm_Status",
                recipient.id(),
                sender.id(),
                "bdm_Request",
                true,
                false,
                true,
                "UNAVAILABLE",
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Route(
                "bdm_Status",
                recipient.id(),
                sender.id(),
                "bdm_Request",
                false,
                false,
                true,
                "ANSWERED",
                false));
  }

  @Test
  void successful_one_way_delivery_does_not_generate_a_reply() {
    Route request = request(false);
    messaging.complete(request.message(), new Outcome(Ending.ANSWERED, "done", 1, 1, ""));
    assertTrue(notices.isEmpty());
    verify(inboxes, never()).publish(any(), any(), any(), any(), any(), any());
  }

  @Test
  void failed_handling_of_generated_notice_does_not_create_an_error_ping_pong() {
    Route request = request(false);
    Route generated =
        new Route(
            "bdm_Generated",
            recipient.id(),
            sender.id(),
            request.message(),
            false,
            true,
            true,
            "UNAVAILABLE",
            false);
    routes.put(generated.message(), generated);
    messaging.complete(
        generated.message(), new Outcome(Ending.UNAVAILABLE, "another error", 1, 1, ""));
    assertTrue(notices.isEmpty());
    assertTrue(routes.get(generated.message()).handled());
  }

  @Test
  void revoked_participant_visibility_withholds_notice_but_keeps_terminal_failure() {
    Route request = request(false);
    messaging.useLogVisibility(
        (conversation, account) -> !conversation.equals(sender.conversation()));
    messaging.complete(
        request.message(), new Outcome(Ending.UNAVAILABLE, "private", 1, 1, "private"));
    assertTrue(notices.isEmpty());
    assertTrue(routes.get(request.message()).handled());
    assertEquals("UNAVAILABLE", routes.get(request.message()).ending());
  }

  @Test
  void waiting_and_successful_generated_replies_preserve_existing_behavior() {
    Route request = request(true);
    messaging.complete(request.message(), new Outcome(Ending.AWAITING, "approval", 1, 1, ""));
    assertTrue(notices.isEmpty());
    verify(repository).awaiting(request.message());
    messaging.complete(
        request.message(), new Outcome(Ending.ANSWERED, "completed answer", 1, 1, ""));
    assertEquals("completed answer", notices.getFirst().body());
    assertTrue(routes.get(notices.getFirst().id()).finalReply());
  }
}
