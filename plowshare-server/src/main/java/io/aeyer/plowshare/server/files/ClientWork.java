package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileRequest;
import java.time.Duration;

/** Execution-side workspace fences. Transport callbacks never create or replay a job. */
public interface ClientWork {
  ClientWork NONE = new ClientWork() {};

  /** Register the provider actually selected for work, under the current execution context. */
  default void requires(String session) {}

  /** Park before an unsubmitted request if its required workspace is absent. */
  default void beforeRequest(String session) {}

  /** Record a broken connection generation without weakening a bound snapshot's fence. */
  default void disconnected(String session) {}

  /** Nested pure snapshot requests share their original remaining active budget. */
  default Duration requestBudget(Duration requested) {
    return requested;
  }

  /** A request may have reached the client. Mutations must remain blocked, never replayed. */
  default boolean interrupted(String session, FileRequest request) {
    return false;
  }

  /** Only these protocol operations are pure reads; unknown operations are never replayable. */
  static boolean readOnly(FileRequest request) {
    return switch (request.op()) {
      case FileRequest.ROOTS,
              FileRequest.READ,
              FileRequest.STAT,
              FileRequest.GLOB,
              FileRequest.GREP,
              FileRequest.SOURCE ->
          true;
      default -> false;
    };
  }
}
