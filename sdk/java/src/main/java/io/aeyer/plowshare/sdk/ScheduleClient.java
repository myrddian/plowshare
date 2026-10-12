package io.aeyer.plowshare.sdk;

import io.aeyer.plowshare.protocol.ScheduledWork;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only source/effective schedule inspection. Live project roles are enforced by the server.
 */
public final class ScheduleClient {
  private final Plowshare connection;

  public ScheduleClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  /** Effective projection, including an operational pause independent of source content. */
  public record Schedule(
      String name,
      String cron,
      String zone,
      String emits,
      boolean paused,
      String nextFireAt,
      String definedBy) {
    public Schedule {
      ContractChecks.identity(name, "name");
      ContractChecks.identity(cron, "cron");
      ContractChecks.identity(zone, "zone");
      ContractChecks.identity(emits, "emits");
      ContractChecks.timestamp(nextFireAt, "nextFireAt");
      ContractChecks.identity(definedBy, "definedBy");
    }
  }

  public List<Schedule> list(String project) throws IOException {
    return read("schedule.list", project, Schedule.class);
  }

  public List<ScheduledWork.File> files(String project) throws IOException {
    return read("schedule.files", project, ScheduledWork.File.class);
  }

  private <T> List<T> read(String operation, String project, Class<T> type) throws IOException {
    io.aeyer.plowshare.protocol.ApplicationIdentity.project(project);
    var value = connection.request(operation, Map.of("project", project)).requirePayload();
    if (!value.isArray() || value.size() > 10000)
      throw new IOException("Unreadable scoped schedule listing");
    List<T> result = new ArrayList<>();
    for (var row : value) result.add(SdkJson.decode(SdkJson.mapper(), row, type));
    return List.copyOf(result);
  }
}
