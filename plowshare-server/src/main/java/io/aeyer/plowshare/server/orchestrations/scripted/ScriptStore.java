package io.aeyer.plowshare.server.orchestrations.scripted;

import io.aeyer.plowshare.server.todos.TodoItem;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable script journal. Script-owned state is read only by the sandbox/persistence boundary.
 * Callers receive validated commands and execution receipts, never a mutable script state tree.
 * Preparation records the evaluated state and command together before any command is dispatched.
 * Uncertain executions require reconciliation, not replay; raw paid receipts survive hook failure.
 */
public interface ScriptStore {
  sealed interface Command permits Wait, Tool, Finish {}

  /**
   * Pure terminal result for an event handler; orchestration conductors retain their stage gates.
   */
  record Finish(String text) implements Command {
    public Finish {
      if (text == null || text.length() > 65536 || text.indexOf('\0') >= 0)
        throw new IllegalArgumentException("invalid script finish text");
    }
  }

  record Wait(int milliseconds) implements Command {
    public Wait {
      if (milliseconds < 1 || milliseconds > 1000)
        throw new IllegalArgumentException("script wait must be 1–1000 ms");
    }
  }

  /** Arguments are the bounded object encoding handed to the owning tool's request codec. */
  record Tool(String name, String arguments, boolean readinessObserver) implements Command {
    public Tool {
      if (name == null || !name.matches("[A-Za-z0-9_\\-]{1,128}"))
        throw new IllegalArgumentException("script tool name is invalid");
      if (arguments == null || arguments.length() > 8388608 || arguments.indexOf('\0') >= 0)
        throw new IllegalArgumentException("script tool arguments are invalid");
      ScriptCommands.arguments(name, arguments, readinessObserver);
      if (readinessObserver && !name.equals("information_read"))
        throw new IllegalArgumentException("only information_read can be a readiness observer");
    }
  }

  record Input(
      String run,
      String message,
      int sequence,
      UUID requestId,
      Integer previousSequence,
      String result,
      List<TodoItem> todos,
      String hash) {
    public Input {
      run = identity(run, "conversation");
      hash = identity(hash, "source hash");
      if (sequence < 0
          || previousSequence == null && sequence != 0
          || previousSequence != null && previousSequence != sequence - 1)
        throw new IllegalArgumentException("invalid script journal sequence");
      Objects.requireNonNull(requestId, "requestId");
      if (message == null || message.length() > 1048576 || message.indexOf('\0') >= 0)
        throw new IllegalArgumentException("invalid script message");
      if (result != null && (result.length() > 8388608 || result.indexOf('\0') >= 0))
        throw new IllegalArgumentException("invalid script result");
      todos = List.copyOf(todos);
      if (todos.size() > 10000) throw new IllegalArgumentException("too many script todos");
    }
  }

  record Step(
      int sequence,
      String hash,
      Command command,
      String raw,
      String result,
      boolean started,
      String arguments) {
    public Step {
      if (sequence < 0) throw new IllegalArgumentException("invalid script sequence");
      hash = identity(hash, "source hash");
      Objects.requireNonNull(command, "command");
    }
  }

  sealed interface Preparation permits Prepared, NeedsCall {}

  record Prepared(Step step) implements Preparation {
    public Prepared {
      Objects.requireNonNull(step, "step");
    }
  }

  record NeedsCall() implements Preparation {}

  /**
   * Materializes a pure sandbox evaluation, or leaves the journal untouched when delegation is
   * unaffordable.
   */
  Preparation prepareNext(String source, Input input, boolean mayDelegate);

  Optional<Step> latest(String conversation);

  /** The latest failed turn's input, retained verbatim for deterministic script continuation. */
  default Optional<String> resumeMessage(String conversation) {
    return Optional.empty();
  }

  /** A stopped delegation whose persisted transient failure makes an explicit retry possible. */
  record FailedDelegate(String conversation, String agent, int sequence) {
    public FailedDelegate {
      conversation = identity(conversation, "delegate conversation");
      agent = identity(agent, "delegate agent");
      if (sequence < 0) throw new IllegalArgumentException("invalid script sequence");
    }
  }

  default Optional<FailedDelegate> failedDelegate(String conversation) {
    return Optional.empty();
  }

  /** Refuse uncertain commands before reviving a run; completed receipts and observers are safe. */
  default void requireRecoverable(String conversation) {
    var step = latest(conversation).orElse(null);
    if (step == null || step.result() != null || !step.started() || step.raw() != null) return;
    if (step.command() instanceof Wait || step.command() instanceof Finish) return;
    if (step.command() instanceof Tool tool && tool.readinessObserver()) return;
    if (failedDelegate(conversation).isPresent()
        || delegateResult(conversation, step.sequence()).isPresent()) return;
    throw new io.aeyer.plowshare.server.faults.CallerFault(
        "The interrupted script command has no durable result; it may have run. Reconcile it before resuming; replay is refused.");
  }

  void started(String conversation, int sequence, String arguments);

  Optional<String> delegateResult(String conversation, int sequence);

  void executed(String conversation, int sequence, String raw);

  void retryReadinessObserver(String conversation, int sequence);

  void retryRefusedStage(String conversation, int sequence);

  void completed(String conversation, int sequence, String result);

  private static String identity(String value, String field) {
    if (value == null
        || value.isBlank()
        || value.length() > 1024
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw new IllegalArgumentException("invalid script " + field);
    return value;
  }

  /**
   * Semantic argument comparison for cached receipts, including journals written before canonical
   * encoding.
   */
  static boolean sameArguments(String first, String second) {
    return ScriptCommands.sameArguments(first, second);
  }
}
