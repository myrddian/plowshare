package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The task a run was given, or the refusal for a body that gave none.
 *
 * <p>Moved out of {@code AgentController.run}, where it was a plain presence check on the request's
 * own {@code task} field — no store, no domain object, nothing beyond the string and the agent's
 * name the refusal names for context. The name is a parameter rather than a lookup: by the point
 * this runs, {@code agents.Runs.start} has already resolved the {@link
 * io.aeyer.plowshare.server.agents.AgentDefinition} the task belongs to, and this factory has no
 * reason to resolve it again.
 */
public final class RequestedTask {

  private RequestedTask() {}

  /**
   * {@code task}, or a {@link CallerFault} naming {@code agentName} when it was left out or left
   * blank.
   *
   * @param task the body's {@code task} field
   * @param agentName the agent the refusal should name, for a caller who sees which agent was asked
   *     for nothing
   */
  public static String in(String task, String agentName) {
    if (task == null || task.isBlank()) {
      throw new CallerFault(
          "the agent '"
              + agentName
              + "' was given no task to do; 'task' says what it"
              + " is being asked for, in prose");
    }
    return task;
  }
}
