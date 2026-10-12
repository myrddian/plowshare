package io.aeyer.plowshare.server.relay.tools;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit trusted worker identities. Empty configuration disables delegated hosting. */
@ConfigurationProperties(prefix = "plowshare.providers.hosting", ignoreUnknownFields = false)
public final class HostedToolProperties {
  private Set<String> executors = Set.of();

  public Set<String> getExecutors() {
    return executors;
  }

  public void setExecutors(Set<String> values) {
    if (values.size() > 128
        || values.stream()
            .anyMatch(
                v ->
                    v == null
                        || !v.matches("@service/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")))
      throw new IllegalArgumentException(
          "Configure exact bounded executor service-token principals");
    executors = Set.copyOf(values);
  }
}
