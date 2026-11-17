package io.aeyer.plowshare.server.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit composition for the two platform Ex operations; ordinary routing does not opt in. */
@Configuration
public class ExternalConversationRequestConfiguration {
  @Bean(destroyMethod = "close")
  BoundedExternalConversationCompletions externalConversationCompletions(
      @Value("${plowshare.conversation-aliases.completion-capacity:256}") int capacity) {
    return new BoundedExternalConversationCompletions(capacity);
  }

  @Bean
  ExternalConversationRequestWork externalConversationRequestWork(
      ExternalConversationCompletions completions) {
    return new ScopedExternalConversationRequestWork(completions);
  }
}
