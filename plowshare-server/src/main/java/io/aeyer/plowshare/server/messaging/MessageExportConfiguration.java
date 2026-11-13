package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.MessageReturnReceiver;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPorts;
import io.aeyer.plowshare.server.relay.RelayProperties;
import io.aeyer.plowshare.server.relay.RelayPublisherAccounts;
import io.aeyer.plowshare.server.relay.RelayReceivers;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/** Messaging owns exported request/return policy, native requester evidence and receiver wiring. */
@Configuration
@EnableConfigurationProperties(MessageExportProperties.class)
public class MessageExportConfiguration {
  @Bean
  @DependsOnDatabaseInitialization
  public MessageExportRepository messageExportRepository(JdbcTemplate jdbc) {
    return new JdbcMessageExportRepository(jdbc);
  }

  @Bean
  public RelayMessageExports messageExports(
      MessageExportProperties properties,
      MessageExportRepository repository,
      @Lazy NativeMessageExports nativeMessages,
      Relay relay,
      RelayPorts ports,
      ProjectMembers members,
      ProjectWorkspaces projects,
      UnitOfWork work,
      RelayProperties limits) {
    return new RelayMessageExports(
        properties,
        repository,
        nativeMessages,
        relay,
        ports,
        members,
        projects,
        work,
        limits.getMaxForwardingHops());
  }

  @Bean
  public RelayReceivers.Binding messageReturnReceiver(
      @Lazy MessageExports exports, RelayPublisherAccounts publishers) {
    return new RelayReceivers.Binding(
        "message.return", new MessageReturnReceiver(exports, publishers));
  }
}
