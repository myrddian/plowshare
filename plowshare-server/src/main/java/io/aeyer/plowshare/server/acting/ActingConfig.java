package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.server.applications.ApplicationIdentityRepository;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Consent lifecycle, socket contexts and the bounded synchronous history capability. */
@Configuration
@EnableConfigurationProperties(ActingProperties.class)
public class ActingConfig {
  @Bean
  public ActingWorkRepository actingWorkRepository(
      JdbcTemplate jdbc,
      io.aeyer.plowshare.server.archive.ConversationStore conversations,
      ActingRepository consent,
      org.springframework.transaction.PlatformTransactionManager manager) {
    return new JdbcActingWorkRepository(jdbc, conversations, consent, manager);
  }

  @Bean
  public ActingWork actingWork(
      ActingWorkRepository repository,
      ActingAuthorization authorization,
      io.aeyer.plowshare.server.agents.Callers callers,
      io.aeyer.plowshare.server.agents.Turn turns,
      UnitOfWork transactions,
      io.aeyer.plowshare.server.api.ConversationsProperties properties,
      io.aeyer.plowshare.server.access.ResourceScopeRepository resources) {
    ActingAgents agents =
        (subject, agent) ->
            callers.requireAgent(
                agent,
                callers.callerFor(
                    io.aeyer.plowshare.server.personal.PersonalSpaces.name(subject),
                    null,
                    subject));
    ActingTurns executor =
        (conversation, agent, task, admission) ->
            turns.speakAdmitted(conversation, agent, task, admission);
    return new PersonalActingWork(
        repository,
        authorization,
        agents,
        executor,
        transactions,
        properties.defaultBudgetLimit(),
        resources);
  }

  @Bean
  public ActingContexts actingContexts(
      ActingRepository repository, ActingAuthorization authorization) {
    return new BoundActingContexts(repository, authorization, Clock.systemUTC());
  }

  @Bean
  public ActingHistory actingHistory(
      io.aeyer.plowshare.server.access.ResourceScopeRepository resources,
      io.aeyer.plowshare.server.archive.TurnStore turns,
      ActingAuthorization authorization) {
    return new AuthorizedActingHistory(resources, turns, authorization);
  }

  @Bean
  public ActingRepository actingRepository(
      JdbcTemplate jdbc, ApplicationIdentityRepository identities, ActingProperties properties) {
    return new JdbcActingRepository(jdbc, identities, properties::isEnabled);
  }

  @Bean
  public ActingTokens actingTokens(
      ActingRepository repository, ProjectMembers members, UnitOfWork work) {
    return new OwnedActingTokens(repository, members, work, Clock.systemUTC());
  }

  @Bean
  public ActingAuthorization actingAuthorization(
      ActingRepository repository,
      ProjectMembers members,
      io.aeyer.plowshare.server.access.IdentityRepository identities,
      io.aeyer.plowshare.server.access.ScopedAuthority actions) {
    return new LiveActingAuthorization(repository, members, Clock.systemUTC(), identities, actions);
  }
}
