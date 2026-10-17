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

  /** Resolves and retains exact supporting grants; never changes account or home on refusal. */
  Admission admit(String executor, Home home, Set<AuthorityAction> actions);

  /** Rechecks only the captured grants and current token/Application ceilings. */
  void require(Admission admission);

  /** Refuses targets outside the fixed operation home; domain/tool fences still apply. */
  void requireTargets(Admission admission, Home home, List<AccessRequest.Resource> references);

  /** Associates an existing job identity before its executor can start. */
  void started(Admission admission, String job);
}
