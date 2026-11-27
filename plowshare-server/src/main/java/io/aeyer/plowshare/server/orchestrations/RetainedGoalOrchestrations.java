package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.access.AccessRequest.Resource;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.access.OperationRecords;
import io.aeyer.plowshare.server.access.ResourceScopeRepository.Kind;
import io.aeyer.plowshare.server.agents.ConversationDefinitions;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.InformationLogAccess;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrationReceipts.Child;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrationReceipts.Key;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class RetainedGoalOrchestrations implements GoalOrchestrations {
  @FunctionalInterface
  interface Start {
    OrchestrationRecord start(
        Orchestrations.Start start, Admission caller, Key receipt, Runnable requireArmedWait);
  }

  @FunctionalInterface
  interface Cancel {
    boolean cancel(String run, String by);
  }

  private final ConversationDefinitions definitions;
  private final GrantedOrchestrations grants;
  private final OperationAuthority authority;
  private final OperationRecords operations;
  private final InformationLogAccess information;
  private final GoalOrchestrationReceipts receipts;
  private final Start starts;
  private final Cancel cancels;

  RetainedGoalOrchestrations(
      ConversationDefinitions definitions,
      GrantedOrchestrations grants,
      OperationAuthority authority,
      OperationRecords operations,
      InformationLogAccess information,
      GoalOrchestrationReceipts receipts,
      Start starts,
      Cancel cancels) {
    this.definitions = definitions;
    this.grants = grants;
    this.authority = authority;
    this.operations = operations;
    this.information = information;
    this.receipts = receipts;
    this.starts = starts;
    this.cancels = cancels;
  }

  @Override
  public Optional<Definition> granted(Caller caller, String name) {
    return liveGrant(caller, name).map(found -> new Definition(found.name(), found.hash()));
  }

  @Override
  public String start(Caller caller, Request request, Runnable requireArmedWait) {
    var definition = requireGrant(caller, request.name(), request.expectedHash());
    var owner = owner(caller);
    var key =
        key(owner, caller.authority(), request.invocation(), definition.name(), definition.hash());
    // A Goal conversation isn't an orchestration parent. Its retained admission is still the
    // conductor's authority ancestor, with its own independent orchestration allowance.
    var start =
        new Orchestrations.Start(
            definition,
            caller.authority().home(),
            request.request(),
            null,
            owner.conversation(),
            owner.agent(),
            owner.executor(),
            null,
            null,
            0,
            null,
            null);
    return starts.start(start, caller.authority(), key, requireArmedWait).id();
  }

  @Override
  public Optional<Run> inspect(Caller caller, UUID invocation, String name, String hash) {
    requireGrant(caller, name, hash);
    var owner = owner(caller);
    return receipts
        .find(key(owner, caller.authority(), invocation, name, hash))
        .map(
            run -> {
              var child = new Child(run.id(), run.conversation());
              if (!ownedChild(owner, child)) throw unavailable();
              if (run.endedAt() != null) requireReadable(caller.authority(), child);
              return run;
            });
  }

  @Override
  public boolean stillGranted(Caller caller, String name, String hash) {
    return exactGrant(caller, name, hash).isPresent();
  }

  @Override
  public boolean stillTerminal(
      Caller caller, UUID invocation, String name, String hash, Run expected) {
    if (expected.endedAt() == null || !OrchestrationState.of(expected.state()).terminal())
      return false;
    var owner = owner(caller);
    var child = new Child(expected.id(), expected.conversation());
    if (!receipts.stillTerminal(
            key(owner, caller.authority(), invocation, name, hash),
            child,
            expected.state(),
            expected.endedAt())
        || !ownedChild(owner, child)) return false;
    requireReadable(caller.authority(), child);
    return true;
  }

  @Override
  public boolean cancel(Owner owner, UUID invocation, String name, String hash) {
    return operations
        .find(owner.admission())
        .filter(admission -> admission.executor().equals(owner.executor()))
        .flatMap(admission -> receipts.active(key(owner, admission, invocation, name, hash)))
        .filter(child -> ownedChild(owner, child))
        .map(child -> cancels.cancel(child.id(), "Goal " + owner.conversation()))
        .orElse(false);
  }

  private Optional<OrchestrationDefinition> liveGrant(Caller caller, String name) {
    if (!caller.agent().orchestrations().contains(name)
        || !caller.authority().actions().contains(AuthorityAction.CONVERSATION_CREATE))
      return Optional.empty();
    authority.requireTargets(
        caller.authority(),
        caller.authority().home(),
        List.of(new Resource(Kind.CONVERSATION, caller.conversation())));
    var lookup = definitions.callerForConversation(caller.conversation(), null);
    var live = definitions.readAgent(caller.agent().name(), lookup);
    if (!live.name().equals(caller.agent().name())) return Optional.empty();
    return grants.granted(live, lookup, name);
  }

  private OrchestrationDefinition requireGrant(Caller caller, String name, String hash) {
    return exactGrant(caller, name, hash).orElseThrow(RetainedGoalOrchestrations::unavailable);
  }

  private Optional<OrchestrationDefinition> exactGrant(Caller caller, String name, String hash) {
    return liveGrant(caller, name)
        .filter(definition -> name.equals(definition.name()) && definition.hash().equals(hash));
  }

  private boolean ownedChild(Owner owner, Child child) {
    // This immutable edge also proves cleanup authority after live grants have been withdrawn.
    return operations
        .conversation(child.conversation())
        .filter(
            bound ->
                owner.conversation().equals(bound.parent())
                    && owner.executor().equals(bound.admission().executor()))
        .flatMap(bound -> operations.parent(bound.admission().id()))
        .filter(owner.admission()::equals)
        .isPresent();
  }

  private void requireReadable(Admission caller, Child child) {
    authority.requireReadTargets(
        caller, caller.home(), List.of(new Resource(Kind.CONVERSATION, child.conversation())));
    information.requireLog(child.conversation(), caller.executor());
  }

  private static Owner owner(Caller caller) {
    return new Owner(
        caller.conversation(),
        caller.agent().name(),
        caller.authority().executor(),
        caller.authority().id());
  }

  private static Key key(
      Owner owner, Admission authority, UUID invocation, String name, String hash) {
    return new Key(owner, invocation, authority.home().project(), name, hash);
  }

  private static CallerFault unavailable() {
    return new CallerFault("The retained orchestration is unavailable or its grant changed");
  }
}
