package io.aeyer.plowshare.protocol;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Socket-bound subject proof exchange. Context IDs are routing references, never credentials. */
public final class ActingContext {
  private ActingContext() {}

  /** Explicit request selection; omission alone selects the Application's ordinary identity. */
  public record Selection(UUID context) {
    public Selection {
      Objects.requireNonNull(context, "context");
    }
  }

  public record Open(String application, String grant, Proof proof) {
    public Open {
      ApplicationIdentity.project(application);
      if (grant == null || !grant.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
        throw new IllegalArgumentException("Choose a bounded acting grant");
      Objects.requireNonNull(proof, "proof");
    }
  }

  /** Logging proof input must never reveal its bearer material. */
  public record Proof(String kind, String credential) {
    public Proof {
      if (!"acting-token".equals(kind)
          || credential == null
          || !credential.matches("psa_[0-9a-f]{48}"))
        throw new IllegalArgumentException("Choose an acting-token proof");
    }

    @Override
    public String toString() {
      return "ActingProof[kind=acting-token, credential=REDACTED]";
    }
  }

  /** No token ID, digest or bearer material is exposed by the exchange. */
  public record Opened(UUID context, String subject, Instant expiresAt) {
    public Opened {
      Objects.requireNonNull(context, "context");
      if (subject == null || !subject.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
        throw new IllegalArgumentException("Invalid acting subject");
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }
}
