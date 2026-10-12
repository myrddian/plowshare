package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.UUID;

/**
 * Explicit refusal keeps existing deployments operational without silently inventing encryption
 * keys.
 */
public final class DisabledProviderConnections implements ProviderConnections {
  private static CallerFault disabled() {
    return new CallerFault(
        "Provider credential hosting is disabled; configure encryption keys, allowed origins and OAuth callback explicitly");
  }

  public List<Connection> list(String actor, String project) {
    throw disabled();
  }

  public Connection status(String actor, String project, UUID connection) {
    throw disabled();
  }

  public Receipt save(String actor, Save request) {
    throw disabled();
  }

  public Receipt change(String actor, Change request) {
    throw disabled();
  }

  public Credential credential(String actor, Target target) {
    throw disabled();
  }

  public Receipt put(String actor, Put request) {
    throw disabled();
  }

  public Receipt revoke(String actor, Mutation request) {
    throw disabled();
  }

  public Receipt rekey(String actor, Mutation request) {
    throw disabled();
  }

  public Authorization authorize(String actor, Mutation request) {
    throw disabled();
  }

  public Receipt refresh(String actor, Mutation request) {
    throw disabled();
  }

  public Receipt receipt(String actor, String project, UUID requestId) {
    throw disabled();
  }

  public Credential callback(String actor, String state, String issuer, String code) {
    throw disabled();
  }
}
