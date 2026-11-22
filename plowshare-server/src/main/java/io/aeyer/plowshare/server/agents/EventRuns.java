package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.events.AgentRunner.ScheduledTask;
import java.util.function.BiConsumer;

/**
 * Normal event execution, including grants, hooks, accounting, cancellation and conversation logs.
 */
public interface EventRuns {
  /** A trigger may carry the saved instruction of an admitted scheduled firing. */
  JobStore.EventRun submitTriggeredEvent(
      AgentDefinition definition,
      String utterance,
      Home home,
      String session,
      Integer maxModelCalls,
      TurnCap cap,
      String callerHandle,
      Speaker speaker,
      BiConsumer<String, Outcome> ended,
      ScheduledTask scheduled);

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

  /** Causation must be durable before a Relay-started job or its lifecycle notice can run. */
  JobStore.EventRun submitEvent(
      AgentDefinition definition,
      String utterance,
      Home home,
      String session,
      Integer maxModelCalls,
      TurnCap cap,
      String callerHandle,
      Speaker speaker,
      BiConsumer<String, Outcome> ended,
      RelayCausation causation);
}
