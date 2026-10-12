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
@EnableConfigurationProperties({RelayToolProperties.class, HostedToolProperties.class})
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
  public ToolScopeConnections sessionToolScopeConnections(
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
  @org.springframework.context.annotation.Primary
  public ToolScopeConnections toolScopeConnections(
      @org.springframework.beans.factory.annotation.Qualifier("sessionToolScopeConnections")
          ToolScopeConnections local,
      HostedToolScopes hosted) {
    return new CombinedToolScopeConnections(local, hosted);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public HostedToolScopeRepository hostedToolScopeRepository(
      JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper mapper) {
    return new JdbcHostedToolScopeRepository(jdbc, mapper);
  }

  @Bean
  public HostedToolScopes hostedToolScopes(
      HostedToolScopeRepository repository,
      io.aeyer.plowshare.server.providers.ProviderCredentialGrants credentials,
      ProjectWorkspaces projects,
      ProjectMembers members,
      io.aeyer.plowshare.server.auth.AdminStore accounts,
      io.aeyer.plowshare.server.agents.ApplicationResources resources,
      @org.springframework.beans.factory.annotation.Qualifier("sessionToolScopeConnections")
          ToolScopeConnections local,
      io.aeyer.plowshare.server.session.SessionRegistry sessions,
      HostedToolProperties properties,
      org.springframework.beans.factory.ObjectProvider<
              io.aeyer.plowshare.server.agents.DefinitionResolver>
          definitions,
      org.springframework.beans.factory.ObjectProvider<io.aeyer.plowshare.server.agents.JobRuntime>
          runtime,
      Relay relay,
      io.aeyer.plowshare.server.relay.RelayLogRepository logs,
      io.aeyer.plowshare.server.relay.RelayPortRepository ports,
      org.springframework.beans.factory.ObjectProvider<RelayToolRepository> invocations,
      org.springframework.beans.factory.ObjectProvider<RelayToolInvocations> results,
      UnitOfWork work,
      io.aeyer.plowshare.server.relay.RelayProperties relayProperties) {
    return new OwnedHostedToolScopes(
        repository,
        new io.aeyer.plowshare.server.providers.CurrentProviderCredentialAccess(accounts, members),
        credentials,
        projects,
        members,
        accounts,
        resources,
        toolGrants(definitions),
        local,
        (actor, session, socket) ->
            sessions
                .find(session)
                .filter(s -> s.account().filter(actor::equals).isPresent())
                .flatMap(
                    s ->
                        s.attached(
                            io.aeyer.plowshare.server.session.Role.LISTENER,
                            org.springframework.web.socket.WebSocketSession.class))
                .filter(s -> s.isOpen() && s.getId().equals(socket))
                .isPresent(),
        properties.getExecutors(),
        () ->
            io.aeyer.plowshare.server.agents.ScopedTools.reservedNames(
                runtime.getObject().knownTools()),
        relay,
        logs,
        ports,
        invocations::getObject,
        results::getObject,
        work,
        java.time.Clock.systemUTC(),
        java.util.UUID.randomUUID(),
        relayProperties.getMaxForwardingHops());
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayToolRepository relayToolRepository(
      JdbcTemplate jdbc, UnitOfWork transactions, Relay relay, HostedToolScopes hosting) {
    return new JdbcRelayToolRepository(jdbc, transactions, relay, hosting);
  }

  @Bean
  public RelayToolInvocations relayToolInvocations(
      RelayToolRepository repository,
      Relay relay,
      ProjectMembers members,
      ProjectWorkspaces projects,
      io.aeyer.plowshare.server.relay.RelayProperties properties,
      HostedToolScopes hosting) {
    return new ProjectRelayToolInvocations(
        repository,
        relay,
        members,
        projects,
        Instant::now,
        properties.getMaxForwardingHops(),
        hosting);
  }
}
