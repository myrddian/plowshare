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

  /**
   * The project home a contained scope's <em>own</em> confined execution is recorded under.
   *
   * <p>This is a sibling of {@link #home(AuthorityScope)}, not a relaxation of it. That method's
   * refusal of a contained scope stands and is load-bearing: every existing caller holds a general
   * {@link AuthorityScope}, so answering there would have handed a child project a usable home
   * through every {@code Home}-keyed path in the server at once. This method's parameter type is
   * {@link AuthorityScope.Contained}, so no such caller can reach it without first matching on
   * containment — which is the explicit decision containment is supposed to force.
   *
   * <p>It exists because a containment hop changes home: a child has its own writable knowledge and
   * memory home inside its containment, and a contained admission whose home named its parent's
   * project would be fenced to the one project the child must not reach. The direction that stays
   * closed is the reverse one — {@link #project(String)} still refuses to resolve a contained scope
   * from a project name — so a contained admission knows its own home while nothing can travel from
   * that name back to the scope.
   *
   * <p><strong>The containment row is resolved, never trusted.</strong> A {@link
   * AuthorityScope.Contained} value is freely constructible with any id, project key and container,
   * and no repository path returns one, so every field of {@code scope} arrives caller-supplied:
   * comparing its {@code container()} against a caller's own parent scope only asks the caller to
   * agree with itself. So this resolves the stored row and binds four facts in the predicate — the
   * row for {@code scope.id()} exists, its kind is {@code CONTAINED}, its container is {@code
   * container}, and it maps to {@code scope.projectId()} — and refuses unless all four hold.
   *
   * <p>That is what makes reaching beside yourself a resolution failure rather than a denied
   * permission. Every child subtree's project carries the same reserved {@code contained:} prefix,
   * so the prefix predicate alone closes the *ordinary-project* substitution only: it stops a value
   * pointing at an ordinary project being answered with that project's home, and it cannot tell one
   * contained project from another. Only the container and project columns of the stored row
   * distinguish this parent's child from a sibling subtree's. Resolving a home grants nothing
   * either way.
   *
   * @param scope the child as the caller asserts it, every field of which is verified
   * @param container the scope that must be recorded as this child's container
   */
  Home containedHome(AuthorityScope.Contained scope, ScopeId container);

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
