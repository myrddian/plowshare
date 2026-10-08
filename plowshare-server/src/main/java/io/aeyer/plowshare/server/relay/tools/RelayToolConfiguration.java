package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import java.time.Instant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composes the narrow tool façade with the existing broker and membership authority. */
@Configuration
@EnableConfigurationProperties(RelayToolProperties.class)
public class RelayToolConfiguration {
  @Bean
  @DependsOnDatabaseInitialization
  public RelayToolRepository relayToolRepository(
      JdbcTemplate jdbc, UnitOfWork transactions, Relay relay) {
    return new JdbcRelayToolRepository(jdbc, transactions, relay);
  }

  @Bean
  public RelayToolInvocations relayToolInvocations(
      RelayToolRepository repository,
      Relay relay,
      ProjectMembers members,
      ProjectWorkspaces projects,
      io.aeyer.plowshare.server.relay.RelayProperties properties) {
    return new ProjectRelayToolInvocations(
        repository, relay, members, projects, Instant::now, properties.getMaxForwardingHops());
  }
}
