package io.aeyer.plowshare.server.embedding;

/** Shared deployment policy syntax: unlimited by default, zero disabled, positive limited. */
public final class SpendingPolicy {
  private SpendingPolicy() {}

  public static Integer limit(String value, String setting) {
    if (value == null || value.isBlank() || value.strip().equalsIgnoreCase("unlimited"))
      return null;
    try {
      int limit = Integer.parseInt(value.strip());
      if (limit >= 0) return limit;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(
          setting + " must be unlimited or a nonnegative integer", invalid);
    }
    throw new IllegalArgumentException(setting + " must be unlimited or a nonnegative integer");
  }
}
