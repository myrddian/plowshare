package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Lazy definition resolution avoids making the definition loader depend on its runtime tools. */
@Configuration
@EnableConfigurationProperties(RelayPublishingProperties.class)
public class RelayPublishingConfiguration {
  @Bean
  @DependsOnDatabaseInitialization
  public RelayPublishingRepository relayPublishingRepository(
      JdbcTemplate jdbc, UnitOfWork transactions, Relay relay) {
    return new JdbcRelayPublishingRepository(jdbc, transactions, relay);
  }

  @Bean
  public RelayPublishing relayPublishing(
      RelayPublishingRepository repository,
      RelayPublishingProperties grants,
      ObjectProvider<DefinitionResolver> definitions,
      ObjectProvider<OrchestrationResolver> orchestrations,
      ProjectMembers members,
      ProjectWorkspaces projects,
      RelayProperties properties) {
    return new GrantedRelayPublishing(
        repository,
        grants,
        (project, execution) -> {
          var owner = execution.owner();
          var caller =
              new DefinitionResolver.Caller(project, execution.session(), owner.accountHandle());
          if (execution.kind() == RelayPublishing.DefinitionKind.ORCHESTRATION) {
            var definition = orchestrations.getObject().forCaller(caller).get(owner.agentName());
            return definition != null && definition.conductor().tools().contains("relay_publish");
          }
          return definitions
              .getObject()
              .forCaller(caller)
              .findConcrete(owner.agentName())
              .map(d -> d.tools().contains("relay_publish"))
              .orElse(false);
        },
        members,
        projects,
        properties.getMaxForwardingHops(),
        Instant::now);
  }
}
