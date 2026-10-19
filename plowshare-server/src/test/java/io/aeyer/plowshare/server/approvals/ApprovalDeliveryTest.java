package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.delivery.PersonDelivery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApprovalDeliveryTest {

  @Test
  void an_attended_question_never_starts_a_model_turn_to_ask_for_approval() {
    RunApprovalStore store = mock(RunApprovalStore.class);
    var conversations = mock(io.aeyer.plowshare.server.archive.ConversationStore.class);
    var voice = mock(PersonDelivery.Voice.class);
    var inbox = mock(PersonDelivery.Inbox.class);
    when(conversations.ownerOf("cnv_turn")).thenReturn(Optional.of("enzo"));
    PersonDelivery people = new PersonDelivery(conversations, voice, inbox);
    RunApproval attended =
        new RunApproval(
            "apr_attended",
            7L,
            "cnv_turn",
            "cnv_turn",
            "coder",
            "local",
            List.of("python3", "connect.py", "post", "Hello from Professor Farnsworth!"),
            "/fixture",
            null,
            RunApproval.ASKED,
            null,
            null,
            null,
            null,
            Instant.now());
    when(store.undelivered("cnv_turn")).thenReturn(List.of(attended));

    new ApprovalDelivery(store, people).drain("cnv_turn");

    verifyNoInteractions(voice);
    verify(inbox)
        .notifyOnceFromLog(
            org.mockito.ArgumentMatchers.eq("enzo"),
            org.mockito.ArgumentMatchers.eq("approval"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("approval:apr_attended"),
            org.mockito.ArgumentMatchers.eq("cnv_turn"));
    verify(store).delivered("apr_attended");
    // Delivery acknowledgement never settles the decision or removes the pending row.
    verify(store, never())
        .allow(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  private static RunApproval question() {
    return new RunApproval(
        "apr_1",
        7L,
        "cnv_event",
        "cnv_child",
        "enzo",
        "coder",
        "server",
        List.of("./gradlew", "test"),
        "/repo",
        null,
        RunApproval.ASKED,
        null,
        null,
        null,
        null,
        null,
        Instant.now());
  }

  @Test
  void relay_owns_notifications_and_direct_drains_do_not_compete() {
    var store = mock(RunApprovalStore.class);
    var people = mock(PersonDelivery.class);
    var delivery = new ApprovalDelivery(store, people);
    delivery.useRelayDelivery(true);
    delivery.drain("cnv_event");
    delivery.drainAll();
    verifyNoInteractions(store, people);
  }

  @Test
  void notification_waits_for_the_requesting_turn_and_checks_project_ownership() {
    var store = mock(RunApprovalStore.class);
    var people = mock(PersonDelivery.class);
    when(store.find("apr_1")).thenReturn(Optional.of(question()));
    var delivery = new ApprovalDelivery(store, people);
    delivery.whenNotificationReady(ignored -> false);
    org.junit.jupiter.api.Assertions.assertFalse(delivery.requested("apr_1", 7));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> delivery.requested("apr_1", 8));
    verifyNoInteractions(people);
  }

  @Test
  void a_question_is_marked_only_after_the_shared_last_mile_delivers_it() {
    RunApprovalStore store = mock(RunApprovalStore.class);
    PersonDelivery people = mock(PersonDelivery.class);
    RunApproval question = question();
    when(store.undelivered("cnv_event")).thenReturn(List.of(question));
    when(people.sendQuestionFromLog(
            "cnv_event",
            "enzo",
            "approval",
            ApprovalDelivery.question(question),
            "approval:apr_1",
            "cnv_child"))
        .thenReturn(
            new PersonDelivery.Sent(
                PersonDelivery.Result.DELIVERED, io.aeyer.plowshare.server.hooks.Handover.INBOX));
    when(store.find("apr_1")).thenReturn(Optional.of(question));

    new ApprovalDelivery(store, people).drain("cnv_event");

    verify(store).delivered("apr_1");
  }

  /** V68: the notice names its approval, and leaves the inbox when that approval is answered. */
  @Test
  void an_answered_approval_s_notice_is_settled() {
    List<String> settled = new ArrayList<>();

    new ApprovalDelivery(mock(RunApprovalStore.class), mock(PersonDelivery.class), settled::add)
        .settled("apr_1");

    assertEquals(List.of("approval:apr_1"), settled);
  }

  /**
   * The person answered — in the TUI's dialog — between the drain reading the question and its
   * notice landing: nothing would settle that notice later, so it is settled as it lands.
   */
  @Test
  void a_question_answered_while_its_notice_was_on_its_way_is_settled_as_it_lands() {
    RunApprovalStore store = mock(RunApprovalStore.class);
    PersonDelivery people = mock(PersonDelivery.class);
    RunApproval question = question();
    RunApproval answered =
        new RunApproval(
            "apr_1",
            7L,
            "cnv_event",
            "cnv_child",
            "enzo",
            "coder",
            "server",
            List.of("./gradlew", "test"),
            "/repo",
            null,
            RunApproval.DENIED,
            null,
            null,
            "enzo",
            Instant.now(),
            null,
            Instant.now());
    when(store.undelivered("cnv_event")).thenReturn(List.of(question));
    when(people.sendQuestionFromLog(
            "cnv_event",
            "enzo",
            "approval",
            ApprovalDelivery.question(question),
            "approval:apr_1",
            "cnv_child"))
        .thenReturn(
            new PersonDelivery.Sent(
                PersonDelivery.Result.DELIVERED, io.aeyer.plowshare.server.hooks.Handover.INBOX));
    when(store.find("apr_1")).thenReturn(Optional.of(answered));
    List<String> settled = new ArrayList<>();

    new ApprovalDelivery(store, people, settled::add).drain("cnv_event");

    verify(store).delivered("apr_1");
    assertEquals(List.of("approval:apr_1"), settled);
  }

  @Test
  void a_settle_that_fails_is_logged_and_nothing_else() {
    new ApprovalDelivery(
            mock(RunApprovalStore.class),
            mock(PersonDelivery.class),
            about -> {
              throw new IllegalStateException("the inbox is down");
            })
        .settled("apr_1");
  }

  @Test
  void a_second_awaiting_ending_is_not_a_duplicate_inbox_notice() {
    PersonDelivery people = mock(PersonDelivery.class);

    new ApprovalDelivery(mock(RunApprovalStore.class), people)
        .continuationEnded(
            question(), new Outcome(Outcome.Ending.AWAITING, "another question", 1, 1, ""));

    verify(people, never())
        .deliver(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }
}
