package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.relay.Relay;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Specialist persistence contract. SQL, row decoding and guarded transitions belong to its JDBC
 * implementation.
 */
public interface ScheduleStore {

  ScheduleRecord define(
      String name, CronSchedule schedule, String emits, String definedBy, Instant now);

  List<ScheduleRecord> list();

  Optional<ScheduleRecord> find(String name);

  void pause(String name, boolean paused, String handle);

  void forget(String name, String handle);

  List<ScheduleRecord> due(Instant now);

  boolean claim(String name, Instant due, Instant following);

  /**
   * Claims exactly the selected definition under live emitter authority and returns its real
   * publication scope. Joins the publisher's transaction; empty means a changed/paused/denied
   * schedule or a losing claimant. Projectless schedules explicitly use server scope.
   */
  Optional<Relay.Scope> claimPublication(ScheduleRecord selected, Instant following);

  int rollForward(Instant olderThan, Function<ScheduleRecord, Instant> following);
}
