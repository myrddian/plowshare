package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.CommandCatalog;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.agents.SkillResolver;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.relay.MessageRelayReceiver;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayForwardingHistory;
import io.aeyer.plowshare.server.relay.RelayPorts;
import io.aeyer.plowshare.server.relay.RelayProperties;
import io.aeyer.plowshare.server.relay.RelayPublisherAccounts;
import io.aeyer.plowshare.server.relay.RelayPublishingProperties;
import io.aeyer.plowshare.server.relay.RelayReceivers;
import io.aeyer.plowshare.server.relay.RelayRepository;
import io.aeyer.plowshare.server.relay.RelayWorkerProperties;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composes public Relay receiver adapters with the same native messaging owner used by schedules.
 */
@Configuration
@EnableConfigurationProperties(MessageRelayProperties.class)
public class MessageRelayConfiguration {
  @Bean
  @DependsOnDatabaseInitialization
  public MessageRelayRepository messageRelayRepository(JdbcTemplate jdbc) {
    return new JdbcMessageRelayRepository(jdbc);
  }

  @Bean
  public MessageRelayPages messageRelayPages(
      @Lazy MessageIngress messages, MessageRelayRepository repository, UnitOfWork work) {
    return new MessageRelayPages(messages, repository, work);
  }

  @Bean
  public MessageRelayAccess messageRelayAccess(
      RelayPorts ports, RelayPublishingProperties publishing) {
    return new LiveMessageRelayAccess(ports, publishing);
  }

  @Bean
  public MessageRelayDefinitions messageRelayDefinitions(
      Callers callers, SkillResolver skills, ObjectProvider<OrchestrationResolver> orchestrations) {
    return (family, command) -> {
      var caller = new DefinitionResolver.Caller(family.projectId(), null, family.account());
      try {
        var definition = callers.requireAgent(family.agent(), caller);
        if (command == null) return;
        var procedures = orchestrations.getIfAvailable();
        var catalog =
            CommandCatalog.of(
                definition,
                skills.forCaller(caller),
                procedures == null ? Map.of() : procedures.forCaller(caller));
        String qualified = command.split(" ", 2)[0];
        if (catalog.stream().noneMatch(entry -> entry.command().equals(qualified)))
          throw new CallerFault("The native command is unavailable to the receiving definition");
      } catch (NotFoundFault missing) {
        throw new CallerFault("The receiving definition is unavailable");
      }
    };
  }

  @Bean
  public MessageRelayIngress messageRelayIngress(
      @Lazy MessageIngress messages,
      MessageRelayRepository repository,
      MessageRelayPages pages,
      MessageRelayDefinitions definitions,
      UnitOfWork work,
      MessageRelayProperties properties,
      ObjectProvider<MessageRelayWorker> workers) {
    return new RelayMessageIngress(
        messages,
        repository,
        pages,
        definitions,
        work,
        properties,
        () -> workers.getObject().signal());
  }

  @Bean
  public MessageRelayOutbox messageRelayOutbox(
      MessageRelayRepository repository,
      MessageRelayPages pages,
      MessageRelayAccess access,
      Relay relay,
      UnitOfWork work) {
    return new RelayMessageRelayOutbox(repository, pages, access, relay, work);
  }

  @Bean(destroyMethod = "close")
  public MessageRelayWorker messageRelayWorker(
      MessageRelayOutbox outbox, RelayWorkerProperties properties) {
    return new MessageRelayWorker(outbox, properties);
  }

  @Bean
  public RelayReceivers.Binding messageReceiveReceiver(
      @Lazy MessageRelayIngress ingress,
      MessageRelayAccess access,
      RelayPublisherAccounts publishers,
      RelayRepository topics,
      RelayForwardingHistory history,
      RelayProperties properties) {
    return receiver("message.receive", ingress, access, publishers, topics, history, properties);
  }

  @Bean
  public RelayReceivers.Binding messageCancelReceiver(
      @Lazy MessageRelayIngress ingress,
      MessageRelayAccess access,
      RelayPublisherAccounts publishers,
      RelayRepository topics,
      RelayForwardingHistory history,
      RelayProperties properties) {
    return receiver("message.cancel", ingress, access, publishers, topics, history, properties);
  }

  @Bean
  public RelayReceivers.Binding messageInspectReceiver(
      @Lazy MessageRelayIngress ingress,
      MessageRelayAccess access,
      RelayPublisherAccounts publishers,
      RelayRepository topics,
      RelayForwardingHistory history,
      RelayProperties properties) {
    return receiver("message.inspect", ingress, access, publishers, topics, history, properties);
  }

  private static RelayReceivers.Binding receiver(
      String receiver,
      MessageRelayIngress ingress,
      MessageRelayAccess access,
      RelayPublisherAccounts publishers,
      RelayRepository topics,
      RelayForwardingHistory history,
      RelayProperties properties) {
    return new RelayReceivers.Binding(
        receiver,
        new MessageRelayReceiver(
            receiver,
            ingress,
            access,
            publishers,
            topics,
            history,
            properties.getMaxForwardingHops(),
            Instant::now));
  }
}
