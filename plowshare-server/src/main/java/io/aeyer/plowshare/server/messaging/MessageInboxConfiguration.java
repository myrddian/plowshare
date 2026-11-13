package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.relay.JdbcRelayRetirementRepository;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayConsumerRepository;
import io.aeyer.plowshare.server.relay.RelayRetirementRepository;
import io.aeyer.plowshare.server.relay.RelayWorkerProperties;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composition keeps messaging storage, publication and existing native dispatch separate. */
@Configuration
public class MessageInboxConfiguration {
  @Bean
  @DependsOnDatabaseInitialization
  public MessagingRepository messagingRepository(
      JdbcTemplate jdbc, AllowanceRepository allowances) {
    return new JdbcMessagingRepository(jdbc, allowances);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public MessageInboxRepository messageInboxRepository(JdbcTemplate jdbc) {
    return new JdbcMessageInboxRepository(jdbc);
  }

  @Bean
  @DependsOnDatabaseInitialization
  public RelayRetirementRepository relayRetirementRepository(JdbcTemplate jdbc, UnitOfWork work) {
    return new JdbcRelayRetirementRepository(jdbc, work);
  }

  @Bean
  public MessagePublications messagePublications(Relay relay, MessagingProperties properties) {
    return new RelayMessagePublications(relay, new MessagePayloadCodec(properties.getBodyBytes()));
  }

  @Bean
  public MessageInboxes messageInboxes(
      MessagingRepository messages,
      MessageInboxRepository inboxes,
      MessagePublications publications,
      Relay relay,
      RelayConsumerRepository consumers,
      FiringStore firings,
      UnitOfWork work,
      Dispatcher dispatcher,
      RelayRetirementRepository retirement,
      ObjectProvider<MessageInboxWorker> workers) {
    return new RelayMessageInboxes(
        messages,
        inboxes,
        publications,
        relay,
        consumers,
        firings,
        work,
        dispatcher::drain,
        Instant::now,
        retirement,
        inbox -> workers.getObject().signal(inbox));
  }

  @Bean(destroyMethod = "close")
  public MessageInboxWorker messageInboxWorker(
      MessageInboxes inboxes, RelayWorkerProperties properties, ObjectProvider<Callers> callers) {
    return new MessageInboxWorker(
        inboxes,
        properties,
        (account, project) -> callers.getObject().visible(Home.of(project), null, account).names());
  }
}
