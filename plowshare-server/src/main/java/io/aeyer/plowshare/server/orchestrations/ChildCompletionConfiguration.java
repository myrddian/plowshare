package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayConsumerRepository;
import io.aeyer.plowshare.server.relay.RelayProperties;
import io.aeyer.plowshare.server.relay.RelayWorkerProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composes child completion arrival separately from the owning orchestration's wait legality. */
@Configuration
public class ChildCompletionConfiguration {
  @Bean
  @DependsOnDatabaseInitialization
  public ChildCompletions childCompletions(
      JdbcTemplate jdbc, UnitOfWork work, RelayConsumerRepository consumers) {
    return new JdbcChildCompletions(jdbc, work, consumers);
  }

  @Bean(destroyMethod = "close")
  public RelayChildCompletions relayChildCompletions(
      Relay relay,
      RelayConsumerRepository consumers,
      ChildCompletions repository,
      ChildCompletionDelivery delivery,
      UnitOfWork work) {
    return new RelayChildCompletions(relay, consumers, repository, delivery, work);
  }

  @Bean(destroyMethod = "close")
  public ChildCompletionWorker childCompletionWorker(
      ChildCompletions repository,
      ChildCompletionConsumer consumer,
      RelayWorkerProperties properties,
      RelayProperties relay) {
    return new ChildCompletionWorker(repository, consumer, properties, relay.isInternalEnabled());
  }
}
