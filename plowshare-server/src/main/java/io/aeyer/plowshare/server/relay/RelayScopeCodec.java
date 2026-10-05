package io.aeyer.plowshare.server.relay;

/** Canonical database scope keys; encoded strings never substitute for typed domain scopes. */
final class RelayScopeCodec {
  private RelayScopeCodec() {}

  static String write(Relay.TopicKey topic) {
    return topic.scope() instanceof Relay.ProjectScope project
        ? "project:" + project.projectId()
        : "system";
  }

  static Relay.Scope read(String key) {
    if ("system".equals(key)) return Relay.SystemScope.SERVER;
    if (key == null || !key.matches("project:[1-9][0-9]{0,18}"))
      throw new IllegalStateException("Invalid persisted Relay scope");
    try {
      return new Relay.ProjectScope(Long.parseLong(key.substring(8)));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("Invalid persisted Relay scope");
    }
  }

  static Long project(Relay.TopicKey topic) {
    return topic.scope() instanceof Relay.ProjectScope project ? project.projectId() : null;
  }
}
