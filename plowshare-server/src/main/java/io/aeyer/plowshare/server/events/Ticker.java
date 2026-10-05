package io.aeyer.plowshare.server.events;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cron adapter: turns due schedules into events. One call is one pass; the thread that calls it
 * lives in EventsConfig and never starts in tests.
 */
public class Ticker {

  private static final Logger log = LoggerFactory.getLogger(Ticker.class);

  private final ScheduleStore schedules;
  private final ScheduledPublication publications;

  public Ticker(ScheduleStore schedules, ScheduledPublication publications) {
    this.schedules = Objects.requireNonNull(schedules, "schedules");
    this.publications = Objects.requireNonNull(publications, "scheduled publications");
  }

  private Runnable reconciliation = () -> {};

  /**
   * File projection completes before due work is selected; an unavailable scan suspends its source.
   */
  public void useReconciliation(Runnable reconciliation) {
    this.reconciliation = Objects.requireNonNull(reconciliation);
  }

  public void reconcile() {
    reconciliation.run();
  }

  public int tick(Instant now) {
    reconcile();
    publications.recover();
    int fired = 0;
    for (ScheduleRecord due : schedules.due(now)) {
      try {
        Instant following = CronSchedule.parse(due.cron(), due.zone()).nextAfter(now);
        if (!publications.publish(due, following, now)) {
          continue;
        }
        fired++;
      } catch (RuntimeException broken) {
        log.warn(
            "schedule {} could not fire at {}: {}",
            due.name(),
            due.nextFireAt(),
            broken.getMessage());
      }
    }
    return fired;
  }

  /**
   * Missed ticks are not replayed. Only fire times older than {@code grace} move, so a server
   * booting beside a running one does not swallow a tick that one is about to fire.
   */
  public int rollForward(Instant now, Duration grace) {
    return schedules.rollForward(
        now.minus(grace),
        stale -> {
          try {
            return CronSchedule.parse(stale.cron(), stale.zone()).nextAfter(now);
          } catch (RuntimeException broken) {
            log.warn("schedule {} cannot be rolled forward: {}", stale.name(), broken.getMessage());
            return stale.nextFireAt();
          }
        });
  }
}
