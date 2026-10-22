package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only identity compatibility boundary. No lookup grants membership, audience or execution.
 */
public interface IdentityRepository {
  enum AccountKind {
    USER,
    SERVICE,
    SERVICE_TOKEN,
    BUILTIN
  }

  record Account(AccountId id, String handle, AccountKind kind, boolean locked) {
    public Account {
      Objects.requireNonNull(id);
      Objects.requireNonNull(kind);
      if (handle == null || handle.isBlank())
        throw new IllegalArgumentException("Account locator is required");
      if (locked != (kind == AccountKind.BUILTIN))
        throw new IllegalArgumentException("Inconsistent account lifecycle");
    }
  }

  record Role(RoleId id, String name, boolean assignable, Optional<AccountId> intrinsicAccount) {
    public Role {
      Objects.requireNonNull(id);
      Objects.requireNonNull(intrinsicAccount);
      if (name == null || name.isBlank() || assignable && intrinsicAccount.isPresent())
        throw new IllegalArgumentException("Invalid role identity");
    }
  }

  Account account(String legacyHandle);

  Role role(RoleId id);

  AuthorityScope scope(ScopeId id);

  /** Resolves the exact legacy name, never interprets its text as a scope/account UUID. */
  AuthorityScope project(String legacyName);

  /**
   * The legacy project home a project-bearing scope is recorded under: the exact inverse of {@link
   * #project(String)}.
   *
   * <p>Knowledge and administration scopes have no project and are refused rather than answered
   * with {@link Home#global()}. A null project is the global <em>memory</em> tier, so handing it
   * back for an account's own knowledge scope would be the same conflation {@link
   * #legacyHome(Home)} refuses in the other direction — and a retained home of "no project" is
   * exactly what closed personal admission in the first place.
   */
  Home home(AuthorityScope scope);

  AuthorityScope.Personal personal(AccountId account);

  AuthorityScope.AccountKnowledge knowledge(AccountId account);

  /** Refuses missing/inconsistent built-ins; never creates replacement records on startup. */
  void verifyBuiltIns();

  /** Legacy null Home remains unclassified. Callers must resolve request omission elsewhere. */
  default AuthorityScope legacyHome(Home home) {
    Objects.requireNonNull(home, "home");
    if (home.isGlobal())
      throw new CallerFault("Legacy null project has no explicit authority scope");
    return project(home.project());
  }
}
