package io.aeyer.plowshare.server.relay.tools;

/**
 * Delegation stays in the provider path; admission pinning runs inside the intent's transaction.
 */
public interface HostedInvocationAuthority {
  boolean permits(RelayToolDefinition tool, String owner);

  HostedInvocation pin(RelayToolRepository.Intent intent);

  /**
   * Current retained-generation authority for an artifact producer; joins the caller's short
   * transaction.
   */
  default void authorizeArtifact(String actor, RelayToolRepository.Intent intent) {
    throw new io.aeyer.plowshare.server.faults.CallerFault(
        "Hosted artifact authority is unavailable");
  }

  HostedInvocationAuthority NONE =
      new HostedInvocationAuthority() {
        public boolean permits(RelayToolDefinition t, String a) {
          return false;
        }

        public HostedInvocation pin(RelayToolRepository.Intent i) {
          throw new IllegalStateException("Hosted provider authority is unavailable");
        }
      };
}
