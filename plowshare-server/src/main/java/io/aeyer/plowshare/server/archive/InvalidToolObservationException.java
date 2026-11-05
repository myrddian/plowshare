package io.aeyer.plowshare.server.archive;

/** Corrupt typed history must stop reconstruction instead of becoming a text-only conversation. */
public final class InvalidToolObservationException extends IllegalStateException {
  public InvalidToolObservationException(Throwable cause) {
    super("Stored tool observation is invalid; image history cannot be reconstructed", cause);
  }
}
