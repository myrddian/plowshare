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
  public ApplicationToolRegistry applicationToolRegistry(
      io.aeyer.plowshare.server.agents.ApplicationResources resources,
      ProjectWorkspaces projects,
      ProjectMembers members,
      io.aeyer.plowshare.server.archive.ProjectNames names,
      io.aeyer.plowshare.server.relay.RelayLogRepository logs,
      RelayToolProperties boot,
      RelayToolInvocations invocations,
      org.springframework.beans.factory.ObjectProvider<
              io.aeyer.plowshare.server.agents.DefinitionResolver>
          definitions,
      org.springframework.beans.factory.ObjectProvider<io.aeyer.plowshare.server.agents.JobRuntime>
          runtime,
      ToolScopeConnections connections) {
    return new ApplicationToolRegistry(
        resources,
        projects,
        members,
        names,
        logs,
        boot,
        invocations,
        toolGrants(definitions),
        () ->
            io.aeyer.plowshare.server.agents.ScopedTools.reservedNames(
                runtime.getObject().knownTools()),
        Instant::now,
        connections);
  }

  private static io.aeyer.plowshare.server.agents.ToolGrants toolGrants(
      org.springframework.beans.factory.ObjectProvider<
              io.aeyer.plowshare.server.agents.DefinitionResolver>
          definitions) {
    return new io.aeyer.plowshare.server.agents.ToolGrants() {
      public boolean permits(
          Long project, String account, String agent, String session, String tool) {
        return definitions
            .getObject()
            .forCaller(
                new io.aeyer.plowshare.server.agents.DefinitionResolver.Caller(
                    project, session, account))
            .findConcrete(agent)
            .map(d -> d.tools().contains(tool))
            .orElse(false);
      }

      public boolean acceptsDynamic(Long project, String account, String agent, String session) {
        return definitions
            .getObject()
            .forCaller(
                new io.aeyer.plowshare.server.agents.DefinitionResolver.Caller(
                    project, session, account))
            .findConcrete(agent)
            .map(io.aeyer.plowshare.server.agents.AgentDefinition::dynamic)
            .orElse(false);
      }
    };
  }

  @Bean
  public ToolScopeConnections toolScopeConnections(
      io.aeyer.plowshare.server.agents.ApplicationResources resources,
      ProjectWorkspaces projects,
      ProjectMembers members,
      io.aeyer.plowshare.server.session.SessionRegistry sessions,
      org.springframework.beans.factory.ObjectProvider<
              io.aeyer.plowshare.server.agents.DefinitionResolver>
          definitions,
      org.springframework.beans.factory.ObjectProvider<io.aeyer.plowshare.server.agents.JobRuntime>
          runtime) {
    return new SessionToolScopes(
        resources,
        projects,
        members,
        toolGrants(definitions),
        (account, session, connection) ->
            sessions
                .find(session)
                .filter(s -> s.account().filter(account::equals).isPresent())
                .flatMap(
                    s ->
                        s.attached(
                            io.aeyer.plowshare.server.session.Role.LISTENER,
                            org.springframework.web.socket.WebSocketSession.class))
                .filter(s -> s.isOpen() && s.getId().equals(connection))
                .isPresent(),
        () ->
            io.aeyer.plowshare.server.agents.ScopedTools.reservedNames(
                runtime.getObject().knownTools()));
  }

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
