package io.aeyer.plowshare.server.archive;

/**
 * A write proposal failed structural validation.
 *
 * <p>Ported from Excalibur's {@code ValidationError}. Unchecked, because validation sits on every
 * write path — a checked exception would force every caller between here and the HTTP layer to
 * either handle or re-declare a failure that is always a malformed request, never a recoverable
 * condition the caller can retry without changing anything.
 */
public class ValidationException extends RuntimeException {

  public ValidationException(String message) {
    super(message);
  }
}
