package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.agents.digests.Digester;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.util.concurrent.*;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composition for the opt-in transition. Both models are required; no deployment defaults. */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
@ConditionalOnProperty(name = "plowshare.embeddings.enabled", havingValue = "true")
public class EmbeddingConfig {
  @Bean
  public EmbeddingWorkRepository embeddingWorkRepository(
      JdbcTemplate jdbc, UnitOfWork work, EmbeddingSpaceRepository spaces) {
    return new JdbcEmbeddingWorkRepository(jdbc, work, spaces);
  }

  @Bean
  public DispatchingDualEmbeddings dualEmbeddings(
      EmbeddingProperties properties,
      EmbeddingSpaceRepository spaces,
      EmbeddingWorkRepository work,
      LlmDispatcher dispatcher,
      EmbeddingTokenizers tokenizers) {
    properties.validate();
    for (EmbeddingSlot slot : EmbeddingSlot.values()) {
      var model = properties.model(slot);
      dispatcher.requireServed(model.modelId());
      var space = spaces.register(model.definition());
      work.active(slot)
          .ifPresent(
              active -> {
                tokenizers.model(active.space().definition());
                // A friendly alias cannot serve two different weight identities concurrently.
                // Retain the old served name during a rolling rebuild. Client-side prefix and
                // normalization changes may reuse an encoder; changed weights/output may not.
                if (!active.space().equals(space)
                    && active.space().definition().modelId().equals(model.modelId())
                    && !EmbeddingProperties.sameEncoder(
                        active.space().definition(), space.definition()))
                  throw new IllegalArgumentException(
                      "an active embedding alias cannot be relabelled to different weights or preprocessing; configure a distinct served model name for the replacement");
              });
      work.inputLimit(space, model.maxInputTokens());
      work.configure(slot, space, model.policy());
    }
    return new DispatchingDualEmbeddings(work, dispatcher::embed, tokenizers);
  }

  @Bean
  public SmartInitializingSingleton dualEmbeddingBinding(
      DualEmbeddings dual,
      Archive archive,
      MemoryStore memories,
      DocumentStore documents,
      RetrievalService retrieval,
      IngestService ingest,
      SummaryEmbeddings summaries,
      PassageIndex passages,
      Digester digester) {
    return () -> {
      archive.useDualEmbeddings(dual);
      memories.useDualEmbeddings(dual);
      documents.useDualEmbeddings(dual);
      retrieval.useDualEmbeddings(dual);
      ingest.useDualEmbeddings(dual);
      summaries.useDualEmbeddings(dual);
      passages.useDualEmbeddings(dual);
      digester.useDualEmbeddings(dual);
    };
  }

  @Bean
  public SmartLifecycle dualEmbeddingRepairWorker(
      EmbeddingMaintenance dual, EmbeddingProperties properties) {
    return new SmartLifecycle() {
      private ScheduledExecutorService executor;

      public boolean isRunning() {
        return executor != null && !executor.isShutdown();
      }

      public void start() {
        if (isRunning()) return;
        executor =
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("dual-embedding-repair").factory());
        executor.scheduleWithFixedDelay(
            () -> {
              if (properties.paused()) return;
              try {
                dual.tick();
              } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(EmbeddingConfig.class)
                    .warn(
                        "Embedding repair/activation deferred: {}",
                        failure.getClass().getSimpleName());
              }
            },
            2,
            2,
            TimeUnit.SECONDS);
      }

      public void stop() {
        if (executor != null) executor.shutdownNow();
      }
    };
  }
}
