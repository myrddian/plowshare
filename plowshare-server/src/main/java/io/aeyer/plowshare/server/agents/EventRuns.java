package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import java.util.function.BiConsumer;

/**
 * Normal event execution, including grants, hooks, accounting, cancellation and conversation logs.
 */
public interface EventRuns {
  JobStore.EventRun submitEvent(
      AgentDefinition definition,
      String utterance,
      Home home,
      String session,
      Integer maxModelCalls,
      TurnCap cap,
      String callerHandle,
      Speaker speaker,
      BiConsumer<String, Outcome> ended);
}
