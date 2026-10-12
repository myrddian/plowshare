package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials.Definition;

/** Operator-configured destination policy, applied before metadata or token requests. */
public interface ProviderEndpointPolicy {
  void require(Definition definition);
}
