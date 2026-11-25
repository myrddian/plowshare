package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.messaging.MessageRefused;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Retries committed Mailbox alerts independently of schedule and Mailbox receipt state. */
final class PersonalScheduleAlerts {
  private static final Logger LOG = LoggerFactory.getLogger(PersonalScheduleAlerts.class);
  private final PersonalScheduleAlertStore alerts;
  private final InboxStore inbox;
  private final Supplier<MessageService> messaging;
  private final UnitOfWork work;
  private final SqlWorkBudget sql;
  private final Duration ceiling;
  private final int maxHops;

  PersonalScheduleAlerts(
      PersonalScheduleAlertStore alerts,
      InboxStore inbox,
      Supplier<MessageService> messaging,
      UnitOfWork work,
      SqlWorkBudget sql,
      Duration ceiling,
      int maxHops) {
    this.alerts = alerts;
    this.inbox = inbox;
    this.messaging = messaging;
    this.work = work;
    this.sql = sql;
    this.ceiling = ceiling;
    this.maxHops = maxHops;
  }

  void process() {
    var messages = messaging.get();
    if (messages == null) return;
    for (String item : sql.within(ceiling, () -> alerts.due(16))) {
      try {
        sql.within(
            ceiling,
            () ->
                work.inTransaction(
                    () -> {
                      alerts.lock(item).ifPresent(alert -> deliver(alert, messages));
                      return null;
                    }));
      } catch (CallerFault | NotFoundFault denied) {
        if (denied instanceof MessageRefused refusal
            && refusal.code() == MessageRefused.Code.QUEUE_FULL)
          update(item, () -> alerts.retry(item));
        else {
          update(item, () -> alerts.refuse(item));
          LOG.info("Personal schedule alert {} was refused", item);
        }
      } catch (RuntimeException failed) {
        update(item, () -> alerts.retry(item));
        LOG.warn(
            "Personal schedule alert {} deferred after {}",
            item,
            failed.getClass().getSimpleName());
      }
    }
  }

  private void deliver(PersonalScheduleAlertStore.Alert alert, MessageService messages) {
    if (!inbox.canSeeScheduledResult(alert.owner(), alert.item(), alert.firing())) {
      alerts.refuse(alert.item());
      return;
    }
    String source = "schedule-result:" + RelayPort.hash(alert.item());
    var cause = alert.cause() == null ? RelayCausation.root(source) : alert.cause();
    if (cause.depth() < 0 || cause.depth() >= maxHops) {
      alerts.refuse(alert.item());
      return;
    }
    String message =
        messages.sendPersonalAlert(
            alert.owner(),
            alert.route(),
            new MessageService.PersonalAlert(alert.item(), alert.firing()),
            cause.next(source, maxHops));
    // The receipt and queue addition share a transaction, so a worker crash can only replay
    // admission for the same item. Messaging uses the retained item and destination as its key.
    alerts.sent(alert.item(), message);
  }

  private void update(String item, Runnable update) {
    try {
      sql.within(
          ceiling,
          () -> {
            update.run();
            return null;
          });
    } catch (RuntimeException failed) {
      LOG.warn("Could not record disposition for Personal schedule alert {}", item);
    }
  }
}
