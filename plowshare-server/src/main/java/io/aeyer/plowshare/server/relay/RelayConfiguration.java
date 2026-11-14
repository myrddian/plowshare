package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.agents.ApplicationResources;
import io.aeyer.plowshare.server.agents.EventRuns;
import io.aeyer.plowshare.server.agents.WorkCallers;
import io.aeyer.plowshare.server.approvals.ApprovalNotifications;
import io.aeyer.plowshare.server.archive.ProjectCatalogue;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.blobs.BlobRepository;
import io.aeyer.plowshare.server.cache.ProjectionCaches;
import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.EventsProperties;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.orchestrations.GrantedOrchestrations;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStarts;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore;
import io.aeyer.plowshare.server.personal.PersonalWorkspaces;
import io.aeyer.plowshare.server.relay.tools.ApplicationToolRegistry;
import io.aeyer.plowshare.server.session.ProjectPresences;
import io.aeyer.plowshare.server.session.SessionOwners;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
      ApplicationToolRegistry tools,
      RelayPublicationSignals signals) {
    return new ProjectRelayPorts(
        relay,
        repository,
        properties,
        members,
        projects,
        relayProperties.getMaxForwardingHops(),
        tools,
        signals);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayRepository relayRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      RelayPayloadStore payloads,
      RelayProperties properties,
      BlobRepository blobs) {
    return new JdbcRelayRepository(jdbc, transactions, payloads, properties.textLimit(), blobs);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayPayloadStore relayPayloadStore(
      JdbcTemplate jdbc, PlatformTransactionManager manager, RelayProperties properties) {
    // Selection locks must be followed by a fresh statement snapshot, even when a caller already
    // owns a repeatable-read transaction. Bound total batch time as well as individual statements.
    var template = new TransactionTemplate(manager);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setTimeout(5);
    UnitOfWork cleanup =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> action) {
            return Objects.requireNonNull(template.execute(status -> action.get()));
          }
        };
    return new JdbcRelayPayloadStore(
        jdbc,
        cleanup,
        properties.getPayloadStagingWindow(),
        properties.getPayloadDeferralCeiling(),
        properties.getSettledRetention());
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
      FiringStore firings) {
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
      Dispatcher dispatcher,
      RelayWorkerProperties properties,
      RelayProperties internal,
      ObjectProvider<RelayApprovalInbox> approvals) {
    var workers =
        new RelayInternalWorkers(
            sources,
            inboxes,
            consumers,
            relay,
            signals,
            dispatcher,
            properties,
            internal.isInternalEnabled());
    workers.useApprovalInbox(approvals::getObject);
    return workers;
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayApprovalInboxRepository relayApprovalInboxRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      Relay relay,
      RelayConsumerRepository consumers,
      EventsProperties properties) {
    return new JdbcRelayApprovalInboxRepository(
        jdbc, transactions, relay, consumers, properties.getQuestionAlertRetryInterval());
  }

  @Bean
  public RelayApprovalInbox relayApprovalInbox(
      Relay relay,
      RelayConsumerRepository consumers,
      RelayApprovalInboxRepository repository,
      ApprovalNotifications notifications) {
    return new DurableRelayApprovalInbox(relay, consumers, repository, notifications);
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

  @Bean(destroyMethod = "close")
  public RelayPublicationSignals relayPublicationSignals(
      ProjectionChangeSignals signals, RelayWorkerProperties properties) {
    return new NotificationRelayPublicationSignals(signals, properties);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayConsumerRepository relayConsumerRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      RelayDeliveryRepository deliveries,
      RelayRepository subscriptions) {
    return new JdbcRelayConsumerRepository(jdbc, transactions, deliveries, subscriptions);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelaySubscriptionAuthority relaySubscriptionAuthority(JdbcTemplate jdbc) {
    return new JdbcRelaySubscriptionAuthority(jdbc);
  }

  /**
   * Read-tier subscription and consumption (decision 0038). The maximum age is read here so a
   * deployment that omits it fails at startup, not on a caller's first subscription.
   */
  @Bean
  public RelayConsumption relayConsumption(
      ProjectMembers members,
      ProjectWorkspaces projects,
      Relay relay,
      RelayConsumerRepository consumers,
      RelaySubscriptionAuthority authority,
      RelayProperties properties) {
    return new ReadTierRelayConsumption(
        members,
        projects,
        relay,
        consumers,
        authority,
        properties.subscriptionMaximumAge(),
        Instant::now);
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
      RelayWorkerProperties properties,
      RelayWorkerBindings bindings) {
    return new RelayWorkers(projects, work, signals, properties, bindings);
  }

  @Bean
  public RelayWorkerBindings relayWorkerBindings(
      ProjectCatalogue projects,
      ApplicationResources resources,
      ProjectMembers members,
      RelayWorkerProperties properties) {
    return new ApplicationRelayWorkerBindings(projects, resources, members, properties);
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
          if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                "Relay receiver dispatch must run outside a database transaction");
        });
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayLogRepository relayLogRepository(
      JdbcTemplate jdbc, UnitOfWork work, ProjectionCaches caches) {
    var domains = Set.of(ProjectionVersions.Domain.RELAY, ProjectionVersions.Domain.AUTHORITY);
    return new JdbcRelayLogRepository(
        jdbc,
        work,
        caches.create("relay-topics-v1", domains),
        caches.create("relay-inspection-v1", domains));
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
      WorkCallers callers,
      EventRuns jobs,
      GrantedOrchestrations grants,
      OrchestrationStarts starts,
      OrchestrationStore runs,
      RelayExecutions executions,
      RelayProjectFiles files,
      ProjectWorkspaces projects,
      ProjectPresences presences,
      SessionOwners sessions,
      MessageService.Routing routing,
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
      WorkCallers callers,
      EventRuns jobs,
      GrantedOrchestrations grants,
      OrchestrationStarts starts,
      OrchestrationStore runs,
      RelayExecutions executions,
      RelayProjectFiles files,
      ProjectWorkspaces projects,
      ProjectPresences presences,
      SessionOwners sessions,
      MessageService.Routing routing,
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
      WorkCallers callers,
      EventRuns jobs,
      GrantedOrchestrations grants,
      OrchestrationStarts starts,
      OrchestrationStore runs,
      RelayExecutions executions,
      RelayProjectFiles files,
      ProjectWorkspaces projects,
      ProjectPresences presences,
      SessionOwners sessions,
      MessageService.Routing routing,
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
      WorkCallers callers,
      EventRuns jobs,
      GrantedOrchestrations grants,
      OrchestrationStarts starts,
      OrchestrationStore runs,
      RelayExecutions executions,
      RelayProjectFiles files,
      ProjectWorkspaces projects,
      ProjectPresences presences,
      SessionOwners sessions,
      MessageService.Routing routing,
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
      RelayNativeRepository nativeInboxes,
      RelayApprovalInboxRepository approvals,
      RelayPayloadStore payloads) {
    var maintenance =
        new RelayMaintenance(
            repository,
            deliveries,
            executions,
            properties,
            Instant::now,
            operations,
            nativeInboxes);
    maintenance.useApprovalInbox(approvals);
    maintenance.usePayloads(payloads);
    return maintenance;
  }
}
