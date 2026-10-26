package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.information.InformationAccess;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/** Common foundation composition, independent of the selected legacy/dual encoder mode. */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class SemanticEmbeddingConfig {
  /** Late binding avoids Archive -> repair -> embedding/runtime composition cycles. */
  @Bean
  public org.springframework.beans.factory.SmartInitializingSingleton remainingEmbeddingConsumers(
      io.aeyer.plowshare.server.archive.Archive archive,
      io.aeyer.plowshare.server.agents.curator.Curator curator,
      EmbeddingRepairs repairs,
      OperationEmbeddings operations,
      EmbeddingProperties properties,
      io.aeyer.plowshare.server.agents.digests.DigestModel digestModel,
      io.aeyer.plowshare.server.agents.digests.Digester digester,
      io.aeyer.plowshare.server.archive.PassageIndex passages,
      QueryEmbeddings queries,
      InformationAccess access) {
    return () -> {
      archive.useRepairs(repairs, properties::maintenanceAllowance);
      curator.useEmbeddingOperations(operations);
      digestModel.useEmbeddingOperations(operations);
      digester.useRepairs(repairs);
      passages.useOperations(queries, access, operations, properties::maintenanceAllowance);
    };
  }

  /** Source/version access is also needed by admitted legacy write-time publication. */
  @Bean
  public EmbeddingWorkRepository embeddingWorkRepository(
      org.springframework.jdbc.core.JdbcTemplate jdbc,
      UnitOfWork transactions,
      EmbeddingSpaceRepository spaces,
      io.aeyer.plowshare.server.reads.ReadAdmission admission) {
    return new JdbcEmbeddingWorkRepository(jdbc, transactions, spaces).useAdmission(admission);
  }

  @Bean
  public QueryEmbeddings queryEmbeddings(
      QueryEmbeddingRepository records,
      InformationAccess access,
      UnitOfWork transactions,
      SemanticEmbedding foundation,
      EmbeddingProperties properties) {
    return new BoundQueryEmbeddings(
        records, access, transactions, foundation, properties::searchLimit);
  }
}
