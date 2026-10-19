package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Durable role issuance. Implementations serialize by destination scope, recheck live issuing
 * authority under lock, and commit grant/audit together. Failures propagate; uncertain mutations
 * must be reconciled by their grant ID rather than automatically replayed.
 */
public interface ScopedGrantRepository {
  enum Coverage {
    SELF,
    ORDINARY_PROJECT_CONTROL,
    ALL_SCOPES
  }

  record Binding(
      UUID id,
      GrantRecipient recipient,
      ScopeId scope,
      StandardRole role,
      StandardRole requested,
      Coverage coverage,
      GrantRecipient issuer,
      Optional<UUID> authority,
      Optional<UUID> issuingToken,
      long version,
      int roleVersion,
      boolean managerForced,
      String provenance,
      OffsetDateTime createdAt,
      Optional<OffsetDateTime> expiresAt,
      Optional<OffsetDateTime> revokedAt,
      Set<AuthorityAction> actions,
      String recipientHandle,
      Optional<String> project) {
    public Binding {
      Objects.requireNonNull(id);
      Objects.requireNonNull(recipient);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(role);
      Objects.requireNonNull(requested);
      Objects.requireNonNull(coverage);
      Objects.requireNonNull(issuer);
      Objects.requireNonNull(authority);
      Objects.requireNonNull(issuingToken);
      Objects.requireNonNull(createdAt);
      Objects.requireNonNull(expiresAt);
      Objects.requireNonNull(revokedAt);
      actions = Set.copyOf(actions);
      Objects.requireNonNull(recipientHandle);
      Objects.requireNonNull(project);
      if (version < 1 || roleVersion != 1 || provenance == null || provenance.isBlank())
        throw new IllegalArgumentException("Invalid grant state");
    }
  }

  /** Live universal GLOBAL_READ membership; no stored grant, issuer or project membership. */
  boolean globalReadEligible(io.aeyer.plowshare.server.access.AuthorityIds.AccountId account);

  /** Returns current effective chains only, never direct-account bindings for an agent. */
  List<Binding> live(GrantRecipient recipient);

  /** Live project account bindings; token callers use their owner's bindings plus their ceiling. */
  List<Binding> projectBindings(String project, String account);

  /** Live chain from this binding through its root, in child-to-parent order; empty if invalid. */
  List<Binding> lineage(UUID id);

  /** Reads retained state for reconciliation; a stored record is not proof of live authority. */
  Optional<Binding> find(UUID id);

  /** The trusted caller allocates the grant ID before mutation, retaining it for reconciliation. */
  record Issue(
      UUID id,
      GrantRecipient recipient,
      ScopeId scope,
      StandardRole requested,
      GrantRecipient issuer,
      UUID authority,
      Optional<OffsetDateTime> expiresAt,
      boolean forceManager) {
    public Issue {
      Objects.requireNonNull(id);
      if (id.equals(new UUID(0, 0)))
        throw new IllegalArgumentException("Grant identity is required");
      Objects.requireNonNull(recipient);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(requested);
      Objects.requireNonNull(issuer);
      Objects.requireNonNull(authority);
      Objects.requireNonNull(expiresAt);
    }
  }

  /**
   * Result exposes descoping before callers perform dependent work. No partial issue on refusal.
   */
  Binding issue(Issue request);

  /**
   * Expected version fences stale mutations; repeated/uncertain calls are not silently replayed.
   */
  Binding revoke(UUID id, long version, GrantRecipient issuer, UUID authority);
}
