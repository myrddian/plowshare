package io.aeyer.plowshare.server.archive;

/**
 * The database answered a statement with query cancellation, not a lost-connection failure.
 *
 * <p>This remains archive unavailability unless a read-operation owner can translate it after its
 * transactions unwind. It never authorizes replay of a mutation or any possibly committed effect.
 */
public final class ArchiveQueryCancelledException extends ArchiveUnavailableException {
  public ArchiveQueryCancelledException(String operation, Throwable cause) {
    super(
        "archive SQL was cancelled before a result was available while trying to "
            + operation
            + "; a statement deadline or explicit cancellation can cause this (SQLSTATE 57014)",
        cause);
  }
}
