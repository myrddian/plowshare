package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials.*;

/**
 * Internal grant capability. The caller supplies owner authority, never an executor-selected human.
 */
public interface ProviderCredentialGrants {
  record Reference(Connection connection, Credential credential) {
    public Reference {
      java.util.Objects.requireNonNull(connection);
      java.util.Objects.requireNonNull(credential);
      if (!connection.connection().equals(credential.connection())
          || !connection.project().equals(credential.project())
          || !connection.revision().equals(credential.connectionRevision())
          || credential.state() != State.READY)
        throw new IllegalArgumentException("Foreign credential grant");
    }
  }

  record Header(String name, String value) {
    public Header {
      if (!java.util.Set.of("Authorization", "X-API-Key", "Api-Key").contains(name))
        throw new IllegalArgumentException("Invalid credential header");
      java.util.Objects.requireNonNull(value);
    }

    @Override
    public String toString() {
      return "Header[name=" + name + ", value=<redacted>]";
    }
  }

  /** Validates a configuration reference without requiring an authorized upstream grant. */
  Connection connection(String owner, Target target);

  /** Read-only eligibility snapshot. Admission and release always use require instead. */
  Reference inspect(String owner, Target target);

  /**
   * Locks connection then credential in the caller's transaction and rechecks current owner
   * authority.
   */
  Reference require(String owner, Target target);

  /**
   * Revalidates/decrypts the exact locked reference. Never releases refresh/client secrets or a
   * verifier.
   */
  Header header(Reference reference);
}
