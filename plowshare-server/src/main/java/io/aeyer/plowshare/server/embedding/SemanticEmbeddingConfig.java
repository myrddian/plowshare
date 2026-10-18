package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.information.InformationAccess;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/** Common foundation composition, independent of the selected legacy/dual encoder mode. */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class SemanticEmbeddingConfig {
  @Bean
  public QueryEmbeddings queryEmbeddings(
      QueryEmbeddingRepository records,
      InformationAccess access,
      UnitOfWork transactions,
      SemanticEmbedding foundation,
      EmbeddingProperties properties) {
    return new BoundQueryEmbeddings(
        records, access, transactions, foundation, properties::searchBudget);
  }
}
