package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.SchedulePage;
import io.aeyer.plowshare.server.events.ScheduleStore;
import java.util.Map;
import java.util.Objects;

/** {@code schedule.list} — an exclusive name-keyset page, with current next fire times. */
public final class ScheduleListHandler implements FrameHandler {

  private final ScheduleStore schedules;
  private final java.util.function.Supplier<io.aeyer.plowshare.server.events.ScheduleDefinitions>
      definitions;

  public ScheduleListHandler(ScheduleStore schedules) {
    this(schedules, () -> null);
  }

  public ScheduleListHandler(
      ScheduleStore schedules,
      java.util.function.Supplier<io.aeyer.plowshare.server.events.ScheduleDefinitions>
          definitions) {
    this.schedules = Objects.requireNonNull(schedules, "schedules");
    this.definitions = Objects.requireNonNull(definitions, "definitions");
  }

  /** Decode the optional project selection before reading any projections. */
  static String project(Map<String, Object> payload) {
    if (!payload.containsKey("project")) return null;
    Object value = payload.get("project");
    if (!(value instanceof String project)
        || project.isBlank()
        || !project.equals(project.strip())
        || project.length() > 256
        || project.codePoints().anyMatch(Character::isISOControl)
        || project.startsWith("personal:")
        || project.startsWith("Personal:")
        || io.aeyer.plowshare.server.archive.ClientProjects.privateProject(project)
        // ADR 0042, list (c): the same refusal as FileScheduleDefinitions.requireRead, made at
        // the frame boundary so a child's project is not even decoded as a schedule selection.
        || io.aeyer.plowshare.protocol.ContainedProjects.name(project))
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Choose an ordinary project for schedule inspection");
    return project;
  }

  /** Validate transport values before any source, permission or projection read. */
  static SchedulePage page(Map<String, Object> payload) {
    // Keep the event protocol's unknown-field compatibility, but refuse its other listing
    // dialect's offset: silently ignoring that known control could repeat the first page.
    if (payload.containsKey("offset"))
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Schedule listing uses an exclusive after cursor, not an offset");
    String after = null;
    if (payload.containsKey("after")) {
      if (!(payload.get("after") instanceof String value))
        throw new io.aeyer.plowshare.server.faults.CallerFault("after must be a schedule cursor");
      after = value;
    }
    int limit = SchedulePage.DEFAULT_LIMIT;
    if (payload.containsKey("limit")) {
      if (!(payload.get("limit") instanceof Number value)
          || !Double.isFinite(value.doubleValue())
          || value.doubleValue() != Math.rint(value.doubleValue())
          || value.doubleValue() < 1
          || value.doubleValue() > 100)
        throw new io.aeyer.plowshare.server.faults.CallerFault(
            "limit must be an integer between 1 and 100");
      limit = value.intValue();
    }
    try {
      return new SchedulePage(after, limit);
    } catch (IllegalArgumentException invalid) {
      throw new io.aeyer.plowshare.server.faults.CallerFault(invalid.getMessage());
    }
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String project = project(payload);
    SchedulePage page = page(payload);
    String account = asking.requireHandle(FrameTypes.SCHEDULE_LIST);
    if (project == null) return Outcome.ok(schedules.page(account, null, page));
    var service = definitions.get();
    if (service == null)
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Scoped schedule inspection is unavailable");
    service.requireRead(account, project);
    var result = schedules.page(account, project, page);
    service.requireRead(account, project);
    return Outcome.ok(result);
  }
}
