package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.a2a.model.AgentCard;
import io.aeyer.plowshare.a2a.model.ExternalMessage;
import io.aeyer.plowshare.a2a.model.ExternalResult;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** External A2A protocol boundary. Calls are single attempts; errors never authorize replay. */
public interface RemotePeer extends AutoCloseable {
  AgentCard agentCard() throws IOException;

  Observation send(UUID identity, ExternalMessage content) throws IOException;

  /** Read only this peer's named task/context; reject a response naming different identities. */
  Observation status(String task, String context) throws IOException;

  /**
   * Submit cancellation once, preserving uncertainty and checking returned task/context identity.
   */
  Observation cancel(String task, String context) throws IOException;

  @Override
  void close();

  /** Remote evidence, not local execution authority or usage accounting. */
  record Observation(
      String state, String task, String context, ExternalResult result, String error) {
    public Observation {
      if (!Set.of(
              "WORKING",
              "INPUT_REQUIRED",
              "AUTH_REQUIRED",
              "COMPLETED",
              "FAILED",
              "CANCELED",
              "REJECTED",
              "UNKNOWN")
          .contains(Objects.requireNonNull(state)))
        throw new IllegalArgumentException("Invalid A2A observation state");
      if (task != null) RelayWork.identity(task);
      if (context != null) RelayWork.identity(context);
      if (error != null) RelayWork.text(error, 4096);
      if (task != null && context == null)
        throw new IllegalArgumentException("A remote task needs its context");
      if (result instanceof ExternalResult.TaskResult remote) {
        String expected = remote.task().status().state().substring("TASK_STATE_".length());
        if (expected.equals("SUBMITTED")) expected = "WORKING";
        if (!Objects.equals(task, remote.task().id())
            || !Objects.equals(context, remote.task().contextId())
            || !state.equals("UNKNOWN") && !state.equals(expected))
          throw new IllegalArgumentException("Foreign or inconsistent remote observation");
      }
      if (result instanceof ExternalResult.MessageResult remote
          && (!state.equals("COMPLETED")
              || task != null
              || !Objects.equals(context, remote.message().contextId())))
        throw new IllegalArgumentException("Inconsistent direct A2A reply");
      if (result == null && !Set.of("FAILED", "UNKNOWN", "REJECTED").contains(state))
        throw new IllegalArgumentException("Missing remote result");
      if (result == null && error == null)
        throw new IllegalArgumentException("Missing failure diagnostic");
    }
  }
}
