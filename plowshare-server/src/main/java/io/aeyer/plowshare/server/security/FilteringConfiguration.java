package io.aeyer.plowshare.server.security;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayLogRepository;
import io.aeyer.plowshare.server.relay.RelayPortProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires detectors to the model boundary without giving SDK reviewers access to server internals.
 */
@Configuration
@EnableConfigurationProperties(FilteringProperties.class)
public class FilteringConfiguration {
  @Bean
  public MessageReview messageReview(
      FilteringProperties properties,
      Relay relay,
      RelayLogRepository logs,
      ProjectMembers members,
      ProjectWorkspaces projects,
      RelayPortProperties ports) {
    return new RelayMessageReview(properties, relay, logs, members, projects, ports);
  }

  @Bean
  public ChatFiltering chatFiltering(FilteringProperties properties, MessageReview review) {
    return new FilteredChat(new LocalTextFilter(properties), review);
  }
}
