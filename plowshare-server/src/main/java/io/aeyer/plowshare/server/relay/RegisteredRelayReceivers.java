package io.aeyer.plowshare.server.relay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable explicit receiver bindings; an unavailable name is never substituted or redirected. */
public final class RegisteredRelayReceivers implements RelayReceivers {
  private final Map<String, RelayReceiver> receivers;

  public RegisteredRelayReceivers(List<Binding> bindings) {
    bindings = List.copyOf(bindings);
    if (bindings.size() > 256) throw new IllegalArgumentException("too many Relay receivers");
    var registered = new LinkedHashMap<String, RelayReceiver>();
    for (var binding : bindings)
      if (registered.putIfAbsent(binding.name(), binding.receiver()) != null)
        throw new IllegalArgumentException("duplicate Relay receiver registration");
    receivers = Map.copyOf(registered);
  }

  @Override
  public Optional<RelayReceiver> find(String name) {
    return Optional.ofNullable(receivers.get(RelayValues.name(name, "receiver")));
  }
}
