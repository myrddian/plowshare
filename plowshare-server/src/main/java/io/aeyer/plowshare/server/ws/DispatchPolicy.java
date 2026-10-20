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

  private static final Set<String> FINITE =
      Set.of(
          "information.list",
          "information.facets",
          "information.read",
          "information.navigation.capabilities",
          "information.documents.list",
          "information.documents.facets",
          "information.documents.get",
          "information.relationships.list",
          "relay.topics",
          "relay.log",
          "schedule.list",
          "schedule.files");
  private static final Set<String> CONVERSATIONS =
      Set.of(
          "conversation.lifecycle",
          "conversation.turns",
          "conversation.chat",
          "conversation.trajectory",
          "conversation.context",
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
    if (FINITE.contains(type)) return new DispatchPolicy(Kind.FINITE, List.of());
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
