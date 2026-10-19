package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.protocol.ApplicationIdentity;
import io.aeyer.plowshare.server.access.BuiltInIdentities;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.util.Objects;
import java.util.UUID;

/** Structural invariants shared by the internal authority values; these do not authenticate. */
final class ActingValues {
  private ActingValues() {}

  static String account(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) {
      throw new IllegalArgumentException("Choose a bounded account identity");
    }
    return value;
  }

  static String subject(String value) {
    if (BuiltInIdentities.reservedAccount(value))
      throw new IllegalArgumentException("Built-in accounts cannot be acting subjects");
    return account(value);
  }

  static String actor(String value) {
    if (value != null && value.startsWith("@service/")) {
      String token = value.substring("@service/".length());
      if (!UUID.fromString(token).toString().equals(token)) {
        throw new IllegalArgumentException("Choose a canonical service principal");
      }
      return value;
    }
    return subject(value);
  }

  static String application(String value) {
    ApplicationIdentity.project(value);
    return value;
  }

  static String serviceActor(String value) {
    value = actor(value);
    if (!value.startsWith("@service/")) {
      throw new IllegalArgumentException(
          "Acting requests require an Application service principal");
    }
    return value;
  }

  static ProjectRole ceiling(ProjectRole value) {
    Objects.requireNonNull(value, "ceiling");
    if (value == ProjectRole.MANAGER) {
      throw new IllegalArgumentException("Acting authority is bounded to VIEWER or CONTRIBUTOR");
    }
    return value;
  }
}
