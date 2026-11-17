package io.aeyer.plowshare.server.reads;

/**
 * An HTTP entry point could not acquire capacity before starting its handler. This is flow control,
 * not a failed archive operation; callers may pace resubmission. Only the entry point's acquisition
 * catch may construct it. Mid-operation capacity failures must retain their original
 * classification.
 */
public final class IncomingReadCapacityException extends RuntimeException {
  IncomingReadCapacityException(ReadUnavailableException cause) {
    super("Incoming read capacity is busy; no handler was started", cause);
  }
}
