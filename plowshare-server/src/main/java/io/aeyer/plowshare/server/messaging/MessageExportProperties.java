package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayPort;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit operator opt-in; unrelated granted publications do not become message requests. */
@ConfigurationProperties(value = "plowshare.messaging.exports", ignoreUnknownFields = false)
public final class MessageExportProperties {
  public record Policy(
      String project,
      String requestTopic,
      String channel,
      String returnTopic,
      String adapterAccount) {
    public Policy {
      project = RelayPort.identity(project);
      if (Home.of(project).isGlobal())
        throw new IllegalArgumentException("Export requires a project");
      requestTopic = RelayPort.topic(requestTopic);
      returnTopic = RelayPort.topic(returnTopic);
      channel = RelayPort.identity(channel);
      adapterAccount = RelayPort.identity(adapterAccount);
      if (!channel.matches("[a-z][a-z0-9_.-]{0,63}") || requestTopic.equals(returnTopic))
        throw new IllegalArgumentException("Invalid export channel or topic loop");
    }
  }

  private volatile List<Policy> policies = List.of();

  public List<Policy> getPolicies() {
    return policies;
  }

  public void setPolicies(List<Policy> value) {
    value = List.copyOf(Objects.requireNonNull(value));
    if (value.size() > 256) throw new IllegalArgumentException("Too many messaging exports");
    var requests = new HashSet<String>();
    var returns = new HashSet<String>();
    for (var policy : value) {
      if (!requests.add(policy.project() + "\n" + policy.requestTopic())
          || !returns.add(policy.project() + "\n" + policy.returnTopic() + "\n" + policy.channel()))
        throw new IllegalArgumentException("Duplicate messaging export policy");
    }
    policies = value;
  }

  public Optional<Policy> request(String project, String topic) {
    return policies.stream()
        .filter(policy -> policy.project().equals(project) && policy.requestTopic().equals(topic))
        .findFirst();
  }

  public Optional<Policy> returns(String project, String topic, String channel) {
    return policies.stream()
        .filter(
            policy ->
                policy.project().equals(project)
                    && policy.returnTopic().equals(topic)
                    && policy.channel().equals(channel))
        .findFirst();
  }
}
