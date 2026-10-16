package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.protocol.ApplicationIdentity.Binding;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Live, trusted policy reads backed by owning repositories. Callers receive typed values and never
 * SQL or credential material. Missing records refuse authorization; storage failures propagate
 * rather than grant access.
 *
 * <p>These reads do not acquire an effect lease. A later admitting service must recheck authority
 * within the owning intent transaction and at its later execution boundaries.
 */
public interface ActingPolicySource {
  /** Whether new delegated permission checks are enabled by server policy. */
  boolean enabled();

  /**
   * Current account validity, including a service token's expiry, revocation and owner's status.
   */
  boolean accountActive(String account);

  /**
   * Enabled human USER account; service accounts, token principals and bootstrap accounts refuse.
   */
  boolean subjectActive(String account);

  /** Exact Application-owned identity, including dormant, expired or revoked lifecycle state. */
  Optional<Binding> application(String application);

  /** Read only the named grant from the Application's currently active revision. */
  Optional<Grant> grant(String application, String grant);

  /** Reevaluate explicit eligibility and membership rules against their current owning records. */
  boolean eligible(String application, String grant, String subject);

  /** Immutable token metadata, including revoked records; never a digest or bearer credential. */
  Optional<Token> token(UUID id);

  enum ProofKind {
    ACTING_TOKEN
  }

  /** Empty accepted kinds represent withdrawn proof authority and grant no access. */
  record Grant(
      String application,
      String name,
      ProjectRole ceiling,
      boolean personal,
      Set<ProofKind> proofs) {
    public Grant {
      application = ActingValues.application(application);
      name = ActingValues.account(name);
      ceiling = ActingValues.ceiling(ceiling);
      proofs = Set.copyOf(proofs);
    }
  }

  /** Digest and bearer material are deliberately absent from permission-policy state. */
  record Token(
      UUID id,
      String application,
      String actor,
      String grant,
      String subject,
      ActingScope scope,
      Instant expiresAt,
      boolean revoked) {
    public Token {
      Objects.requireNonNull(id, "id");
      application = ActingValues.application(application);
      actor = ActingValues.serviceActor(actor);
      grant = ActingValues.account(grant);
      subject = ActingValues.account(subject);
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }
}
