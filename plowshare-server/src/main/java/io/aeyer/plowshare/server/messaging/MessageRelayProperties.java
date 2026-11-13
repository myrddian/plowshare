package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayPort;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit, exact operator mappings for retained legacy ingress; no mapping means no adoption. */
@ConfigurationProperties(value = "plowshare.messaging.relay", ignoreUnknownFields = false)
public final class MessageRelayProperties {
  private volatile List<LegacyMapping> legacyMappings = List.of();

  public record LegacyMapping(
      String account,
      String project,
      String client,
      String sourceTopic,
      String subscriber,
      UUID sourceGeneration,
      String channel,
      String agent,
      String replyTopic,
      UUID replyGeneration) {
    public LegacyMapping {
      for (String value : List.of(account, project, subscriber, channel, agent))
        RelayPort.identity(value);
      MessageIngress.client(client);
      RelayPort.topic(sourceTopic);
      RelayPort.topic(replyTopic);
      Objects.requireNonNull(sourceGeneration);
      Objects.requireNonNull(replyGeneration);
    }

    boolean matches(MessageRelayRepository.Family family, String client) {
      return this.client.equals(client)
          && account.equals(family.account())
          && project.equals(family.project())
          && family.executionDefinition() == null
          && RelayPort.publisher(account).equals(family.publisher())
          && sourceTopic.equals(family.sourceTopic())
          && subscriber.equals(family.subscriber())
          && sourceGeneration.equals(family.sourceGeneration())
          && channel.equals(family.channel())
          && agent.equals(family.agent())
          && replyTopic.equals(family.replyTopic())
          && replyGeneration.equals(family.replyGeneration());
    }
  }

  public List<LegacyMapping> getLegacyMappings() {
    return legacyMappings;
  }

  public void setLegacyMappings(List<LegacyMapping> values) {
    var checked = List.copyOf(values);
    var keys = new HashSet<String>();
    if (checked.size() > 256)
      throw new IllegalArgumentException("Too many legacy ingress mappings");
    for (var value : checked)
      if (!keys.add(value.account() + "\n" + value.project() + "\n" + value.client()))
        throw new IllegalArgumentException(
            "One exact destination is required per legacy client scope");
    legacyMappings = checked;
  }

  public boolean permitsMigration(MessageRelayRepository.Family family, String client) {
    return legacyMappings.stream().anyMatch(mapping -> mapping.matches(family, client));
  }
}
