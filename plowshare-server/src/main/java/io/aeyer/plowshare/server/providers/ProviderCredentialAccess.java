package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials.Target;

/** Current account/project authority; never derives a credential subject from model input. */
public interface ProviderCredentialAccess {
  void read(String actor, String project);

  void configure(String actor, String project);

  /**
   * Null requested subject derives the caller; selecting a service principal requires admin
   * authority.
   */
  String subject(String actor, Target target);
}
