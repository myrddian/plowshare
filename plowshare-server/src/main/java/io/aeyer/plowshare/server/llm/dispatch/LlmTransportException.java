package io.aeyer.plowshare.server.llm.dispatch;

import java.util.Objects;
import java.util.Optional;

/**
 * A provider request could not complete: connection failure, refusal, invalid response, interrupted
 * stream or completion deadline expiry. A transport failure does not imply an unreachable endpoint.
 */
public final class LlmTransportException extends LlmException {

  /** The dispatch operation whose completion deadline expired. */
  public enum TimeoutOperation {
    PROMPT("Prompt"),
    FOLD("Conversation fold"),
    SKILL_SUMMARY("Skill summary"),
    REVIEW("Security review");

    private final String label;

    TimeoutOperation(String label) {
      this.label = label;
    }
  }

  private final boolean timedOut;
  private final TimeoutOperation timeoutOperation;

  public LlmTransportException(String message) {
    this(message, null, false);
  }

  public LlmTransportException(String message, Throwable cause) {
    this(message, cause, false);
  }

  private LlmTransportException(String message, Throwable cause, boolean timedOut) {
    this(message, cause, timedOut, null);
  }

  private LlmTransportException(
      String message, Throwable cause, boolean timedOut, TimeoutOperation timeoutOperation) {
    super(message, cause);
    this.timedOut = timedOut;
    this.timeoutOperation = timeoutOperation;
  }

  /** A completion deadline expired; this does not imply that the endpoint was unreachable. */
  public static LlmTransportException timedOut(String message, Throwable cause) {
    return new LlmTransportException(message, cause, true);
  }

  /** Preserve the typed failure when adding safe transport-attempt context. */
  public LlmTransportException describedAs(String message) {
    return new LlmTransportException(message, this, timedOut, timeoutOperation);
  }

  /** The dispatcher knows the operation even when it uses a shared transport budget. */
  LlmTransportException forTimeoutOperation(TimeoutOperation operation) {
    Objects.requireNonNull(operation);
    if (!timedOut || timeoutOperation != null) return this;
    return new LlmTransportException(
        operation.label + " timed out: " + getMessage(), this, true, operation);
  }

  public Optional<TimeoutOperation> timeoutOperation() {
    return Optional.ofNullable(timeoutOperation);
  }

  public boolean timedOut() {
    return timedOut;
  }
}
