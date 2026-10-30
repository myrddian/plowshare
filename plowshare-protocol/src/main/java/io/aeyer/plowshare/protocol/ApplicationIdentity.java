package io.aeyer.plowshare.protocol;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Application-owned execution identity. Metadata is recoverable; bearer secrets are returned once.
 */
public final class ApplicationIdentity {
  private ApplicationIdentity() {}

  public record Declaration(String handle, String role) {
    public Declaration {
      if (handle == null
          || !handle.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
          || role == null
          || !java.util.Set.of("VIEWER", "CONTRIBUTOR").contains(role))
        throw new IllegalArgumentException(
            "Application service needs a handle and VIEWER or CONTRIBUTOR role");
    }
  }

  public enum State {
    RESERVED,
    ACTIVE
  }

  public record Binding(
      String project,
      Declaration service,
      UUID tokenId,
      String principal,
      State state,
      boolean credentialIssued,
      OffsetDateTime expiresAt,
      OffsetDateTime revokedAt,
      UUID credentialRequestId) {
    public Binding {
      ApplicationIdentity.project(project);
      Objects.requireNonNull(service);
      Objects.requireNonNull(tokenId);
      Objects.requireNonNull(state);
      Objects.requireNonNull(expiresAt);
      if (!("@service/" + tokenId).equals(principal))
        throw new IllegalArgumentException(
            "Application principal must belong to its retained token");
    }
  }

  /** Null identity preserves manual executionAccount Applications without adopting their tokens. */
  public record Status(String project, Binding identity) {
    public Status {
      ApplicationIdentity.project(project);
      if (identity != null && !project.equals(identity.project()))
        throw new IllegalArgumentException("Foreign Application identity");
    }
  }

  public record Issue(String project, UUID requestId, int expiresInDays, boolean rotate) {
    public Issue {
      ApplicationIdentity.project(project);
      Objects.requireNonNull(requestId);
      if (expiresInDays < 1 || expiresInDays > 365)
        throw new IllegalArgumentException("expiresInDays must be 1–365");
    }
  }

  public record Receipt(
      UUID requestId, String project, UUID tokenId, String principal, OffsetDateTime expiresAt) {
    public Receipt {
      Objects.requireNonNull(requestId);
      ApplicationIdentity.project(project);
      Objects.requireNonNull(tokenId);
      Objects.requireNonNull(expiresAt);
      if (!("@service/" + tokenId).equals(principal))
        throw new IllegalArgumentException("Foreign Application token receipt");
    }
  }

  public record Credential(Receipt receipt, String credential) {
    public Credential {
      Objects.requireNonNull(receipt);
      if (credential == null || !credential.matches("pss_[0-9a-f]{48}"))
        throw new IllegalArgumentException("Invalid Application credential");
    }

    @Override
    public String toString() {
      return "Credential[receipt=" + receipt + ", credential=<redacted>]";
    }
  }

  public static void project(String value) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > 256
        || value.codePoints().anyMatch(Character::isISOControl)
        || value.startsWith("personal:")
        || value.startsWith("Personal:")
        || value.startsWith("client:")
        // ADR 0042, list (c): an Application identity and its credential are an externally
        // addressable principal for a project. A child is addressed only through its container.
        || ContainedProjects.name(value))
      throw new IllegalArgumentException("Choose an ordinary Application project");
  }
}
