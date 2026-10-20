package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.documents.Chunking;
import io.aeyer.plowshare.server.llm.*;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.util.concurrent.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class RetrievalIndexConfig {
  @Bean
  public PassageIndex passageIndex(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      EmbeddingClient embeddings,
      Tokenizer tokenizer,
      org.springframework.beans.factory.ObjectProvider<
              io.aeyer.plowshare.server.embedding.EmbeddingTokenizers>
          embeddingTokenizers,
      LlmProperties llm,
      Environment env) {
    // The operator must bump the generation when an alias's underlying model changes.
    String generation =
        llm.getEmbeddingModel() + ":" + env.getProperty("plowshare.retrieval.generation", "1");
    boolean dual = env.getProperty("plowshare.embeddings.enabled", Boolean.class, false);
    if (dual) generation = "dual-passages-v1:" + llm.getEmbeddingMaxInputTokens();
    var counters = embeddingTokenizers.getIfAvailable();
    int allowance =
        counters == null
            ? llm.getEmbeddingMaxInputTokens()
            : counters.documentLimit(llm.getEmbeddingMaxInputTokens());
    if (counters != null) {
      tokenizer = counters.documents();
      generation += ":" + counters.fingerprint();
    }
    int ceiling = Math.min(512, allowance);
    return new PassageIndex(
        new io.aeyer.plowshare.server.archive.JdbcPassageRepository(jdbc, transactions),
        embeddings,
        new Chunking(tokenizer, Math.min(384, ceiling), ceiling),
        generation,
        dual ? 768 : llm.getEmbeddingDim());
  }

  @Bean
  public ConversationSearch conversationSearch(EntryStore entries, PassageIndex index) {
    return new ConversationSearch(entries, index);
  }

  @Bean
  public SmartLifecycle passageRepairWorker(PassageIndex index, Environment env) {
    return new SmartLifecycle() {
      private volatile ScheduledExecutorService worker;
      private boolean closed;
      private volatile boolean running;
      private final CompletableFuture<Void> stopped = new CompletableFuture<>();

      public boolean isRunning() {
        return running;
      }

      public boolean isAutoStartup() {
        return env.getProperty("plowshare.retrieval.worker-enabled", Boolean.class, true);
      }

      public synchronized void start() {
        if (closed || isRunning()) return;
        worker =
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("retrieval-repair").factory());
        running = true;
        worker.scheduleWithFixedDelay(
            () -> {
              try {
                index.repair();
              } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(RetrievalIndexConfig.class)
                    .warn("Retrieval repair deferred: {}", failure.getClass().getSimpleName());
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
          stopping = worker;
        }
        try {
          io.aeyer.plowshare.server.lifecycle.WorkerShutdown.stop(stopping, "retrieval-repair");
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
        Thread.ofVirtual().name("retrieval-repair-shutdown").start(this::stop);
      }
    };
  }
}
