package io.aeyer.plowshare.server.events;

/**
 * The model a schedule reading needed could not be reached, so nothing was proposed.
 *
 * <p>Beside {@link ScheduleReader} and not in {@code llm}, on {@code
 * config.ConfigUnavailableException}'s precedent: the type is the contract between one subsystem
 * and {@code faults.Faults}, and its sentence is about what <em>this</em> subsystem did not do. An
 * {@code LlmException} reaching a frame unwrapped would answer 500 and "a fault in the server",
 * which is wrong for an endpoint that is merely away, and would put the transport's message — which
 * can name a host and port — in front of a client.
 *
 * <p><b>The message is always this class's caller's own sentence, never the cause's.</b> The cause
 * is kept for the log line and never reaches the wire.
 */
public class ModelUnavailableException extends RuntimeException {

  /** Public because the mapping that reads it lives in {@code faults}. */
  public ModelUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
