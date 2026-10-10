package io.aeyer.plowshare.server.auth;

import java.util.Objects;
import java.util.UUID;

/**
 * One browser rotation, shared by physical deliveries of the same HTTP request. Not a credential.
 */
public record RefreshIntent(UUID value) {
  public static final String HEADER = "X-Plowshare-Refresh-Intent";

  public RefreshIntent {
    Objects.requireNonNull(value, "refresh intent");
    if (value.version() != 4 || value.variant() != 2)
      throw new IllegalArgumentException("refresh intent must be a random UUID");
  }

  /** Strict canonical UUID boundary; malformed headers must fail before spending a token. */
  public static RefreshIntent parse(String value) {
    if (value == null
        || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
      throw new IllegalArgumentException("invalid refresh intent");
    return new RefreshIntent(UUID.fromString(value));
  }
}
