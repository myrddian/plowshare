package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
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
      List<Support> support) {
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
      String budget,
      String capabilities) {
    public Conversation {
      if (id == null
          || id.isBlank()
          || agent == null
          || agent.isBlank()
          || budget == null
          || budget.isBlank()
          || id.length() > 1024
          || agent.length() > 1024
          || budget.length() > 1024
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

  /** Resolves and retains exact supporting grants; never changes account or home on refusal. */
  Admission admit(String executor, Home home, Set<AuthorityAction> actions);

  /** Rechecks only the captured grants and current token/Application ceilings. */
  void require(Admission admission);

  /** Refuses targets outside the fixed operation home; domain/tool fences still apply. */
  void requireTargets(Admission admission, Home home, List<AccessRequest.Resource> references);

  /** Associates an existing job identity before its executor can start. */
  void started(Admission admission, String job);
}
