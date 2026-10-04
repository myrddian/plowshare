package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.delivery.PersonDelivery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApprovalDeliveryTest {

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
  void a_question_is_marked_only_after_the_shared_last_mile_delivers_it() {
    RunApprovalStore store = mock(RunApprovalStore.class);
    PersonDelivery people = mock(PersonDelivery.class);
    RunApproval question = question();
    when(store.undelivered("cnv_event")).thenReturn(List.of(question));
    when(people.sendFromLog(
            "cnv_event",
            "coder",
            "enzo",
            "approval",
            ApprovalDelivery.question(question),
            Speaker.approval("apr_1"),
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
    when(people.sendFromLog(
            "cnv_event",
            "coder",
            "enzo",
            "approval",
            ApprovalDelivery.question(question),
            Speaker.approval("apr_1"),
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
