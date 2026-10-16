package io.aeyer.plowshare.server.acting;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable authority carried by one request. The actor is the authenticated transport identity;
 * the subject owns the admitted work. Construct only after authentication and proof verification.
 * Structural validation here is not proof of permission, and a context is not a permission lease.
 *
 * <p>This value contains no credential, socket or mutable connection-wide selection. Direct callers
 * continue through ordinary authorization; only delegated callers use {@link ActingAuthorization}.
 */
public sealed interface RequestAuthority
    permits RequestAuthority.Direct, RequestAuthority.Delegated {
  String actor();

  String subject();

  /** Human or service-account requests without actingFor keep one identity. */
  record Direct(String actor) implements RequestAuthority {
    public Direct {
      actor = ActingValues.actor(actor);
    }

    @Override
    public String subject() {
      return actor;
    }
  }

  /** The Application remains the actor even when the effective subject is a human. */
  record Delegated(String actor, String subject, Delegation delegation)
      implements RequestAuthority {
    public Delegated {
      actor = ActingValues.serviceActor(actor);
      subject = ActingValues.account(subject);
      Objects.requireNonNull(delegation, "delegation");
    }
  }

  /**
   * First-slice acting-token reference plus the scope admitted earlier. The proof ID names a
   * durable token record, not the temporary socket context or an incoming message routing context.
   * Rechecks intersect current authority with this scope so later widening cannot expand admitted
   * work.
   */
  record Delegation(String application, String grant, UUID proof, ActingScope scope) {
    public Delegation {
      application = ActingValues.application(application);
      grant = ActingValues.account(grant);
      Objects.requireNonNull(proof, "proof");
      Objects.requireNonNull(scope, "scope");
    }
  }
}
