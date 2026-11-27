package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class GoalInboxTest {
  @Test
  void source_bound_receipt_retries_and_question_settlement_only_push_after_commit() {
    var store = mock(InboxStore.class);
    var pushes = mock(AccountPushes.class);
    var at = Instant.parse("2026-11-26T10:00:00Z");
    var goal = UUID.randomUUID();
    var intent = UUID.randomUUID();
    var deferred = new ArrayList<Runnable>();
    var work =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> operation) {
            return operation.get();
          }

          @Override
          public void afterCommit(Runnable action) {
            deferred.add(action);
          }
        };
    GoalInbox inbox =
        new Inbox(store, pushes, () -> at, EventsProperties.DEFAULT_QUESTION_ALERT_RETRY, work);
    when(store.goalNotice("owner", goal, "cnv_Goal", intent, GoalInbox.Kind.QUESTION, at))
        .thenReturn(
            new InboxStore.GoalNotice("inb_Goal", true),
            new InboxStore.GoalNotice("inb_Goal", false));
    when(store.settleGoalQuestion("owner", goal, intent, at)).thenReturn(true, false);
    assertEquals(
        "inb_Goal", inbox.publish("owner", goal, "cnv_Goal", intent, GoalInbox.Kind.QUESTION));
    assertEquals(
        "inb_Goal", inbox.publish("owner", goal, "cnv_Goal", intent, GoalInbox.Kind.QUESTION));
    inbox.settleQuestion("owner", goal, intent);
    inbox.settleQuestion("owner", goal, intent);

    verifyNoInteractions(pushes);
    assertEquals(2, deferred.size());
    when(store.unread("owner")).thenReturn(1, 0);
    deferred.forEach(Runnable::run);
    verify(pushes).push("owner", Inbox.changed(1));
    verify(pushes).push("owner", Inbox.changed(0));
  }
}
