package io.aeyer.plowshare.sdk;

import io.aeyer.plowshare.protocol.ServerPush;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/** Existing operation-codec fixtures explicitly exercise the retained legacy wire mode. */
final class LegacyFixture {
  private LegacyFixture() {}

  static Plowshare connect(
      String origin, String bearer, Duration timeout, Consumer<ServerPush> push)
      throws IOException {
    return connect(origin, bearer, UUID.randomUUID().toString(), timeout, push);
  }

  static Plowshare connect(
      String origin, String bearer, String session, Duration timeout, Consumer<ServerPush> push)
      throws IOException {
    return Plowshare.connect(
        origin, bearer, session, timeout, push, Plowshare.TransportMode.LEGACY);
  }
}
