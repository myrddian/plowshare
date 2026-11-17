package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.reads.ReadFailedException;

/**
 * The database answered a statement with query cancellation, not a lost-connection failure.
 *
 * <p>This is not archive unavailability: one failed statement must not terminate the surrounding
 * agent job as a lost database dependency. The active transaction still rolls back and no result is
 * fabricated. Earlier committed effects remain retained and must never be replayed blindly.
 */
public final class ArchiveQueryCancelledException extends ReadFailedException {
  public ArchiveQueryCancelledException(String operation, Throwable cause) {
    super(
        Reason.QUERY_CANCELLED,
        "archive SQL was cancelled before a result was available while trying to "
            + operation
            + "; a statement deadline or explicit cancellation can cause this (SQLSTATE 57014)",
        cause);
  }
}
