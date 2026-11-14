package io.aeyer.plowshare.server.relay;

/**
 * The locked topic row was absent. Thrown by an append inside its transaction before anything is
 * written to the topic, so the attempt rolled back and a caller may register and retry once.
 */
public final class RelayTopicMissingException extends IllegalStateException {
  private static final long serialVersionUID = 1L;

  public RelayTopicMissingException() {
    super("Relay topic does not exist");
  }
}
