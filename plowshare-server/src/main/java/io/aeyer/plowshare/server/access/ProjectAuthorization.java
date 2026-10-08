package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.util.*;
import org.springframework.stereotype.Component;

/** Resolves resource IDs to their durable scope before granting project operations. */
@Component
public class ProjectAuthorization {
  private final ResourceScopeRepository resources;
  private final ProjectMembers members;
  private final AdminStore accounts;

  public ProjectAuthorization(
      ResourceScopeRepository resources, ProjectMembers members, AdminStore accounts) {
    this.resources = resources;
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
          "application.files",
          "application.file.read",
          "job.list",
          "job.status",
          "job.stream",
          "board.topics",
          "board.messages",
          "swarm.status",
          "swarm.types",
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
        || operation.equals("swarm.types")
        || operation.startsWith("orchestration.")
        || operation.startsWith("message.")
        || operation.startsWith("union.")
        || operation.startsWith("information.")
        || operation.startsWith("application.")
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
    if (operation.equals("schedule.save") || operation.equals("schedule.sync"))
      return ProjectRole.MANAGER;
    if (operation.equals("schedule.files")) return ProjectRole.VIEWER;
    if (MANAGE.contains(operation)) return ProjectRole.MANAGER;
    if (READ.contains(operation)
        || Set.of("message.instances", "message.instance", "message.deliveries", "message.delivery")
            .contains(operation)) return ProjectRole.VIEWER;
    return ProjectRole.CONTRIBUTOR;
  }

  public Set<String> scopes(String operation, AccessRequest request) {
    Set<String> projects = new LinkedHashSet<>(request.projects());
    for (AccessRequest.Resource resource : request.resources())
      projects.addAll(resources.projects(resource));
    return projects;
  }

  public boolean readable(String project, String account) {
    return project == null || members.mayUse(project, account);
  }

  public boolean triggerOwnedBy(String trigger, String account) {
    return resources.triggerOwnedBy(trigger, account);
  }

  /** Delivery and listing checks fail closed when either grants or the archive are unavailable. */
  public boolean allowed(String operation, AccessRequest payload, String account) {
    try {
      require(operation, payload, account);
      return true;
    } catch (RuntimeException denied) {
      return false;
    }
  }

  public void require(String operation, AccessRequest payload, String account) {
    if (account == null) throw new CallerFault("This operation needs an authenticated account");
    boolean service = io.aeyer.plowshare.server.auth.ServiceCredentials.principal(account);
    if (service
        && !operation.equals("admin.status")
        && (!serviceOperation(operation) || SERVER.contains(operation)))
      throw new CallerFault("This operation is outside service-token project scopes");
    if (Set.of("application.create", "application.storage.set").contains(operation) && service)
      throw new CallerFault("Service tokens cannot admit Application storage");
    if (Set.of("application.create", "application.storage.set").contains(operation)
        || operation.startsWith("admin.")
        || SERVER.contains(operation)) {
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
    // Administrative membership management does not grant ordinary Application use.
    if (operation.startsWith("project.member.") && members.isServerAdmin(account)) return;
    if (ACCOUNT_OWNED.contains(operation)) {
      // Resuming a global emitter requires the same authority as defining one.
      if (operation.equals("schedule.pause") && Boolean.FALSE.equals(payload.paused())) {
        var registered = scopes(operation, payload);
        if (registered.isEmpty() || registered.contains(null)) accounts.requireServerAdmin(account);
        else
          for (String project : registered)
            members.requireRole(project, account, ProjectRole.MANAGER);
      }
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
        if (payload.projectScope()) throw new CallerFault("Project scope needs a project");
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
