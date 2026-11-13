package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayPort;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Native forum exports are explicit and cannot adopt an external adapter's export channel. */
@ConfigurationProperties(value = "plowshare.messaging.board-exports", ignoreUnknownFields = false)
public final class BoardMessageExportProperties {
  public record Policy(String project, String requestTopic, String channel, String receiver) {
    public Policy {
      project = RelayPort.identity(project);
      requestTopic = RelayPort.topic(requestTopic);
      channel = RelayPort.identity(channel);
      if (Home.of(project).isGlobal()
          || !channel.matches("[a-z][a-z0-9_.-]{0,63}")
          || !Set.of("board.open", "board.post", "board.close").contains(receiver))
        throw new IllegalArgumentException("Invalid native board export policy");
    }
  }

  private volatile List<Policy> policies = List.of();

  public List<Policy> getPolicies() {
    return policies;
  }

  public void setPolicies(List<Policy> value) {
    value = List.copyOf(Objects.requireNonNull(value));
    if (value.size() > 256) throw new IllegalArgumentException("Too many board export policies");
    var keys = new HashSet<String>();
    for (var policy : value)
      if (!keys.add(policy.project() + "\n" + policy.requestTopic()))
        throw new IllegalArgumentException("Duplicate board export policy");
    policies = value;
  }

  public Optional<Policy> request(String project, String topic) {
    return policies.stream()
        .filter(policy -> policy.project().equals(project) && policy.requestTopic().equals(topic))
        .findFirst();
  }
}
