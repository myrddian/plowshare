package io.aeyer.plowshare.server.pool;

import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;

/** A possibly partially completed operation is unavailable, never safe capacity replay. */
public final class SqlWorkUnavailableException extends ArchiveUnavailableException {
  public SqlWorkUnavailableException(Throwable cause) {
    super(
        "Scoped database work could not complete; inspect retained work before any mutation replay",
        cause);
  }
}
