package io.aeyer.plowshare.protocol;

import java.util.*;

/**
 * Provider-scope connections over the authenticated socket. No request accepts an owner account.
 */
public final class ToolScopes {
  private ToolScopes() {}

  public record Connect(
      String project,
      String scope,
      String provider,
      String prefix,
      List<String> grants,
      List<String> agents,
      int leaseSeconds) {
    public Connect {
      RelayPort.identity(project);
      identifier(scope);
      ToolScopes.provider(provider);
      if (prefix == null
          || !prefix.matches("[a-z][a-z0-9_]*_")
          || prefix.length() > 48
          || leaseSeconds < 1
          || leaseSeconds > 300)
        throw new IllegalArgumentException("Invalid provider prefix or lease");
      grants = ToolScopes.grants(grants);
      agents = List.copyOf(agents);
      if (agents.isEmpty() || agents.size() > 128 || new HashSet<>(agents).size() != agents.size())
        throw new IllegalArgumentException("Invalid assigned agents");
      agents.forEach(ToolScopes::identifier);
    }
  }

  public record Query(String project) {
    public Query {
      RelayPort.identity(project);
    }
  }

  public record Disconnect(String project, String scope) {
    public Disconnect {
      RelayPort.identity(project);
      identifier(scope);
    }
  }

  /**
   * provider is the isolated Relay routing name; sourceProvider is the owner's logical provider.
   */
  public record Connection(
      String project,
      String scope,
      String sourceProvider,
      String provider,
      String account,
      String prefix,
      List<String> grants,
      List<String> agents,
      int leaseSeconds) {
    public Connection {
      RelayPort.identity(project);
      identifier(scope);
      ToolScopes.provider(sourceProvider);
      ToolScopes.provider(provider);
      RelayPort.identity(account);
      new Connect(project, scope, sourceProvider, prefix, grants, agents, leaseSeconds);
      grants = List.copyOf(grants);
      agents = List.copyOf(agents);
    }
  }

  public record Connections(List<Connection> connections) {
    public Connections {
      connections = List.copyOf(connections);
    }
  }

  public record Disconnected(String project, String scope, boolean disconnected) {}

  private static void identifier(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9]*(?:[_-][a-z0-9]+)*") || value.length() > 64)
      throw new IllegalArgumentException("Invalid tool scope or agent");
  }

  private static void provider(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*") || value.length() > 48)
      throw new IllegalArgumentException("Invalid tool provider");
  }

  private static List<String> grants(List<String> values) {
    if (values == null
        || values.isEmpty()
        || values.size() > 128
        || new HashSet<>(values).size() != values.size())
      throw new IllegalArgumentException("Invalid scope grants");
    for (String value : values)
      if (!"*".equals(value)
          && (value == null
              || !value.matches("[a-z][a-z0-9]*(?:_[a-z0-9]+)*")
              || value.length() > 64)) throw new IllegalArgumentException("Invalid scope tool");
    if (values.contains("*") && values.size() != 1)
      throw new IllegalArgumentException("Mixed wildcard scope");
    return List.copyOf(values);
  }
}
