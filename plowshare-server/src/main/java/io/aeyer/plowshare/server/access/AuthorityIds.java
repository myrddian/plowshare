package io.aeyer.plowshare.server.access;

import java.util.UUID;

/** Distinct internal identity domains; parsing a UUID confers no authority. */
public final class AuthorityIds {
  private AuthorityIds() {}

  private static UUID required(UUID value) {
    if (value == null || value.equals(new UUID(0, 0)))
      throw new IllegalArgumentException("Authority identities require a nonzero UUID");
    return value;
  }

  public record AccountId(UUID value) {
    public AccountId {
      value = required(value);
    }
  }

  public record RoleId(UUID value) {
    public RoleId {
      value = required(value);
    }
  }

  public record ScopeId(UUID value) {
    public ScopeId {
      value = required(value);
    }
  }
}
