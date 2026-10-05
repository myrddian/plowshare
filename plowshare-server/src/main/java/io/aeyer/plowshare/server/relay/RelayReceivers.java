package io.aeyer.plowshare.server.relay;

import java.util.Objects;
import java.util.Optional;

/**
 * Server-owned receiver registrations, never created by a routes.js payload. A name must retain its
 * adapter/command meaning while admitted work exists; change that meaning under a new name.
 */
public interface RelayReceivers {
  Optional<RelayReceiver> find(String name);

  record Binding(String name, RelayReceiver receiver) {
    public Binding {
      name = RelayValues.name(name, "receiver");
      Objects.requireNonNull(receiver, "receiver");
    }
  }
}
