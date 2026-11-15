package io.aeyer.plowshare.server.llm.dispatch;

/**
 * A provider request could not complete: connection failure, refusal, invalid response, interrupted
 * stream or completion deadline expiry. A transport failure does not imply an unreachable endpoint.
 */
public final class LlmTransportException extends LlmException {

  private final boolean timedOut;

  public LlmTransportException(String message) {
    this(message, null, false);
  }

  public LlmTransportException(String message, Throwable cause) {
    this(message, cause, false);
  }

  private LlmTransportException(String message, Throwable cause, boolean timedOut) {
    super(message, cause);
    this.timedOut = timedOut;
  }

  /** A completion deadline expired; this does not imply that the endpoint was unreachable. */
  public static LlmTransportException timedOut(String message, Throwable cause) {
    return new LlmTransportException(message, cause, true);
  }

  /** Preserve the typed failure when adding safe transport-attempt context. */
  public LlmTransportException describedAs(String message) {
    return new LlmTransportException(message, this, timedOut);
  }

  public boolean timedOut() {
    return timedOut;
  }
}
