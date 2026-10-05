package io.aeyer.plowshare.server.auth;

import java.util.Optional;

/**
 * Durable machine credentials. Execution identity carries the ceiling through queued work and
 * routing.
 */
public interface ServiceCredentials {
  String PREFIX = "pss_";

  static boolean principal(String account) {
    return account != null && account.startsWith("@service/");
  }

  static boolean credential(String presented) {
    return presented != null && presented.startsWith(PREFIX);
  }

  /** Authenticates an active, unexpired machine token without exposing its persisted digest. */
  Optional<TokenStore.Authentication> authentication(String presented);
}
