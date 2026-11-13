package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operator-installed exact grants supplement, but never replace, definition tool grants. */
@ConfigurationProperties(value = "plowshare.relay.publishing", ignoreUnknownFields = false)
public final class RelayPublishingProperties {
  private volatile List<Grant> grants = List.of();

  /** No wildcard, project fallback or implicit topic creation authority is inferred. */
  public record Grant(String project, String definition, String topic) {
    public Grant {
      RelayPort.identity(project);
      if (definition == null || !definition.matches("[a-z0-9_]{1,64}"))
        throw new IllegalArgumentException("Invalid publishing definition");
      RelayPort.topic(topic);
    }
  }

  public List<Grant> getGrants() {
    return grants;
  }

  public void setGrants(List<Grant> value) {
    var checked = List.copyOf(value);
    if (checked.size() > 1024 || new HashSet<>(checked).size() != checked.size())
      throw new IllegalArgumentException("Publishing grants must be unique and bounded to 1024");
    grants = checked;
  }

  public boolean permits(String project, String definition, String topic) {
    return grants.contains(new Grant(project, definition, topic));
  }
}
