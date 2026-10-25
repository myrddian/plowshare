package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.access.ProjectAuthorization;
import java.util.*;

/** Outbound transport filtering delegates every ownership decision to authorization policy. */
public final class ProjectListingFilter {
  private final ProjectAuthorization authorization;

  public ProjectListingFilter(ProjectAuthorization authorization) {
    this.authorization = authorization;
  }

  private boolean triggerReadable(
      io.aeyer.plowshare.server.events.TriggerRecord trigger, String account) {
    if (!account.equals(trigger.definedBy())) return false;
    Map<String, Object> scope = new LinkedHashMap<>();
    if (trigger.project() != null) scope.put("project", trigger.project());
    if (trigger.conversation() != null) scope.put("conversation", trigger.conversation());
    return authorization.allowed(
        "trigger.list",
        io.aeyer.plowshare.server.access.AccessRequestDecoder.decode("trigger.list", scope),
        account);
  }

  /** Unscoped listings must not retain access through a resource's earlier ownership. */
  public io.aeyer.plowshare.protocol.frames.Outcome filter(
      String operation, io.aeyer.plowshare.protocol.frames.Outcome outcome, String account) {
    return filter(operation, outcome, account, null);
  }

  /**
   * Batched current policy at completion/disclosure; a loaded listing is not a permission lease.
   */
  public io.aeyer.plowshare.protocol.frames.Outcome filter(
      String operation,
      io.aeyer.plowshare.protocol.frames.Outcome outcome,
      String account,
      String selectedProject) {
    Object payload = outcome.payload();
    if (payload instanceof List<?> rows && operation.equals("project.list")) {
      var projects =
          rows.stream().map(io.aeyer.plowshare.server.api.ProjectView.class::cast).toList();
      var roles =
          authorization.projectRoles(projects.stream().map(row -> row.name()).toList(), account);
      payload =
          projects.stream()
              .filter(row -> roles.containsKey(row.name()))
              .map(row -> row.withRole(roles.get(row.name()).name()))
              .toList();
    } else if (payload instanceof List<?> rows && operation.equals("trigger.list")) {
      payload =
          rows.stream()
              .map(io.aeyer.plowshare.server.events.TriggerRecord.class::cast)
              .filter(row -> triggerReadable(row, account))
              .toList();
    } else if (payload instanceof List<?> rows && operation.equals("schedule.list")) {
      payload =
          rows.stream()
              .map(io.aeyer.plowshare.server.events.ScheduleRecord.class::cast)
              .filter(row -> selectedProject != null || account.equals(row.definedBy()))
              .toList();
    } else if (payload instanceof List<?> rows && operation.equals("firing.list")) {
      // FiringListHandler has already projected records into the v1 response shape.
      payload =
          rows.stream()
              .map(FiringView.class::cast)
              .filter(
                  row -> {
                    return authorization.triggerOwnedBy(row.trigger(), account)
                        && authorization.allowed(
                            "trigger.list",
                            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                                "trigger.list", Map.of("trigger", row.trigger())),
                            account);
                  })
              .toList();
    } else if (payload instanceof io.aeyer.plowshare.protocol.Orchestration.Listed listed) {
      var readable =
          authorization.readableProjects(
              listed.orchestrations().stream().map(row -> row.project()).toList(), account);
      payload =
          new io.aeyer.plowshare.protocol.Orchestration.Listed(
              listed.orchestrations().stream()
                  .filter(row -> row.project() == null || readable.contains(row.project()))
                  .toList());
    } else if (payload instanceof io.aeyer.plowshare.server.ws.ApprovalFrames.Listed listed) {
      payload =
          new io.aeyer.plowshare.server.ws.ApprovalFrames.Listed(
              listed.approvals().stream()
                  .filter(
                      row ->
                          authorization.allowed(
                              "approval.list",
                              io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                                  "approval.list", Map.of("id", row.id())),
                              account))
                  .toList());
    } else if (payload
        instanceof io.aeyer.plowshare.server.ws.BoardInspectionFrames.Topics listed) {
      var readable =
          authorization.readableProjects(
              listed.topics().stream().map(row -> row.topic().project()).toList(), account);
      payload =
          new io.aeyer.plowshare.server.ws.BoardInspectionFrames.Topics(
              listed.topics().stream()
                  .filter(
                      row ->
                          row.topic().project() == null || readable.contains(row.topic().project()))
                  .toList(),
              listed.more(),
              listed.offset());
    } else if (payload instanceof io.aeyer.plowshare.server.ws.BoardInspectionFrames.Swarm listed) {
      var readable =
          authorization.readableProjects(
              listed.topics().stream().map(row -> row.topic().project()).toList(), account);
      payload =
          new io.aeyer.plowshare.server.ws.BoardInspectionFrames.Swarm(
              listed.pools(),
              listed.ready().stream()
                  .filter(
                      row ->
                          authorization.allowed(
                              "board.messages",
                              io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                                  "board.messages", Map.of("topic", row.topic())),
                              account))
                  .toList(),
              listed.topics().stream()
                  .filter(
                      row ->
                          row.topic().project() == null || readable.contains(row.topic().project()))
                  .toList(),
              listed.seats().stream()
                  .filter(
                      row ->
                          authorization.allowed(
                              "board.messages",
                              io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                                  "board.messages", Map.of("topic", row.seat().topic())),
                              account))
                  .toList(),
              listed.more());
    }
    return payload == outcome.payload()
        ? outcome
        : new io.aeyer.plowshare.protocol.frames.Outcome(outcome.code(), outcome.said(), payload);
  }
}
