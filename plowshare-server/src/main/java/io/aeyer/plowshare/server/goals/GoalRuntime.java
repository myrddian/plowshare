package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AccessRequest.Resource;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.IdentityRepository.AccountKind;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationRecords;
import io.aeyer.plowshare.server.access.ResourceScopeRepository.Kind;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.ConversationDefinitions;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.GoalTurn;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.OperationExecution;
import io.aeyer.plowshare.server.agents.RunAdmission;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.Turn.Refused;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.SuspendedRunCalls;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.goals.GoalRepository.Binding;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.messaging.MessageService.InstanceView;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class GoalRuntime implements GoalExecution {
  private final ConversationStore conversations;
  private final ConversationDefinitions definitions;
  private final OperationAuthority authority;
  private final OperationRecords operations;
  private final IdentityRepository identities;
  private final Turn turns;
  private final JobStore jobs;
  private final SuspendedRunCalls approvals;
  private final AllowanceRepository allowances;
  private final MessageService messages;
  private final InformationJobs information;

  GoalRuntime(
      ConversationStore conversations,
      ConversationDefinitions definitions,
      OperationAuthority authority,
      OperationRecords operations,
      IdentityRepository identities,
      Turn turns,
      JobStore jobs,
      SuspendedRunCalls approvals,
      AllowanceRepository allowances,
      MessageService messages,
      InformationJobs information) {
    this.conversations = conversations;
    this.definitions = definitions;
    this.authority = authority;
    this.operations = operations;
    this.identities = identities;
    this.turns = turns;
    this.jobs = jobs;
    this.approvals = approvals;
    this.allowances = allowances;
    this.messages = messages;
    this.information = information;
  }

  @Override
  public ConversationRecord conversation(String id) {
    return conversations
        .find(id)
        .orElseThrow(() -> new NotFoundFault("Conversation is unavailable"));
  }

  @Override
  public Prepared prepare(String executor, ConversationRecord conversation, String agent) {
    if (identities.account(executor).kind() != AccountKind.USER)
      throw new CallerFault("Goal controls require a direct user account");
    var scope = eligible(conversation);
    var definition = resolve(conversation.id(), agent, false);
    var admission = authority.admit(executor, scope, OperationExecution.actions(definition));
    var prepared = new Prepared(conversation, definition, admission);
    require(prepared);
    return prepared;
  }

  @Override
  public Prepared prepareDedicated(String executor, InstanceView instance) {
    if (identities.account(executor).kind() != AccountKind.USER)
      throw new CallerFault("Goal launch requires a direct user account");
    var conversation = conversation(instance.conversation());
    var scope = eligibleDedicated(conversation, instance.agent());
    var definition = resolve(conversation.id(), instance.agent(), true);
    var prepared =
        allocate(
            conversation, definition, authority.admit(executor, scope, actions(definition)), null);
    require(prepared);
    return prepared;
  }

  @Override
  public List<GoalHandoffs.Candidate> handoffCandidates(GoalHandoffs.Caller caller) {
    var lookup = callerLookup(caller);
    var live = liveCaller(caller.definition().name(), lookup);
    return caller.definition().calls().stream()
        .distinct()
        .filter(live.calls()::contains)
        .map(name -> candidate(caller, live, lookup, name))
        .flatMap(Optional::stream)
        .map(target -> new GoalHandoffs.Candidate(target.name(), target.description()))
        .toList();
  }

  @Override
  public AgentDefinition handoffTarget(GoalHandoffs.Caller caller, String agent) {
    if (!caller.definition().calls().contains(agent)) throw unavailableTarget();
    var lookup = callerLookup(caller);
    var live = liveCaller(caller.definition().name(), lookup);
    if (!live.calls().contains(agent)) throw unavailableTarget();
    return candidate(caller, live, lookup, agent).orElseThrow(GoalRuntime::unavailableTarget);
  }

  @Override
  public boolean humanHandoff(GoalHandoffs.Caller caller) {
    var parent = conversation(caller.conversation());
    var account = caller.admission().executor();
    if (parent.origin() != Origin.TURN
        || parent.lifecycle() != ConversationLifecycle.ACTIVE
        || !parent.home().equals(caller.admission().home())
        || identities.account(account).kind() != AccountKind.USER
        || conversations.ownerOf(parent.id()).filter(account::equals).isEmpty())
      throw new CallerFault("The human Goal return route is unavailable");
    return true;
  }

  private AgentDefinition liveCaller(String name, DefinitionResolver.Caller lookup) {
    var live = definitions.readAgent(name, lookup);
    if (!name.equals(live.name()) || !live.canUseSkill("goal"))
      throw new CallerFault("The calling agent's Goal Agent grant changed");
    return live;
  }

  private DefinitionResolver.Caller callerLookup(GoalHandoffs.Caller caller) {
    var admission = caller.admission();
    var parent = conversation(caller.conversation());
    if (parent.lifecycle() != ConversationLifecycle.ACTIVE
        || (parent.origin() != Origin.TURN && !caller.definition().name().equals(parent.agent()))
        || !parent.home().equals(admission.home())
        || !(authority.scope(parent.home()) instanceof AuthorityScope.Project project)
        || !project.id().equals(admission.scope())
        || !caller.definition().canUseSkill("goal")
        || !admission.actions().contains(AuthorityAction.CONVERSATION_CREATE))
      throw new CallerFault("Goal handoff requires the active calling agent's project admission");
    authority.requireTargets(
        admission, parent.home(), List.of(new Resource(Kind.CONVERSATION, parent.id())));
    return definitions.callerForConversation(parent.id(), null);
  }

  private Optional<AgentDefinition> candidate(
      GoalHandoffs.Caller caller,
      AgentDefinition live,
      DefinitionResolver.Caller lookup,
      String name) {
    try {
      var target = definitions.readAgent(name, lookup);
      if (!target.exported()
          || !target.name().equals(name)
          || !caller.admission().actions().containsAll(actions(target))
          || !AgentRegistry.permitsGoalHandoff(caller.definition(), target)
          || !AgentRegistry.permitsGoalHandoff(live, target)) return Optional.empty();
      GoalTurn.requireAgent(target, true);
      if (target.maxModelCalls() < 1) return Optional.empty();
      return Optional.of(target);
    } catch (CallerFault unavailable) {
      return Optional.empty();
    }
  }

  private static CallerFault unavailableTarget() {
    return new CallerFault("Choose an eligible Goal Agent granted to this calling agent");
  }

  @Override
  public Prepared prepareHandoff(
      GoalHandoffs.Caller caller, InstanceView instance, AgentDefinition target) {
    var conversation = conversation(instance.conversation());
    var scope = eligibleDedicated(conversation, instance.agent());
    if (!target.name().equals(instance.agent())
        || !scope.id().equals(caller.admission().scope())
        || !conversation.home().equals(caller.admission().home())) throw unavailableTarget();
    // Check live lineage in the transaction that commits the inbox and its launch receipt.
    var derived = authority.derive(caller.admission(), actions(target));
    // The objective can carry the caller's retained inputs. Pin those selections in the new
    // log in the same transaction as the launch, including when no native message is sent.
    information.inherit(caller.conversation(), conversation.id(), derived.executor());
    // Messaging keeps its own conversation shape. The goal row records the caller and its
    // independent allowance while the authority repository retains the exact parent edge.
    var prepared = allocate(conversation, target, derived, caller.conversation());
    require(prepared);
    return prepared;
  }

  private static Set<AuthorityAction> actions(AgentDefinition definition) {
    var actions = EnumSet.copyOf(OperationExecution.actions(definition));
    actions.add(AuthorityAction.CONVERSATION_CREATE);
    return actions;
  }

  private Prepared allocate(
      ConversationRecord conversation,
      AgentDefinition definition,
      OperationAuthority.Admission admission,
      String callingConversation) {
    var budget = allowances.bind(Budget.of(definition.maxModelCalls()));
    if (!budget.capped())
      throw new CallerFault("Goal Agent requires a finite model-call allowance");
    return new Prepared(conversation, definition, admission, budget, callingConversation);
  }

  @Override
  public Prepared restore(Binding binding) {
    var admission =
        operations
            .find(binding.admission())
            .filter(found -> found.executor().equals(binding.executor()))
            .orElseThrow(() -> new CallerFault("Goal admission is unavailable"));
    var conversation = conversation(binding.conversation());
    var scope =
        binding.instance() == null
            ? eligible(conversation)
            : eligibleDedicated(conversation, binding.agent());
    var budget =
        binding.instance() == null ? conversation.budget() : dedicatedBudget(binding, conversation);
    if (!scope.id().equals(admission.scope()) || !budget.identity().equals(binding.allowance()))
      throw new CallerFault("Goal conversation scope or allowance changed");
    var definition = resolve(conversation.id(), binding.agent(), binding.instance() != null);
    if (!fingerprint(definition).equals(binding.fingerprint()))
      throw new CallerFault("Goal agent definition changed");
    if (binding.callingAgent() != null) requireHandoff(binding, admission, definition);
    var prepared =
        new Prepared(conversation, definition, admission, budget, binding.callingConversation());
    require(prepared);
    return prepared;
  }

  private void requireHandoff(
      Binding binding, OperationAuthority.Admission admission, AgentDefinition definition) {
    if (operations.parent(admission.id()).isEmpty()) throw unavailableTarget();
    var parent = conversation(binding.callingConversation());
    if (!parent.home().equals(admission.home())) throw unavailableTarget();
    var caller =
        liveCaller(binding.callingAgent(), definitions.callerForConversation(parent.id(), null));
    if (!AgentRegistry.permitsGoalHandoff(caller, definition))
      throw new CallerFault("The calling agent's Goal Agent grant changed");
  }

  @Override
  public void require(Prepared prepared) {
    var conversation = prepared.conversation();
    // Apply the same source-log fence to restored handoffs and each active effect. This also
    // covers older handoffs that have a retained caller but predate input inheritance.
    var target = new Resource(Kind.CONVERSATION, conversation.id());
    var caller = prepared.callingConversation();
    if (caller != null) information.requireLog(caller, prepared.authority().executor());
    authority.requireTargets(
        prepared.authority(),
        conversation.home(),
        caller == null
            ? List.of(target)
            : List.of(target, new Resource(Kind.CONVERSATION, caller)));
  }

  @Override
  public void requireTargets(Prepared prepared, Home home, List<Resource> references) {
    // Confined tool references stay in the exact project, including reads.
    authority.requireTargets(prepared.authority(), home, references);
  }

  @Override
  public boolean idle(String conversation) {
    return !turns.isSpeaking(conversation);
  }

  @Override
  public Optional<String> start(
      Binding binding, Prepared prepared, String opening, RunAdmission admission) {
    return turns.speakGoal(
        binding.conversation(),
        prepared.agent(),
        opening,
        binding.allowance(),
        binding.instance() == null ? null : prepared.budget(),
        admission,
        () -> {
          var pending = approvals.pending(binding.conversation());
          var expected = admission.goal().approval();
          if (expected == null) {
            if (!pending.isEmpty())
              throw new Refused(
                  "Resolve the conversation's retained approval before resuming the goal");
            return;
          }
          if (pending.size() != 1
              || !expected.id().equals(pending.getFirst().approval())
              || !expected.call().equals(pending.getFirst().tool().id())
              || !pending.getFirst().released()
              || RunApproval.ASKED.equals(pending.getFirst().decision())) {
            throw new GoalWaits.Unconfirmed();
          }
        });
  }

  @Override
  public void cancel(String job) {
    jobs.cancel(job);
  }

  @Override
  public boolean active(String job) {
    try {
      return jobs.get(job).active();
    } catch (NotFoundFault gone) {
      return false;
    }
  }

  @Override
  public Budget statusBudget(Binding binding, ConversationRecord conversation) {
    return binding.instance() == null
        ? conversation.budget()
        : allowances.restore(new AllowanceId(binding.allowance()));
  }

  private Budget dedicatedBudget(Binding binding, ConversationRecord conversation) {
    var instance = messages.owned(binding.instance(), binding.executor());
    if (!instance.active()
        || !instance.lifetime().equals("persistent")
        || !instance.conversation().equals(conversation.id())
        || !instance.agent().equals(binding.agent()))
      throw new CallerFault("Goal Agent inbox is unavailable");
    return allowances.restore(new AllowanceId(binding.allowance()));
  }

  private AuthorityScope.Project eligibleDedicated(ConversationRecord record, String agent) {
    if (record.origin() != Origin.MESSAGE
        || record.lifecycle() != ConversationLifecycle.ACTIVE
        || !agent.equals(record.agent())
        || !(authority.scope(record.home()) instanceof AuthorityScope.Project project))
      throw new CallerFault("Goal Agent requires its own active project inbox");
    return project;
  }

  private AuthorityScope.Project eligible(ConversationRecord record) {
    if (record.origin() != Origin.TURN
        || record.lifecycle() != ConversationLifecycle.ACTIVE
        || record.home().isGlobal()
        || record.budget() == null
        || !record.budget().capped()
        || !record.budget().durable()
        || record.turnCap() != null && !record.turnCap().capped()
        || !(authority.scope(record.home()) instanceof AuthorityScope.Project project))
      throw new CallerFault("A goal requires an active project conversation with finite limits");
    return project;
  }

  private AgentDefinition resolve(String conversation, String agent, boolean dedicated) {
    // A null session deliberately excludes all client-provided agent definitions and files.
    var definition =
        definitions.requireAgent(agent, definitions.callerForConversation(conversation, null));
    GoalTurn.requireAgent(definition, dedicated);
    return definition;
  }

  static String fingerprint(AgentDefinition definition) {
    return GoalText.digest(
        OperationExecution.fingerprint(definition),
        definition.prompt(),
        definition.origin(),
        Integer.toString(definition.maxTurns()));
  }
}
