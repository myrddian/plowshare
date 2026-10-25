package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import java.util.*;

/** Immutable admission evidence and live checks for existing bounded execution paths. */
public interface OperationAuthority {
  record Support(AuthorityAction action, UUID grant) {
    public Support {
      Objects.requireNonNull(action);
      Objects.requireNonNull(grant);
    }
  }

  record Admission(
      UUID id,
      String executor,
      AccountId account,
      ScopeId scope,
      Home home,
      GrantRecipient source,
      List<Support> support,
      boolean globalRead) {
    public Admission(
        UUID id,
        String executor,
        AccountId account,
        ScopeId scope,
        Home home,
        GrantRecipient source,
        List<Support> support) {
      this(id, executor, account, scope, home, source, support, false);
    }

    public Admission {
      Objects.requireNonNull(id);
      Objects.requireNonNull(account);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(home);
      Objects.requireNonNull(source);
      if (executor == null || executor.isBlank() || home.isGlobal())
        throw new IllegalArgumentException("Explicit executor and execution home required");
      if (source.agent().isPresent())
        throw new IllegalArgumentException("Account operation source required");
      support = List.copyOf(support);
      if (globalRead && support.stream().noneMatch(s -> s.action() == AuthorityAction.READ))
        throw new IllegalArgumentException("Global READ needs a retained READ action");
      if (support.isEmpty() || support.size() > 256)
        throw new IllegalArgumentException("Bounded supporting authority required");
    }

    public Set<AuthorityAction> actions() {
      var actions = EnumSet.noneOf(AuthorityAction.class);
      support.forEach(item -> actions.add(item.action()));
      return Set.copyOf(actions);
    }
  }

  /** A durable execution envelope; provenance and the budget owner are never inferred on resume. */
  record Conversation(
      String id,
      String agent,
      Admission admission,
      String parent,
      AllowanceId budget,
      String capabilities) {
    public Conversation {
      if (id == null
          || id.isBlank()
          || agent == null
          || agent.isBlank()
          || budget == null
          || id.length() > 1024
          || agent.length() > 1024
          || (parent != null && (parent.isBlank() || parent.length() > 1024)))
        throw new IllegalArgumentException("Bound conversation, agent and budget required");
      Objects.requireNonNull(admission);
      if (capabilities == null || !capabilities.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Retained capability fingerprint required");
      if (id.equals(parent)) throw new IllegalArgumentException("A child cannot parent itself");
    }
  }

  /** Reads immutable evidence only; callers must still recheck live authority. */
  Optional<Conversation> conversation(String id);

  /** Attaches an existing conversation exactly once; a different binding is never substituted. */
  void bind(Conversation conversation);

  /** Narrows a retained parent, preserving exact supporting grants and immutable ancestry. */
  Admission derive(Admission parent, Set<AuthorityAction> actions);

  /**
   * Narrows a retained parent across one containment hop into a confined child.
   *
   * <p>{@link #derive} narrows actions while holding scope, account and home fixed, so it cannot
   * express this hop. {@code contain} is its sibling and the only way a contained admission comes
   * into being. It issues no authority: the child's support is exactly the parent's own grant rows
   * for the narrowed actions, so a child's authority is a projection of its parent's. The account,
   * executor and grant source never change, Global knowledge READ is dropped unconditionally
   * because it sits outside every containment subtree, and the requested actions must be a subset
   * of what the parent retains. There is therefore no sequence of calls that yields an admission
   * holding an action its parent does not hold: every hop only ever drops.
   *
   * <p>{@code child} must be recorded as contained by this parent's scope. It is a caller-supplied
   * value and not evidence — the record is freely constructible and no repository path returns one
   * — so the stored containment row is resolved rather than its fields believed. A child reaching
   * above or beside itself, at a grandparent, a sibling subtree or an uncontained scope, therefore
   * fails to resolve: a resolution failure refused before any authority is resolved, not a
   * permission later denied. Chain depth is bounded by injected server configuration rather than by
   * any argument of this call, and an absent or non-positive bound refuses rather than defaulting
   * to unlimited.
   *
   * <p>Resolving the child's own home is the one outward-facing act of the hop, and it is the same
   * read that resolves the child; it is why {@link IdentityRepository#containedHome} exists and why
   * {@link IdentityRepository#home(AuthorityScope)} keeps refusing a contained scope.
   */
  Admission contain(Admission parent, AuthorityScope.Contained child, Set<AuthorityAction> actions);

  /**
   * The explicit authority scope a legacy project home names.
   *
   * <p>A {@link Home} is the two-tier <em>memory retrieval</em> partition: a named project, or
   * {@code null} for the global tier. Admission asks a four-way question instead — a project, an
   * account's own space, Global knowledge, or administration — and the home cannot spell the
   * difference between the last three, because all of them are "no project". So the global tier is
   * refused here with the same refusal {@link #admit} gives it: it is a memory tier, not an
   * execution scope. A caller that means an account's own space resolves that scope explicitly
   * instead of presenting a home with no project.
   *
   * <p>This exists for the callers that still carry a request's project name. It is not a
   * permission check: resolving a scope grants nothing.
   */
  AuthorityScope scope(Home home);

  /**
   * Resolves and retains exact supporting grants for an explicit scope; never changes account or
   * home on refusal.
   *
   * <p>Takes the resolved {@link AuthorityScope} rather than a {@link Home} so that each caller
   * states which of the four authority concerns it means and a new concern cannot be absorbed into
   * an existing one. The admission still retains the legacy project home its resource fences
   * compare against; that home is resolved from the scope rather than from caller-supplied text, so
   * no admission can be recorded under a home its scope does not own.
   */
  Admission admit(String executor, AuthorityScope scope, Set<AuthorityAction> actions);

  /** Rechecks only the captured grants and current token/Application ceilings. */
  void require(Admission admission);

  /** Refuses targets outside the fixed operation home; domain/tool fences still apply. */
  void requireTargets(Admission admission, Home home, List<AccessRequest.Resource> references);

  /** Captured Global knowledge READ never widens WRITE, execution, or conversation access. */
  default void requireReadTargets(
      Admission admission, Home home, List<AccessRequest.Resource> references) {
    requireTargets(admission, home, references);
  }

  /** Associates an existing job identity before its executor can start. */
  void started(Admission admission, String job);
}
