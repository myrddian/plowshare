package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Process-local progress, never approval authority. Delegation executes on the same virtual thread;
 * only a budget suspension persists this stack, avoiding a database write on every model step.
 */
final class BudgetExecutionStack {
  private final ThreadLocal<Deque<Running>> stack = ThreadLocal.withInitial(ArrayDeque::new);

  Running enter(AgentDefinition agent, Transcript transcript, TurnCap cap) {
    Running running = new Running(agent, transcript, cap);
    stack.get().addLast(running);
    return running;
  }

  Running current() {
    return stack.get().getLast();
  }

  List<BudgetCheckpoint.Frame> snapshot() {
    return stack.get().stream().map(Running::snapshot).toList();
  }

  void denied() {
    stack.get().forEach(Running::denied);
  }

  final class Running implements AutoCloseable {
    private final AgentDefinition agent;
    private final Transcript transcript;
    private final TurnCap cap;
    private int steps;
    private List<ToolCall> pending = List.of();
    private boolean denied;
    private String pendingDecision;

    private Running(AgentDefinition agent, Transcript transcript, TurnCap cap) {
      this.agent = agent;
      this.transcript = transcript;
      this.cap = cap;
    }

    void progress(int steps, List<ToolCall> pending) {
      this.steps = steps;
      this.pending = List.copyOf(pending);
    }

    void denied() {
      denied = true;
    }

    boolean wasDenied() {
      return denied;
    }

    void pending(String text) {
      pendingDecision = text;
    }

    String pending() {
      return pendingDecision;
    }

    TurnCap cap() {
      return cap;
    }

    BudgetCheckpoint.Frame snapshot() {
      Transcript.Spoken spoken = transcript.spokenIn();
      if (spoken == null)
        throw new IllegalStateException("Budget recovery needs a durable transcript");
      UsageAttribution usage = transcript.usage();
      String attributedConversation = usage.conversations().id();
      if (attributedConversation != null && !spoken.conversationId().equals(attributedConversation))
        throw new IllegalStateException(
            "Budget recovery execution belongs to another conversation");
      int originalTurn =
          transcript.continuingTurn() == null ? spoken.turnOrdinal() : transcript.continuingTurn();
      // A failed-call retry can have several newer log segments. Its immutable execution
      // attribution still names the original task; checkpointing the latest failed segment
      // would omit the paid tool results from earlier segments after approval/restart.
      if (attributedConversation != null && usage.turnOrdinal() != null && usage.turnOrdinal() > 0)
        originalTurn = Math.toIntExact(usage.turnOrdinal());
      return new BudgetCheckpoint.Frame(
          spoken.conversationId(), agent.name(), originalTurn, steps, cap.ceiling(), pending);
    }

    @Override
    public void close() {
      if (stack.get().removeLast() != this)
        throw new IllegalStateException("Budget stack mismatch");
      if (stack.get().isEmpty()) stack.remove();
    }
  }
}
