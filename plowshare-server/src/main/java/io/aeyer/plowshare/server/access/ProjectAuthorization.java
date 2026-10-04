package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves resource IDs to their durable scope before granting project operations. */
@Component
public class ProjectAuthorization {
  private final JdbcTemplate jdbc;
  private final ProjectMembers members;
  private final AdminStore accounts;

  public ProjectAuthorization(JdbcTemplate jdbc, ProjectMembers members, AdminStore accounts) {
    this.jdbc = jdbc;
    this.members = members;
    this.accounts = accounts;
  }

  private static final Set<String> MANAGE =
      Set.of(
          "agent.define",
          "orchestration.define",
          "project.member.add",
          "project.member.remove",
          "project.member.role");
  // Reads are named explicitly. New operations require work until their policy is reviewed.
  private static final Set<String> READ =
      Set.of(
          "project.list",
          "project.access",
          "project.attach",
          "conversation.list",
          "conversation.latest",
          "conversation.search",
          "conversation.follow",
          "conversation.turns",
          "conversation.compactions",
          "conversation.chat",
          "conversation.trajectory",
          "conversation.context",
          "conversation.projection",
          "conversation.context.count",
          "conversation.context.snapshot",
          "memory.recall",
          "memory.index",
          "memory.read",
          "proposal.list",
          "document.retrieve",
          "document.rank",
          "document.stance",
          "document.citations",
          "document.search",
          "document.list",
          "document.detail",
          "document.chunk",
          "web.search",
          "web.fetch",
          "agent.list",
          "job.list",
          "job.status",
          "job.stream",
          "board.topics",
          "board.messages",
          "swarm.status",
          "provider.list",
          "schedule.list",
          "schedule.read",
          "trigger.list",
          "firing.list",
          "inbox.list",
          "approval.list",
          "union.status",
          "union.conflict.list",
          "todos.read",
          "orchestration.receipt",
          "orchestration.definitions",
          "orchestration.list",
          "orchestration.status",
          "orchestration.record",
          "usage.conversation",
          "usage.project",
          "usage.agent",
          "usage.run",
          "usage.orchestration",
          "usage.models",
          "usage.pools",
          "usage.calls",
          "usage.subscribe",
          "usage.unsubscribe",
          "outgoing.status",
          "outgoing.peers",
          "information.inventory",
          "information.acquisitions",
          "information.events",
          "information.facets",
          "information.list",
          "information.status",
          "information.await",
          "information.read",
          "information.outline",
          "information.symbols",
          "information.search",
          "information.rank",
          "information.evidence.read",
          "incoming.catalog",
          "incoming.status");
  private static final Set<String> PERSONAL_DEFAULT =
      Set.of(
          "conversation.open",
          "agent.run",
          "orchestration.start",
          "message.instance.open",
          "inbox.read",
          "outgoing.send",
          "outgoing.advertise",
          "outgoing.claim",
          "outgoing.report",
          "outgoing.cancel",
          "image.upload");
  private static final Set<String> ACCOUNT_OWNED = Set.of("schedule.pause", "schedule.forget");
  private static final Set<String> SERVER =
      Set.of(
          "provider.deregister",
          "buffer.purge",
          "retention.sweep",
          "schedule.define",
          "event.fire",
          "information.migration.list",
          "information.migration.inspect",
          "information.migration.adopt",
          "information.migration.release");
  private static final Set<String> SERVICE_LISTS =
      Set.of(
          "project.list",
          "job.list",
          "orchestration.list",
          "approval.list",
          "trigger.list",
          "firing.list");

  private static boolean serviceOperation(String operation) {
    return operation.startsWith("incoming.")
        || operation.startsWith("conversation.")
        || operation.startsWith("memory.")
        || operation.startsWith("proposal.")
        || operation.startsWith("agent.")
        || operation.startsWith("job.")
        || operation.startsWith("board.")
        || operation.startsWith("orchestration.")
        || operation.startsWith("message.")
        || operation.startsWith("union.")
        || operation.startsWith("information.")
        || operation.startsWith("trigger.")
        || operation.equals("firing.list")
        || operation.startsWith("approval.")
        || operation.equals("image.upload")
        || operation.equals("todos.read")
        || Set.of(
                "project.list",
                "project.access",
                "project.member.add",
                "project.member.remove",
                "project.member.role",
                "usage.project",
                "usage.conversation",
                "usage.agent",
                "usage.run",
                "usage.orchestration",
                "outgoing.send",
                "outgoing.status",
                "outgoing.cancel",
                "outgoing.peers",
                "outgoing.advertise",
                "outgoing.claim",
                "outgoing.report")
            .contains(operation);
  }

  public static ProjectRole required(String operation) {
    if (MANAGE.contains(operation)) return ProjectRole.MANAGER;
    if (READ.contains(operation)
        || Set.of("message.instances", "message.instance", "message.deliveries", "message.delivery")
            .contains(operation)) return ProjectRole.VIEWER;
    return ProjectRole.CONTRIBUTOR;
  }

  private void project(Set<String> projects, Object name) {
    if (name instanceof String value && !value.isBlank()) projects.add(value);
  }

  private void lookup(Set<String> projects, String query, Object id) {
    if (id instanceof String value && !value.isBlank())
      projects.addAll(jdbc.queryForList(query, String.class, value));
  }

  public Set<String> scopes(String operation, Map<String, Object> payload) {
    Set<String> projects = new LinkedHashSet<>();
    project(projects, payload.get("project"));
    if (payload.get("scope") instanceof Map<?, ?> scope && "project".equals(scope.get("kind")))
      project(projects, scope.get("project"));
    lookup(
        projects,
        "SELECT p.name FROM conversations c LEFT JOIN projects p ON p.id=c.project_id WHERE c.id=?",
        payload.get("conversation"));
    if (payload.get("conversations") instanceof Iterable<?> ids)
      for (Object id : ids)
        lookup(
            projects,
            "SELECT p.name FROM conversations c LEFT JOIN projects p ON p.id=c.project_id WHERE c.id=?",
            id);
    lookup(
        projects,
        "SELECT p.name FROM jobs j LEFT JOIN projects p ON p.id=j.project_id WHERE j.id=?",
        payload.get("job"));
    lookup(
        projects,
        "SELECT p.name FROM memories m LEFT JOIN projects p ON p.id=m.project_id WHERE m.id=?",
        payload.get("memory"));
    lookup(
        projects,
        "SELECT pr.name FROM proposals p JOIN memories m ON m.id=p.memory_id LEFT JOIN projects pr ON pr.id=m.project_id WHERE p.id=?",
        payload.get("proposal"));
    lookup(projects, "SELECT project FROM board_topics WHERE id=?", payload.get("topic"));
    lookup(
        projects,
        "SELECT project FROM board_message_instances WHERE id=?",
        payload.get("instance"));
    if (operation.startsWith("orchestration.")) {
      lookup(projects, "SELECT project FROM orchestrations WHERE id=?", payload.get("id"));
      lookup(projects, "SELECT project FROM orchestrations WHERE id=?", payload.get("root"));
    }
    if (operation.startsWith("message."))
      lookup(
          projects,
          "SELECT i.project FROM board_message_routes r JOIN board_message_instances i ON i.id IN (r.sender,r.recipient) WHERE r.message=?",
          payload.get("message"));
    if (operation.startsWith("trigger.") && !operation.equals("trigger.define"))
      lookup(
          projects,
          "SELECT COALESCE(t.project,p.name,'personal:'||encode(convert_to(t.defined_by,'UTF8'),'hex')) FROM triggers t LEFT JOIN conversations c ON c.id=t.conversation LEFT JOIN projects p ON p.id=c.project_id WHERE t.name=?",
          payload.get("trigger"));
    if (operation.startsWith("board.") || operation.startsWith("swarm.")) {
      lookup(projects, "SELECT project FROM board_topics WHERE id=?", payload.get("root"));
      lookup(projects, "SELECT project FROM board_topics WHERE id=?", payload.get("board"));
    }
    if (operation.startsWith("approval."))
      lookup(
          projects,
          "SELECT p.name FROM run_approvals a JOIN projects p ON p.id=a.project_id WHERE a.id=?",
          payload.get("id"));
    if (operation.startsWith("usage.")) {
      lookup(
          projects,
          "SELECT p.name FROM jobs j LEFT JOIN projects p ON p.id=j.project_id WHERE j.id=?",
          payload.get("run"));
      lookup(
          projects, "SELECT project FROM orchestrations WHERE id=?", payload.get("orchestration"));
    }
    if (operation.startsWith("outgoing.") && payload.get("id") instanceof String id) {
      try {
        projects.addAll(
            jdbc.queryForList(
                "SELECT p.name FROM outgoing_work w LEFT JOIN projects p ON p.id=w.project_id WHERE w.id=?",
                String.class,
                java.util.UUID.fromString(id)));
      } catch (IllegalArgumentException invalid) {
        throw new CallerFault("Outgoing work ID must be a UUID");
      }
    }
    return projects;
  }

  private boolean readable(String project, String account) {
    return project == null || members.mayUse(project, account);
  }

  private boolean triggerReadable(
      io.aeyer.plowshare.server.events.TriggerRecord trigger, String account) {
    if (!account.equals(trigger.definedBy())) return false;
    Map<String, Object> scope = new LinkedHashMap<>();
    if (trigger.project() != null) scope.put("project", trigger.project());
    if (trigger.conversation() != null) scope.put("conversation", trigger.conversation());
    return allowed("trigger.list", scope, account);
  }

  /** Unscoped listings must not retain access through a resource's earlier ownership. */
  public io.aeyer.plowshare.protocol.frames.Outcome filter(
      String operation, io.aeyer.plowshare.protocol.frames.Outcome outcome, String account) {
    Object payload = outcome.payload();
    if (payload instanceof List<?> rows && operation.equals("trigger.list")) {
      payload =
          rows.stream()
              .map(io.aeyer.plowshare.server.events.TriggerRecord.class::cast)
              .filter(row -> triggerReadable(row, account))
              .toList();
    } else if (payload instanceof List<?> rows && operation.equals("schedule.list")) {
      payload =
          rows.stream()
              .map(io.aeyer.plowshare.server.events.ScheduleRecord.class::cast)
              .filter(row -> account.equals(row.definedBy()))
              .toList();
    } else if (payload instanceof List<?> rows && operation.equals("firing.list")) {
      payload =
          rows.stream()
              .map(io.aeyer.plowshare.server.events.FiringRecord.class::cast)
              .filter(
                  row -> {
                    var owners =
                        jdbc.queryForList(
                            "SELECT defined_by FROM triggers WHERE name=?",
                            String.class,
                            row.trigger());
                    return owners.contains(account)
                        && allowed("trigger.list", Map.of("trigger", row.trigger()), account);
                  })
              .toList();
    } else if (payload instanceof io.aeyer.plowshare.server.ws.OrchestrationFrames.Listed listed) {
      payload =
          new io.aeyer.plowshare.server.ws.OrchestrationFrames.Listed(
              listed.orchestrations().stream()
                  .filter(row -> readable(row.project(), account))
                  .toList());
    } else if (payload instanceof io.aeyer.plowshare.server.ws.ApprovalFrames.Listed listed) {
      payload =
          new io.aeyer.plowshare.server.ws.ApprovalFrames.Listed(
              listed.approvals().stream()
                  .filter(row -> allowed("approval.list", Map.of("id", row.id()), account))
                  .toList());
    } else if (payload
        instanceof io.aeyer.plowshare.server.ws.BoardInspectionFrames.Topics listed) {
      payload =
          new io.aeyer.plowshare.server.ws.BoardInspectionFrames.Topics(
              listed.topics().stream()
                  .filter(row -> readable(row.topic().project(), account))
                  .toList(),
              listed.more(),
              listed.offset());
    } else if (payload instanceof io.aeyer.plowshare.server.ws.BoardInspectionFrames.Swarm listed) {
      payload =
          new io.aeyer.plowshare.server.ws.BoardInspectionFrames.Swarm(
              listed.pools(),
              listed.ready().stream()
                  .filter(row -> allowed("board.messages", Map.of("topic", row.topic()), account))
                  .toList(),
              listed.topics().stream()
                  .filter(row -> readable(row.topic().project(), account))
                  .toList(),
              listed.seats().stream()
                  .filter(
                      row ->
                          allowed("board.messages", Map.of("topic", row.seat().topic()), account))
                  .toList(),
              listed.more());
    }
    return payload == outcome.payload()
        ? outcome
        : new io.aeyer.plowshare.protocol.frames.Outcome(outcome.code(), outcome.said(), payload);
  }

  /** Delivery and listing checks fail closed when either grants or the archive are unavailable. */
  public boolean allowed(String operation, Map<String, Object> payload, String account) {
    try {
      require(operation, payload, account);
      return true;
    } catch (RuntimeException denied) {
      return false;
    }
  }

  public void require(String operation, Map<String, Object> payload, String account) {
    if (account == null) throw new CallerFault("This operation needs an authenticated account");
    boolean service = io.aeyer.plowshare.server.auth.ServiceCredentials.principal(account);
    if (service
        && !operation.equals("admin.status")
        && (!serviceOperation(operation) || SERVER.contains(operation)))
      throw new CallerFault("This operation is outside service-token project scopes");
    if (operation.startsWith("admin.") || SERVER.contains(operation)) {
      if (!operation.equals("admin.status")) accounts.requireServerAdmin(account);
      return;
    }
    if (operation.startsWith("project.")
        && !Set.of(
                "project.list",
                "project.attach",
                "project.access",
                "project.member.add",
                "project.member.remove",
                "project.member.role")
            .contains(operation)) {
      accounts.requireServerAdmin(account);
      return;
    }
    if (ACCOUNT_OWNED.contains(operation)) {
      // Resuming a global emitter requires the same authority as defining one.
      if (operation.equals("schedule.pause") && Boolean.FALSE.equals(payload.get("paused")))
        accounts.requireServerAdmin(account);
      return; // Services enforce account ownership of these controls.
    }
    ProjectRole permission = required(operation);
    Set<String> projects = scopes(operation, payload);
    if (service && projects.isEmpty() && !SERVICE_LISTS.contains(operation))
      throw new CallerFault(
          "Service tokens require an explicit project or a resource in a granted project");
    if (service && projects.contains(null))
      throw new CallerFault("Service tokens cannot access global work");
    if (projects.isEmpty()) {
      if (operation.startsWith("information.")
          || PERSONAL_DEFAULT.contains(operation)
          || operation.equals("project.attach")
          || operation.equals("trigger.define")) {
        if (payload.get("scope") instanceof Map<?, ?> scope && "project".equals(scope.get("kind")))
          throw new CallerFault("Project scope needs a project");
        // Information lifecycle independently enforces ownership and shared resource policy.
        return;
      }
      if (permission != ProjectRole.VIEWER) accounts.requireServerAdmin(account);
      return;
    }
    for (String project : projects) {
      if (project == null) {
        if (permission != ProjectRole.VIEWER && !operation.startsWith("outgoing."))
          accounts.requireServerAdmin(account);
      } else {
        PersonalSpaces.requireOwn(project, account);
        members.requireRole(project, account, permission);
      }
    }
  }
}
