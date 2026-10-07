package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit deployment grants; project membership alone never opens an external port. */
@ConfigurationProperties(value = "plowshare.relay.ports", ignoreUnknownFields = false)
public final class RelayPortProperties {
  public enum Direction {
    INGRESS,
    EGRESS
  }

  public record Binding(
      String project, String topic, String account, Direction direction, List<String> groups) {
    public Binding {
      RelayPort.identity(project);
      RelayPort.name(topic);
      RelayPort.identity(account);
      java.util.Objects.requireNonNull(direction);
      groups = groups == null ? List.of() : List.copyOf(groups);
      if (groups.size() > 100
          || direction == Direction.INGRESS && !groups.isEmpty()
          || direction == Direction.EGRESS && groups.isEmpty())
        throw new IllegalArgumentException("Invalid Relay port groups");
      groups.forEach(RelayPort::group);
      if (new java.util.HashSet<>(groups).size() != groups.size())
        throw new IllegalArgumentException("Duplicate Relay group");
    }
  }

  private List<Binding> bindings = List.of();

  public List<Binding> getBindings() {
    return bindings;
  }

  public void setBindings(List<Binding> value) {
    bindings = List.copyOf(value);
    if (bindings.size() > 1000 || new java.util.HashSet<>(bindings).size() != bindings.size())
      throw new IllegalArgumentException("Invalid Relay port bindings");
  }

  public boolean permits(
      String account, String project, String topic, Direction direction, String group) {
    return bindings.stream()
        .anyMatch(
            b ->
                b.account().equals(account)
                    && b.project().equals(project)
                    && b.topic().equals(topic)
                    && b.direction() == direction
                    && (group == null || b.groups().contains(group)));
  }
}
