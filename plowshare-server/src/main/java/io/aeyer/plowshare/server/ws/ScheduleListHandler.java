package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.ScheduleStore;
import java.util.Map;
import java.util.Objects;

/** {@code schedule.list} — every schedule, with its next fire time. */
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
        || io.aeyer.plowshare.server.archive.ClientProjects.privateProject(project))
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Choose an ordinary project for schedule inspection");
    return project;
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String project = project(payload);
    if (project == null) return Outcome.ok(schedules.list());
    var service = definitions.get();
    if (service == null)
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Scoped schedule inspection is unavailable");
    var names =
        service.list(asking.requireHandle(FrameTypes.SCHEDULE_LIST), project).stream()
            .map(io.aeyer.plowshare.protocol.ScheduledWork.File::internalName)
            .collect(java.util.stream.Collectors.toSet());
    return Outcome.ok(schedules.list().stream().filter(s -> names.contains(s.name())).toList());
  }
}
