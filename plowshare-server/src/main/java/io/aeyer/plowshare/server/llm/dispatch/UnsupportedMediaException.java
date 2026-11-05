package io.aeyer.plowshare.server.llm.dispatch;

/** A known model has no eligible route for the complete requested native format contract. */
public final class UnsupportedMediaException extends LlmException {
  public UnsupportedMediaException(String specifier) {
    super("No compatible native media route for '" + specifier + "'");
  }
}
