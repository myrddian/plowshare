package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.ToolCall;
import java.util.List;

/**
 * A retained safe suspension point, ordered from root to requesting delegate. Pending calls have
 * not finished: the first is the delegated call or budget request currently waiting, and later
 * calls have not started. Recovery returns those later calls as explicitly unexecuted rather than
 * replaying effects. Completed tool results remain in the original conversation logs.
 */
public record BudgetCheckpoint(String session, String executor, List<Frame> frames) {
  public BudgetCheckpoint {
    requireToken(executor);
    if (session != null) requireToken(session);
    frames = List.copyOf(frames);
    if (frames.isEmpty() || frames.size() > 128) {
      throw new IllegalArgumentException("A budget checkpoint needs a bounded execution stack");
    }
    if (frames.stream().map(Frame::conversation).distinct().count() != frames.size())
      throw new IllegalArgumentException("A continuation stack cannot repeat a conversation");
  }

  private static void requireToken(String value) {
    if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("A bounded continuation identity is required");
  }

  public record Frame(
      String conversation,
      String agent,
      int turn,
      int steps,
      Integer stepLimit,
      List<ToolCall> pending) {
    public Frame {
      requireToken(conversation);
      requireToken(agent);
      pending = List.copyOf(pending);
      if (turn < 1 || steps < 0 || stepLimit != null && stepLimit < 1 || pending.size() > 1024) {
        throw new IllegalArgumentException("Invalid budget continuation frame");
      }
    }
  }
}
