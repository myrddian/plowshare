package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AccessRequest.Resource;
import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.RunAdmission;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.goals.GoalRepository.Binding;
import io.aeyer.plowshare.server.messaging.MessageService.InstanceView;
import java.util.List;
import java.util.Optional;

/** The existing turn and authority owners, viewed through the goal scheduler's narrow port. */
interface GoalExecution {
  record Prepared(
      ConversationRecord conversation, AgentDefinition agent, Admission authority, Budget budget) {
    Prepared(ConversationRecord conversation, AgentDefinition agent, Admission authority) {
      this(conversation, agent, authority, conversation.budget());
    }
  }

  Prepared prepareDedicated(String executor, InstanceView instance);

  List<GoalHandoffs.Candidate> handoffCandidates(GoalHandoffs.Caller caller);

  AgentDefinition handoffTarget(GoalHandoffs.Caller caller, String agent);

  default boolean humanHandoff(GoalHandoffs.Caller caller) {
    return false;
  }

  Prepared prepareHandoff(
      GoalHandoffs.Caller caller, InstanceView instance, AgentDefinition target);

  default Budget statusBudget(Binding binding, ConversationRecord conversation) {
    return conversation.budget();
  }

  ConversationRecord conversation(String id);

  Prepared prepare(String executor, ConversationRecord conversation, String agent);

  Prepared restore(Binding binding);

  void require(Prepared prepared);

  void requireTargets(Prepared prepared, Home home, List<Resource> references);

  boolean idle(String conversation);

  Optional<String> start(
      Binding binding, Prepared prepared, String opening, RunAdmission admission);

  void cancel(String job);

  boolean active(String job);
}
