package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.archive.ConversationAliasProperties;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit composition for the two platform Ex operations; ordinary routing does not opt in. */
@Configuration
@EnableConfigurationProperties(ConversationAliasProperties.class)
public class ExternalConversationRequestConfiguration {
  @Bean(destroyMethod = "close")
  BoundedExternalConversationCompletions externalConversationCompletions(
      ConversationAliasProperties budgets,
      @Value("${plowshare.conversation-aliases.completion-capacity:256}") int capacity) {
    return new BoundedExternalConversationCompletions(budgets.timeout(), capacity);
  }

  @Bean
  ExternalConversationRequestWork externalConversationRequestWork(
      SqlWorkBudget sql,
      ConversationAliasProperties budgets,
      ExternalConversationCompletions completions) {
    return new ScopedExternalConversationRequestWork(sql, budgets, completions);
  }
}
