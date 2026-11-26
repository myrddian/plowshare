package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AccessRequest.Resource;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.IdentityRepository.AccountKind;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationRecords;
import io.aeyer.plowshare.server.access.ResourceScopeRepository.Kind;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.ConversationDefinitions;
import io.aeyer.plowshare.server.agents.GoalTurn;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.OperationExecution;
import io.aeyer.plowshare.server.agents.RunAdmission;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.Turn.Refused;
import io.aeyer.plowshare.server.approvals.SuspendedRunCalls;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.goals.GoalRepository.Binding;
import java.util.List;
import java.util.Optional;

final class GoalRuntime implements GoalExecution {
  private final ConversationStore conversations;
  private final ConversationDefinitions definitions;
  private final OperationAuthority authority;
  private final OperationRecords operations;
  private final IdentityRepository identities;
  private final Turn turns;
  private final JobStore jobs;
  private final SuspendedRunCalls approvals;

  GoalRuntime(
      ConversationStore conversations,
      ConversationDefinitions definitions,
      OperationAuthority authority,
      OperationRecords operations,
      IdentityRepository identities,
      Turn turns,
      JobStore jobs,
      SuspendedRunCalls approvals) {
    this.conversations = conversations;
    this.definitions = definitions;
    this.authority = authority;
    this.operations = operations;
    this.identities = identities;
    this.turns = turns;
    this.jobs = jobs;
    this.approvals = approvals;
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
    var definition = resolve(conversation.id(), agent);
    var admission = authority.admit(executor, scope, OperationExecution.actions(definition));
    var prepared = new Prepared(conversation, definition, admission);
    require(prepared);
    return prepared;
  }

  @Override
  public Prepared restore(Binding binding) {
    var admission =
        operations
            .find(binding.admission())
            .filter(found -> found.executor().equals(binding.executor()))
            .orElseThrow(() -> new CallerFault("Goal admission is unavailable"));
    var conversation = conversation(binding.conversation());
    if (!eligible(conversation).id().equals(admission.scope())
        || !conversation.budget().identity().equals(binding.allowance()))
      throw new CallerFault("Goal conversation scope or allowance changed");
    var definition = resolve(conversation.id(), binding.agent());
    if (!fingerprint(definition).equals(binding.fingerprint()))
      throw new CallerFault("Goal agent definition changed");
    var prepared = new Prepared(conversation, definition, admission);
    require(prepared);
    return prepared;
  }

  @Override
  public void require(Prepared prepared) {
    var conversation = prepared.conversation();
    authority.requireTargets(
        prepared.authority(),
        conversation.home(),
        List.of(new Resource(Kind.CONVERSATION, conversation.id())));
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
        admission,
        () -> {
          if (!approvals.pending(binding.conversation()).isEmpty())
            throw new Refused(
                "Resolve the conversation's retained approval before resuming the goal");
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

  private AgentDefinition resolve(String conversation, String agent) {
    // A null session deliberately excludes all client-provided agent definitions and files.
    var definition =
        definitions.requireAgent(agent, definitions.callerForConversation(conversation, null));
    GoalTurn.requireAgent(definition);
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
