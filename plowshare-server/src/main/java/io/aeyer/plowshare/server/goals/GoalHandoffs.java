package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import java.util.List;

/** Dedicated Goal Agent launch under an existing, server-admitted skill invocation. */
public interface GoalHandoffs {
  /** Source evidence comes from the initial skill invocation's retained speaker. */
  record Caller(
      String conversation,
      AgentDefinition definition,
      Admission admission,
      boolean human,
      String originalMessage) {
    public Caller(String conversation, AgentDefinition definition, Admission admission) {
      this(conversation, definition, admission, false, null);
    }
  }

  record Candidate(String agent, String description) {}

  boolean enabled();

  /** Lists only exact targets authorized by this caller's definition and retained admission. */
  List<Candidate> candidates(Caller caller);

  /** The start request ID is bound by the harness to the durable skill invocation. */
  GoalAgents.Started start(Caller caller, GoalAgents.Start request);

  /** Native launch request for a handoff which explicitly requested a reply. */
  default String launchMessage(Caller caller, GoalAgents.Started started) {
    throw new UnsupportedOperationException("Goal Messaging returns are unavailable");
  }
}
