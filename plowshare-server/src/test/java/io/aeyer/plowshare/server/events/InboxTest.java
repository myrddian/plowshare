package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.AccountEvent;
import io.aeyer.plowshare.server.agents.Outcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InboxTest {

  private static final Instant T0 = Instant.parse("2026-09-13T09:00:00Z");

  @Test
  void a_delivery_is_stored_and_the_accounts_sockets_are_told_the_new_unread_count() {
    InboxStore store = mock(InboxStore.class);
    when(store.unread("enzo")).thenReturn(3);
    List<Object[]> pushed = new ArrayList<>();
    Inbox inbox =
        new Inbox(store, (handle, body) -> pushed.add(new Object[] {handle, body}), () -> T0);

    inbox.deliver(
        "enzo",
        "fir_1",
        "cnv_1",
        new Outcome(Outcome.Ending.ANSWERED, "three PRs need review", 2, 3, ""));

    verify(store).deliver("enzo", "fir_1", "cnv_1", "ANSWERED", "three PRs need review", T0);
    assertEquals(1, pushed.size());
    assertEquals("enzo", pushed.get(0)[0]);
    assertEquals(new AccountEvent.InboxChanged(3), pushed.get(0)[1]);
  }

  @Test
  void reading_items_tells_the_accounts_other_sockets_too() {
    InboxStore store = mock(InboxStore.class);
    when(store.markRead("enzo", List.of("inb_1"), T0)).thenReturn(1);
    when(store.unread("enzo")).thenReturn(0);
    List<Object> pushed = new ArrayList<>();
    Inbox inbox = new Inbox(store, (handle, body) -> pushed.add(body), () -> T0);

    assertEquals(1, inbox.read("enzo", List.of("inb_1")));
    assertEquals(List.of(new AccountEvent.InboxChanged(0)), pushed);
  }

  @Test
  void a_question_s_notice_is_stored_with_what_it_asks_about() {
    InboxStore store = mock(InboxStore.class);
    when(store.unread("enzo")).thenReturn(1);
    List<Object> pushed = new ArrayList<>();
    Inbox inbox = new Inbox(store, (handle, body) -> pushed.add(body), () -> T0);

    inbox.notify("enzo", "approval", "Approve running make? [apr_1]", "approval:apr_1");

    verify(store).notice("enzo", "approval", "Approve running make? [apr_1]", "approval:apr_1", T0);
    assertEquals(List.of(new AccountEvent.InboxChanged(1)), pushed);
  }

  /** A settled question leaves the inbox at once: every account it left is told its count. */
  @Test
  void settling_tells_each_account_whose_notice_it_settled_its_new_count() {
    InboxStore store = mock(InboxStore.class);
    when(store.settle("approval:apr_1", T0)).thenReturn(List.of("enzo"));
    when(store.unread("enzo")).thenReturn(2);
    List<Object[]> pushed = new ArrayList<>();
    Inbox inbox =
        new Inbox(store, (handle, body) -> pushed.add(new Object[] {handle, body}), () -> T0);

    inbox.settle("approval:apr_1");

    assertEquals(1, pushed.size());
    assertEquals("enzo", pushed.get(0)[0]);
    assertEquals(new AccountEvent.InboxChanged(2), pushed.get(0)[1]);
  }

  @Test
  void settling_a_question_nobody_was_told_of_pushes_nothing() {
    InboxStore store = mock(InboxStore.class);
    when(store.settle("approval:apr_1", T0)).thenReturn(List.of());
    List<Object> pushed = new ArrayList<>();
    Inbox inbox = new Inbox(store, (handle, body) -> pushed.add(body), () -> T0);

    inbox.settle("approval:apr_1");

    assertEquals(List.of(), pushed);
  }

  @Test
  void durable_question_push_requires_an_atomic_alert_attempt() {
    InboxStore store = mock(InboxStore.class);
    var item =
        new InboxItem(
            "inb_fixture",
            "owner",
            "approval",
            null,
            "cnv_fixture",
            null,
            "Review command",
            T0,
            null,
            "approval:fixture");
    when(store.noticeOnceFromLog(
            "owner", "approval", "Review command", "cnv_fixture", "approval:fixture", T0))
        .thenReturn(item);
    when(store.claimQuestionAlert(
            "owner", item.id(), T0, EventsProperties.DEFAULT_QUESTION_ALERT_RETRY))
        .thenReturn(true, false);
    when(store.unread("owner")).thenReturn(1);
    List<Object> pushed = new ArrayList<>();
    var inbox = new Inbox(store, (handle, body) -> pushed.add(body), () -> T0);
    inbox.notifyOnceFromLog(
        "owner", "approval", "Review command", "cnv_fixture", "approval:fixture");
    inbox.notifyOnceFromLog(
        "owner", "approval", "Review command", "cnv_fixture", "approval:fixture");
    assertEquals(List.of(new AccountEvent.InboxChanged(1)), pushed);
  }

  @Test
  void alert_retry_configuration_rejects_invalid_or_unbounded_intervals() {
    var properties = new EventsProperties();
    assertEquals(java.time.Duration.ofSeconds(30), properties.getQuestionAlertRetryInterval());
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> properties.setQuestionAlertRetryInterval(null));
    for (var invalid :
        List.of(
            java.time.Duration.ZERO,
            java.time.Duration.ofSeconds(-1),
            java.time.Duration.ofMillis(999),
            java.time.Duration.ofHours(2)))
      org.junit.jupiter.api.Assertions.assertThrows(
          IllegalArgumentException.class, () -> properties.setQuestionAlertRetryInterval(invalid));
    properties.setQuestionAlertRetryInterval(java.time.Duration.ofSeconds(1));
    assertEquals(java.time.Duration.ofSeconds(1), properties.getQuestionAlertRetryInterval());
  }
}
