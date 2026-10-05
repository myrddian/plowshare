package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** A completed cutover must not be silently reversed to unidentified, stale legacy vectors. */
@Configuration
@ConditionalOnProperty(
    name = "plowshare.embeddings.enabled",
    havingValue = "false",
    matchIfMissing = true)
public class LegacyEmbeddingConfig {
  @Bean
  public SmartInitializingSingleton legacyEmbeddingGuard(
      JdbcTemplate jdbc, UnitOfWork work, EmbeddingSpaceRepository spaces) {
    EmbeddingWorkRepository repository = new JdbcEmbeddingWorkRepository(jdbc, work, spaces);
    return () -> {
      for (EmbeddingSlot slot : EmbeddingSlot.values())
        if (repository.active(slot).isPresent())
          throw new IllegalStateException(
              "dual embedding slots have been activated; keep plowshare.embeddings.enabled=true and configure both models");
    };
  }
}
