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

/** Composition for the opt-in transition. Both models are required; no deployment defaults. */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
@ConditionalOnProperty(name = "plowshare.embeddings.enabled", havingValue = "true")
public class EmbeddingConfig {
  @Bean
  public DispatchingDualEmbeddings dualEmbeddings(
      EmbeddingProperties properties,
      EmbeddingSpaceRepository spaces,
      EmbeddingWorkRepository work,
      LlmDispatcher dispatcher,
      EmbeddingTokenizers tokenizers,
      OperationEmbeddings operations) {
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
    var dual = new DispatchingDualEmbeddings(work, dispatcher::embed, tokenizers);
    dual.useOperations(operations, properties::maintenanceAllowance);
    return dual;
  }

  @Bean
  public SmartInitializingSingleton dualEmbeddingBinding(
      DualEmbeddings dual,
      Archive archive,
      MemoryRepository memories,
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
      private volatile ScheduledExecutorService executor;
      private boolean closed;
      private volatile boolean running;
      private final CompletableFuture<Void> stopped = new CompletableFuture<>();

      public boolean isRunning() {
        return running;
      }

      public synchronized void start() {
        if (closed || isRunning()) return;
        executor =
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("dual-embedding-repair").factory());
        running = true;
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
        ScheduledExecutorService stopping;
        synchronized (this) {
          if (closed) return;
          closed = true;
          stopping = executor;
        }
        try {
          io.aeyer.plowshare.server.lifecycle.WorkerShutdown.stop(
              stopping, "dual-embedding-repair");
          running = false;
          stopped.complete(null);
        } catch (RuntimeException | Error failure) {
          running = false;
          stopped.completeExceptionally(failure);
          throw failure;
        }
      }

      public void stop(Runnable callback) {
        stopped.whenComplete((ignored, failure) -> callback.run());
        Thread.ofVirtual().name("dual-embedding-repair-shutdown").start(this::stop);
      }
    };
  }
}
