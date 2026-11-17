package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.pool.SqlWorkUnavailableException;
import java.util.Objects;

/** An Ex phase failed without proving that earlier committed or admitted work did not happen. */
public final class ExternalConversationUnavailableException extends ArchiveUnavailableException {
  public ExternalConversationUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Preserve exact Ex/SQL outcomes; other archive outages cannot prove an Ex mutation had no
   * effect.
   */
  public static ArchiveUnavailableException preserveUncertainty(
      ArchiveUnavailableException failure) {
    Objects.requireNonNull(failure);
    if (failure instanceof ExternalConversationUnavailableException
        || failure instanceof SqlWorkUnavailableException) return failure;
    return new ExternalConversationUnavailableException(
        "External conversation work is unavailable; earlier work may have committed or been"
            + " admitted. Inspect retained work and do not replay this mutation.",
        failure);
  }
}
