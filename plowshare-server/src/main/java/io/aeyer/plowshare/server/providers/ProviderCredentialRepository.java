package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Specialist persistence. All writes and locking reads join the caller's short transaction. */
public interface ProviderCredentialRepository {
  record Stored(
      Credential metadata,
      CredentialCipher.Sealed material,
      UUID attempt,
      String stateHash,
      String initiator,
      OffsetDateTime intentExpiresAt) {
    public Stored {
      java.util.Objects.requireNonNull(metadata);
      boolean running = metadata.state() == State.PENDING || metadata.state() == State.REFRESHING;
      if (running != (attempt != null)
          || running != (intentExpiresAt != null)
          || running != (initiator != null)
          || (metadata.state() == State.PENDING) != (stateHash != null)
          || (metadata.state() == State.NONE
                  || metadata.state() == State.REVOKED
                  || metadata.state() == State.UNCERTAIN)
              != (material == null))
        throw new IllegalArgumentException("Inconsistent credential lifecycle");
      if ((material == null) != (metadata.keyId() == null))
        throw new IllegalArgumentException("Credential encryption metadata disagrees");
      if (material != null && !material.keyId().equals(metadata.keyId()))
        throw new IllegalArgumentException("Credential encryption metadata disagrees");
      if (stateHash != null && !stateHash.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid OAuth state digest");
      if (initiator != null) io.aeyer.plowshare.protocol.ProviderCredentials.subject(initiator);
    }

    public String context() {
      return "provider-credential/1/"
          + metadata.connection()
          + "/"
          + metadata.subject()
          + "/"
          + metadata.connectionRevision()
          + "/"
          + metadata.generation();
    }
  }

  record Committed(String actor, Receipt receipt) {}

  /** Serializes new connection admission and project deletion before connection rows are locked. */
  void lockProject(String project);

  List<Connection> list(String project);

  Optional<Connection> connection(String project, UUID id, boolean lock);

  void create(Connection connection);

  void update(Connection connection, UUID expectedRevision);

  Optional<Stored> credential(UUID connection, String subject, boolean lock);

  void store(Stored stored, UUID expectedGeneration);

  /** Connection changes fence every old credential and discard authorization/refresh material. */
  void revokeAll(UUID connection, UUID connectionRevision);

  Optional<Stored> authorization(String stateHash);

  /** Unique mutation UUID reservation is committed atomically with the redacted outcome. */
  void commit(String actor, Receipt receipt);

  Optional<Committed> receipt(UUID requestId);
}
