package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AccessRequest.Resource;
import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.RunAdmission;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.goals.GoalRepository.Binding;
import java.util.List;
import java.util.Optional;

/** The existing turn and authority owners, viewed through the goal scheduler's narrow port. */
interface GoalExecution {
  record Prepared(ConversationRecord conversation, AgentDefinition agent, Admission authority) {}

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
