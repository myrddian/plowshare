package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.events.AgentRunner.ScheduledTask;
import io.aeyer.plowshare.server.information.InformationContext;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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

  sealed interface SubscriptionSource
      permits LogSource, ProjectSource, PersonalSource, RevisionSource {}

  /** Retain the exact revision selection and the project which published the change. */
  record RevisionSource(long projectId, InformationContext context, UUID revision)
      implements SubscriptionSource {
    public RevisionSource {
      if (projectId < 1) throw new IllegalArgumentException("Invalid source project");
      Objects.requireNonNull(context);
      Objects.requireNonNull(revision);
    }
  }

  record LogSource(String log, Long projectId) implements SubscriptionSource {
    public LogSource(String log) {
      this(log, null);
    }

    public LogSource {
      Objects.requireNonNull(log);
      if (projectId != null && projectId < 1)
        throw new IllegalArgumentException("Invalid source project");
    }
  }

  /** A notice whose published content belongs to the Personal recipient. */
  record PersonalSource() implements SubscriptionSource {}

  record ProjectSource(long projectId) implements SubscriptionSource {
    public ProjectSource {
      if (projectId < 1) throw new IllegalArgumentException("Invalid Relay source project");
    }
  }

  /** Retained source evidence and the owning dispatch fence for one background skill. */
  record SubscriptionSkill(
      SubscriptionSource source,
      String skill,
      String skillHash,
      Runnable requireSource,
      Consumer<String> started) {
    public SubscriptionSkill {
      Objects.requireNonNull(source);
      Objects.requireNonNull(skill);
      Objects.requireNonNull(skillHash);
      Objects.requireNonNull(requireSource);
      Objects.requireNonNull(started);
    }
  }

  JobStore.EventRun submitSubscriptionSkill(
      AgentDefinition definition,
      String utterance,
      Home home,
      TurnCap cap,
      String owner,
      Speaker speaker,
      RelayCausation causation,
      SubscriptionSkill subscription);

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
