package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.HostedToolScopes;
import io.aeyer.plowshare.protocol.ProviderCredentials;
import java.util.*;

/**
 * Admission-time credential/executor selection persisted on the owning Relay intent, never a
 * separate ledger.
 */
public record HostedInvocation(
    UUID binding,
    long generation,
    UUID registration,
    String owner,
    String executor,
    String credentialProject,
    UUID connection,
    UUID connectionRevision,
    String subject,
    UUID credentialGeneration,
    String agent) {
  public HostedInvocation {
    Objects.requireNonNull(binding);
    HostedToolScopes.generation(generation);
    Objects.requireNonNull(registration);
    ProviderCredentials.subject(owner);
    ProviderCredentials.subject(executor);
    ProviderCredentials.project(credentialProject);
    Objects.requireNonNull(connection);
    Objects.requireNonNull(connectionRevision);
    ProviderCredentials.subject(subject);
    Objects.requireNonNull(credentialGeneration);
    if (agent == null || !agent.matches("[a-z][a-z0-9]*(?:[_-][a-z0-9]+)*") || agent.length() > 64)
      throw new IllegalArgumentException("Invalid hosted invocation agent");
  }
}
