package io.aeyer.plowshare.server.archive;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Composes the alias repository with the existing ordinary creation and transaction owners. */
@Configuration
@EnableConfigurationProperties(ConversationAliasProperties.class)
public class ConversationAliasConfiguration {
  @Bean(destroyMethod = "close")
  public JdbcConversationAliasRepository conversationAliasRepository(
      JdbcTemplate jdbc,
      ConversationCreation conversations,
      PlatformTransactionManager transactions,
      ConversationAliasProperties properties,
      MeterRegistry metrics) {
    return new JdbcConversationAliasRepository(
        jdbc, conversations, transactions, properties, metrics);
  }
}
