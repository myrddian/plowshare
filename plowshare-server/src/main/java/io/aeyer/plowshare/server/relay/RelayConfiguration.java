package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.personal.PersonalWorkspaces;
import io.aeyer.plowshare.server.session.ProjectPresences;
import io.aeyer.plowshare.server.session.SessionOwners;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composes the broker, independent workers and adapters to owning lifecycle/inbox contracts. */
@Configuration
@EnableConfigurationProperties({
  RelayProperties.class,
  RelayWorkerProperties.class,
  RelayPortProperties.class
})
public class RelayConfiguration {
  @Bean
  @DependsOnDatabaseInitialization
  public RelayPortRepository relayPortRepository(
      JdbcTemplate jdbc, UnitOfWork transactions, Relay relay) {
    return new JdbcRelayPortRepository(jdbc, transactions, relay);
  }

  @Bean
  public RelayPorts relayPorts(
      Relay relay,
      RelayPortRepository repository,
      RelayPortProperties properties,
      ProjectMembers members,
      ProjectWorkspaces projects,
      RelayProperties relayProperties,
      io.aeyer.plowshare.server.relay.tools.ApplicationToolRegistry tools) {
    return new ProjectRelayPorts(
        relay,
        repository,
        properties,
        members,
        projects,
        relayProperties.getMaxForwardingHops(),
        tools);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayRepository relayRepository(
      JdbcTemplate jdbc, UnitOfWork transactions, RelayProperties properties) {
    return new JdbcRelayRepository(jdbc, transactions, properties.textLimit());
  }

  @Bean
  public Relay relay(
      RelayRepository repository,
      UnitOfWork transactions,
      RelayPublicationSignals signals,
      RelayProperties properties) {
    return new DurableRelay(
        repository,
        Instant::now,
        topic -> transactions.afterCommit(() -> signals.published(topic)),
        properties::systemPolicy);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayNativeRepository relayNativeRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      Relay relay,
      RelayConsumerRepository consumers,
      io.aeyer.plowshare.server.events.FiringStore firings) {
    return new JdbcRelayNativeRepository(jdbc, transactions, relay, consumers, firings);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelaySourceRepository relaySourceRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      Relay relay,
      RelayNativeRepository nativeInboxes) {
    return new JdbcRelaySourceRepository(jdbc, transactions, relay, nativeInboxes);
  }

  @Bean(destroyMethod = "close")
  public RelayInternalWorkers relayInternalWorkers(
      RelaySourceRepository sources,
      RelayNativeRepository inboxes,
      RelayConsumerRepository consumers,
      Relay relay,
      RelayPublicationSignals signals,
      io.aeyer.plowshare.server.events.Dispatcher dispatcher,
      RelayWorkerProperties properties,
      RelayProperties internal) {
    return new RelayInternalWorkers(
        sources,
        inboxes,
        consumers,
        relay,
        signals,
        dispatcher,
        properties,
        internal.isInternalEnabled());
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayOperationRepository relayOperationRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      RelayRepository broker,
      RelayDeliveryRepository deliveries) {
    return new JdbcRelayOperationRepository(jdbc, transactions, broker, deliveries);
  }

  @Bean
  public RelayOperations relayOperations(
      ProjectMembers members,
      ProjectWorkspaces projects,
      RelayRouting routing,
      RelayDispatch dispatch,
      RelayOperationRepository repository,
      RelayPublicationSignals signals,
      UnitOfWork transactions) {
    return new ProjectRelayOperations(
        members, projects, routing, dispatch, repository, signals, transactions);
  }

  @Bean
  public RelayPublicationSignals relayPublicationSignals() {
    return new LocalRelayPublicationSignals();
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayConsumerRepository relayConsumerRepository(
      JdbcTemplate jdbc, UnitOfWork transactions, RelayDeliveryRepository deliveries) {
    return new JdbcRelayConsumerRepository(jdbc, transactions, deliveries);
  }

  @Bean
  public RelaySubscriptionWork relaySubscriptionWork(
      RelayProjectFiles files,
      ProjectMembers members,
      RelayRouting routing,
      Relay relay,
      RelayDispatch dispatch,
      RelayConsumerRepository consumers) {
    return new OwnedRelaySubscriptionWork(files, members, routing, relay, dispatch, consumers);
  }

  @Bean(destroyMethod = "close")
  public RelayWorkers relayWorkers(
      ProjectWorkspaces projects,
      RelaySubscriptionWork work,
      RelayPublicationSignals signals,
      RelayWorkerProperties properties) {
    return new RelayWorkers(projects, work, signals, properties);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayDeliveryRepository relayDeliveryRepository(
      JdbcTemplate jdbc, UnitOfWork transactions) {
    return new JdbcRelayDeliveryRepository(jdbc, transactions);
  }

  @Bean
  public RelayDeliveries relayDeliveries(RelayDeliveryRepository repository) {
    return new DurableRelayDeliveries(repository, Instant::now);
  }

  @Bean
  public RelayProjectFiles relayProjectFiles(
      ProjectWorkspaces projects,
      ProjectMembers members,
      PersonalWorkspaces personal,
      ProjectPresences presences,
      SessionOwners sessions,
      SessionChannel channel) {
    return new WorkspaceRelayProjectFiles(
        projects,
        new ServerRelayProjectFiles(projects, members, personal),
        new RemoteRelayProjectFiles(projects, members, presences, sessions, channel));
  }

  @Bean(destroyMethod = "close")
  public GraalRelayRouteProgram relayRouteProgram() {
    return new GraalRelayRouteProgram();
  }

  @Bean
  public RelayRouting relayRouting(
      RelayProjectFiles files,
      RelayRouteProgram programs,
      Relay relay,
      RelayDeliveries deliveries) {
    return new ProjectRelayRouting(files, programs, relay, deliveries);
  }

  @Bean
  public RelayReceivers relayReceivers(List<RelayReceivers.Binding> bindings) {
    return new RegisteredRelayReceivers(bindings);
  }

  @Bean
  public RelayReceivers.Binding relayPublicationReceiver(
      Relay relay, RelayForwardingHistory history, RelayProperties properties) {
    return new RelayReceivers.Binding(
        "relay.publish",
        new ForwardRelayReceiver(relay, history, properties.getMaxForwardingHops()));
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayForwardingHistory relayForwardingHistory(JdbcTemplate jdbc) {
    return new JdbcRelayForwardingHistory(jdbc);
  }

  @Bean
  public RelayDispatch relayDispatch(
      RelayDeliveries deliveries,
      RelayReceivers receivers,
      ProjectMembers members,
      ProjectWorkspaces projects) {
    return new ReceiverRelayDispatch(
        deliveries,
        receivers,
        members,
        projects,
        () -> {
          if (org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive())
            throw new IllegalStateException(
                "Relay receiver dispatch must run outside a database transaction");
        });
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayLogRepository relayLogRepository(JdbcTemplate jdbc, UnitOfWork work) {
    return new JdbcRelayLogRepository(jdbc, work);
  }

  @Bean
  public RelayProcessing relayProcessing(
      ProjectWorkspaces projects, ProjectMembers members, RelaySubscriptionWork work) {
    return new ProjectRelayProcessing(projects, members, work);
  }

  @Bean
  public RelayInspection relayInspection(
      ProjectMembers members, ProjectWorkspaces projects, RelayLogRepository logs) {
    return new ScopedRelayInspection(members, projects, logs);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayExecutions relayExecutions(JdbcTemplate jdbc) {
    return new JdbcRelayExecutions(jdbc);
  }

  @Bean
  public RelayReceivers.Binding relayAgentReceiver(
      io.aeyer.plowshare.server.agents.WorkCallers callers,
      io.aeyer.plowshare.server.agents.EventRuns jobs,
      io.aeyer.plowshare.server.orchestrations.GrantedOrchestrations grants,
      io.aeyer.plowshare.server.orchestrations.OrchestrationStarts starts,
      io.aeyer.plowshare.server.orchestrations.OrchestrationStore runs,
      RelayExecutions executions,
      RelayProjectFiles files,
      ProjectWorkspaces projects,
      ProjectPresences presences,
      SessionOwners sessions,
      io.aeyer.plowshare.server.board.BoardMessaging.Routing routing,
      RelayForwardingHistory history,
      RelayProperties properties) {
    return workReceiver(
        "agent.run",
        callers,
        jobs,
        grants,
        starts,
        runs,
        executions,
        files,
        projects,
        presences,
        sessions,
        routing,
        history,
        properties);
  }

  @Bean
  public RelayReceivers.Binding relayScriptReceiver(
      io.aeyer.plowshare.server.agents.WorkCallers callers,
      io.aeyer.plowshare.server.agents.EventRuns jobs,
      io.aeyer.plowshare.server.orchestrations.GrantedOrchestrations grants,
      io.aeyer.plowshare.server.orchestrations.OrchestrationStarts starts,
      io.aeyer.plowshare.server.orchestrations.OrchestrationStore runs,
      RelayExecutions executions,
      RelayProjectFiles files,
      ProjectWorkspaces projects,
      ProjectPresences presences,
      SessionOwners sessions,
      io.aeyer.plowshare.server.board.BoardMessaging.Routing routing,
      RelayForwardingHistory history,
      RelayProperties properties) {
    return workReceiver(
        "script.run",
        callers,
        jobs,
        grants,
        starts,
        runs,
        executions,
        files,
        projects,
        presences,
        sessions,
        routing,
        history,
        properties);
  }

  @Bean
  public RelayReceivers.Binding relayOrchestrationReceiver(
      io.aeyer.plowshare.server.agents.WorkCallers callers,
      io.aeyer.plowshare.server.agents.EventRuns jobs,
      io.aeyer.plowshare.server.orchestrations.GrantedOrchestrations grants,
      io.aeyer.plowshare.server.orchestrations.OrchestrationStarts starts,
      io.aeyer.plowshare.server.orchestrations.OrchestrationStore runs,
      RelayExecutions executions,
      RelayProjectFiles files,
      ProjectWorkspaces projects,
      ProjectPresences presences,
      SessionOwners sessions,
      io.aeyer.plowshare.server.board.BoardMessaging.Routing routing,
      RelayForwardingHistory history,
      RelayProperties properties) {
    return workReceiver(
        "orchestration.start",
        callers,
        jobs,
        grants,
        starts,
        runs,
        executions,
        files,
        projects,
        presences,
        sessions,
        routing,
        history,
        properties);
  }

  private static RelayReceivers.Binding workReceiver(
      String name,
      io.aeyer.plowshare.server.agents.WorkCallers callers,
      io.aeyer.plowshare.server.agents.EventRuns jobs,
      io.aeyer.plowshare.server.orchestrations.GrantedOrchestrations grants,
      io.aeyer.plowshare.server.orchestrations.OrchestrationStarts starts,
      io.aeyer.plowshare.server.orchestrations.OrchestrationStore runs,
      RelayExecutions executions,
      RelayProjectFiles files,
      ProjectWorkspaces projects,
      ProjectPresences presences,
      SessionOwners sessions,
      io.aeyer.plowshare.server.board.BoardMessaging.Routing routing,
      RelayForwardingHistory history,
      RelayProperties properties) {
    return new RelayReceivers.Binding(
        name,
        new WorkRelayReceiver(
            name,
            callers,
            jobs,
            grants,
            starts,
            runs,
            executions,
            files,
            projects,
            presences,
            sessions,
            routing,
            history,
            properties.getMaxForwardingHops()));
  }

  @Bean(destroyMethod = "close")
  public RelayMaintenance relayMaintenance(
      RelayRepository repository,
      RelayDeliveryRepository deliveries,
      RelayExecutions executions,
      RelayProperties properties,
      RelayOperationRepository operations,
      RelayNativeRepository nativeInboxes) {
    return new RelayMaintenance(
        repository, deliveries, executions, properties, Instant::now, operations, nativeInboxes);
  }
}
