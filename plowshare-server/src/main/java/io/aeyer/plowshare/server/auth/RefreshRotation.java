package io.aeyer.plowshare.server.auth;

import java.time.Duration;
import java.util.Objects;

/** A rotated pair and its remaining server lifetimes; duplicate delivery never extends expiry. */
public record RefreshRotation(
    TokenStore.Pair pair, Duration accessLifetime, Duration refreshLifetime) {
  public RefreshRotation {
    Objects.requireNonNull(pair, "pair");
    Objects.requireNonNull(accessLifetime, "accessLifetime");
    Objects.requireNonNull(refreshLifetime, "refreshLifetime");
    if (accessLifetime.isNegative()
        || accessLifetime.isZero()
        || refreshLifetime.isNegative()
        || refreshLifetime.isZero())
      throw new IllegalArgumentException("remaining session lifetimes must be positive");
  }
}
