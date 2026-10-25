package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Envelope;
import java.util.List;
import java.util.Set;

/** Validated transport scheduling metadata. It never admits domain authority. */
record DispatchPolicy(Kind kind, List<Resource> resources) {
  enum Kind {
    FINITE,
    RESOURCE,
    OBSERVER,
    BARRIER
  }

  record Resource(String family, String scope, String identity, String qualifier) {
    Resource(String family, String scope, String identity) {
      this(family, scope, identity, "");
    }
  }

  DispatchPolicy {
    resources = List.copyOf(resources);
  }

  /**
   * Audited independent reads, shared with legacy event scheduling. Full effect and authority
   * paths, including intentionally retained barriers, are recorded in
   * docs/dispatcher-read-audit.md. These operations may overlap within the existing execution and
   * admission bounds; they still wait behind earlier connection barriers. Scheduling never grants
   * authority: routing, repository checks and the reply disclosure fence remain current.
   *
   * <p>Use explicit operations, never verb/prefix matching: memory.read counts uses,
   * provider.credential.status can expire credentials, and orchestration.caps applies changes.
   * Conversation-scoped inspection belongs in CONVERSATIONS so it shares mutation ordering.
   */
  private static final Set<String> INDEPENDENT_READS =
      Set.of(
          "admin.status",
          "admin.accounts",
          "admin.sessions",
          "admin.audit",
          "admin.service.accounts",
          "admin.service.tokens",
          "admin.pricing.list",
          "project.list",
          "project.access",
          "conversation.list",
          "conversation.latest",
          "agent.list",
          "job.list",
          "job.status",
          "board.topics",
          "board.messages",
          "swarm.status",
          "swarm.types",
          "proc.list",
          "proc.inspect",
          "proc.calls",
          "proc.queues",
          "proc.tools",
          "usage.conversation",
          "usage.project",
          "usage.agent",
          "usage.run",
          "usage.orchestration",
          "usage.models",
          "usage.pools",
          "usage.calls",
          "document.list",
          "document.detail",
          "document.chunk",
          "document.citations",
          "inbox.list",
          "approval.list",
          "orchestration.list",
          "orchestration.status",
          "orchestration.receipt",
          "orchestration.definitions",
          "orchestration.record",
          "memory.index",
          "proposal.list",
          "provider.list",
          "trigger.list",
          "firing.list",
          "union.conflict.list",
          "message.instances",
          "message.instance",
          "message.deliveries",
          "message.delivery",
          "incoming.catalog",
          "incoming.status",
          "outgoing.status",
          "outgoing.peers",
          "application.files",
          "application.file.read",
          "application.identity.status",
          "application.token.receipt",
          "application.deployment.status",
          "application.deployment.receipt",
          "filestore.list",
          "provider.connection.list",
          "provider.connection.status",
          "provider.connection.receipt",
          "tool.scope.list",
          "tool.scope.host.list",
          "tool.scope.host.status",
          "tool.scope.host.receipt",
          "tool.scope.worker.status",
          "information.list",
          "information.facets",
          "information.read",
          "information.inventory",
          "information.acquisitions",
          "information.outline",
          "information.symbols",
          "information.events",
          "information.evidence.read",
          "information.migration.list",
          "information.types.capabilities",
          "information.types.list",
          "information.types.get",
          "information.types.validate",
          "information.content.get",
          "information.navigation.capabilities",
          "information.documents.list",
          "information.documents.facets",
          "information.documents.get",
          "information.relationships.list",
          "information.policy.get",
          "information.views.list",
          "information.views.get",
          "information.interchange.capabilities",
          "information.interchange.export",
          "information.interchange.readable",
          "information.interchange.origin",
          "relay.topics",
          "relay.log",
          "schedule.list",
          "schedule.files");

  static boolean independentRead(String type) {
    return INDEPENDENT_READS.contains(type);
  }

  private static final Set<String> CONVERSATIONS =
      Set.of(
          "conversation.lifecycle",
          "conversation.turns",
          "conversation.chat",
          "conversation.trajectory",
          "conversation.context",
          "conversation.context.count",
          "conversation.context.snapshot",
          "todos.read",
          "conversation.projection",
          "conversation.compactions",
          "conversation.resume");
  private static final Set<String> SCHEDULES =
      Set.of(
          "schedule.define",
          "schedule.pause",
          "schedule.forget",
          "schedule.read",
          "schedule.save",
          "schedule.sync");
  private static final Set<String> REVISIONS =
      Set.of(
          "information.tags",
          "information.tags.replace",
          "information.tags.groups",
          "information.allowance",
          "information.rebuild",
          "information.retry",
          "information.delete",
          "information.withdraw",
          "information.unexclude",
          "information.exclude",
          "information.restore",
          "information.share",
          "information.unshare",
          "information.revise",
          "information.replace",
          "information.refresh");

  /** Decode only cheap conflict identity here; actual validation and authority stay in routing. */
  static DispatchPolicy decode(Envelope request, String account, String connection) {
    String type = request.type();
    var payload = request.payload();
    if (payload == null) return barrier();
    if (type.equals("information.await") || type.equals("relay.consume"))
      // Relay's repository owns durable claim/lease serialization; do not hold that lane while
      // waiting for a publication/acknowledgement which must be able to run on this connection.
      return new DispatchPolicy(Kind.OBSERVER, List.of());
    if (request.actingFor() != null)
      return resource("acting", connection, request.actingFor().context().toString());
    if (independentRead(type)) return new DispatchPolicy(Kind.FINITE, List.of());
    if (type.equals("web.search")) {
      Object query = payload.get("query");
      Object max = payload.get("max");
      return query instanceof String text && max instanceof Integer limit && limit > 0
          ? resource(
              "search-results", "server", io.aeyer.plowshare.server.search.QueryKey.of(text, limit))
          : barrier();
    }
    if (type.equals("document.search") || type.equals("information.search"))
      return new DispatchPolicy(Kind.FINITE, List.of());
    if (type.equals("relay.publish") || type.equals("relay.ack")) {
      Object project = payload.get("project");
      Object topic = payload.get("topic");
      Object group = payload.get("group");
      return project instanceof String p && topic instanceof String t
          ? new DispatchPolicy(
              Kind.RESOURCE,
              List.of(new Resource("relay-port", p, t, group instanceof String g ? g : "")))
          : barrier();
    }
    if (type.equals("acting.context.close") && payload.get("context") instanceof String context)
      return uuidResource("acting", connection, context);
    if (Set.of("usage.subscribe", "usage.unsubscribe", "acting.context.open").contains(type))
      return resource("connection-state", connection, "lifecycle");
    if (CONVERSATIONS.contains(type)) {
      Object id = payload.get("conversation");
      return id instanceof String text ? resource("conversation", "server", text) : barrier();
    }
    if (SCHEDULES.contains(type)) {
      // Source sync can alter several definitions. Its owning account/project is deliberately
      // wider than a single schedule until all affected identities are available in a typed DTO.
      Object project = payload.get("project");
      return resource(
          "schedules", project instanceof String p ? p : "account:" + account, "source");
    }
    if (REVISIONS.contains(type)) {
      Object revision = payload.get("revision");
      if (revision instanceof String id) return uuidResource("information-revision", "server", id);
    }
    return barrier();
  }

  private static DispatchPolicy resource(String family, String scope, String identity) {
    return new DispatchPolicy(Kind.RESOURCE, List.of(new Resource(family, scope, identity)));
  }

  private static DispatchPolicy uuidResource(String family, String scope, String identity) {
    try {
      return resource(family, scope, java.util.UUID.fromString(identity).toString());
    } catch (IllegalArgumentException malformed) {
      return barrier();
    }
  }

  static DispatchPolicy barrier() {
    return new DispatchPolicy(Kind.BARRIER, List.of());
  }
}
