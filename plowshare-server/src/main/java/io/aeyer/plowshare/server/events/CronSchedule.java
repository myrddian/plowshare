package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import org.springframework.scheduling.support.CronExpression;

/**
 * A cron expression read in a zone. Spring's six-field form (seconds first), and nothing of
 * Spring's scheduling machinery beyond the parser.
 */
public record CronSchedule(String cron, ZoneId zone) {

  public CronSchedule {
    Objects.requireNonNull(cron, "cron");
    Objects.requireNonNull(zone, "zone");
  }

  public static CronSchedule parse(String cron, String zone) {
    if (cron == null || cron.isBlank()) {
      throw new CallerFault(
          "a schedule needs a cron expression, six fields, seconds first:"
              + " \"0 0 9 * * *\" is nine every morning");
    }
    ZoneId id;
    try {
      id = ZoneId.of(zone == null || zone.isBlank() ? "UTC" : zone);
    } catch (DateTimeException unknown) {
      throw new CallerFault(
          "\""
              + zone
              + "\" is not a zone this server knows; use an IANA"
              + " id such as Europe/London or UTC");
    }
    try {
      CronExpression.parse(cron);
    } catch (IllegalArgumentException unreadable) {
      throw new CallerFault(
          "\""
              + cron
              + "\" is not a cron expression this server can read: "
              + unreadable.getMessage());
    }
    return new CronSchedule(cron, id);
  }

  public Instant nextAfter(Instant after) {
    ZonedDateTime next = CronExpression.parse(cron).next(after.atZone(zone));
    if (next == null) {
      throw new CallerFault("\"" + cron + "\" never fires again");
    }
    return next.toInstant();
  }
}
