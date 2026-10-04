package io.aeyer.plowshare.server.llm.dispatch;

/**
 * The endpoint could not be reached, refused, or answered with something that is not what was asked
 * for.
 */
public final class LlmTransportException extends LlmException {

  public LlmTransportException(String message) {
    super(message);
  }

  public LlmTransportException(String message, Throwable cause) {
    super(message, cause);
  }
}
