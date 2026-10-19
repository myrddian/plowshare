package io.aeyer.plowshare.server.allowances;

import java.util.Objects;
import java.util.UUID;

/** Stable spending-source identity, independent of an execution or resource identifier. */
public record AllowanceId(UUID value) {
  public AllowanceId {
    Objects.requireNonNull(value, "allowance ID");
  }
}
