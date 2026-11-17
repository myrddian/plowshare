package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.documents.Chunking;
import io.aeyer.plowshare.server.embedding.EmbeddingTokenizers;
import io.aeyer.plowshare.server.information.InformationLogVisibility;
import io.aeyer.plowshare.server.lifecycle.WorkerShutdown;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import io.aeyer.plowshare.server.telemetry.WorkPerformanceReporter;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
      ObjectProvider<EmbeddingTokenizers> embeddingTokenizers,
      LlmProperties llm,
      InformationLogVisibility visibility,
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
        new JdbcPassageRepository(
            jdbc,
            transactions,
            env.getProperty(
                "plowshare.retrieval.search-budget", Duration.class, Duration.ofSeconds(2)),
            visibility),
        embeddings,
        new Chunking(tokenizer, Math.min(384, ceiling), ceiling),
        generation,
        dual ? 768 : llm.getEmbeddingDim());
  }

  @Bean
  public ConversationSearch conversationSearch(
      EntryStore entries,
      PassageIndex index,
      Environment env,
      WorkPerformanceReporter performance) {
    return new ConversationSearch(
        entries,
        index,
        env.getProperty("plowshare.retrieval.search-budget", Duration.class, Duration.ofSeconds(2)),
        performance);
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
                LoggerFactory.getLogger(RetrievalIndexConfig.class)
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
          WorkerShutdown.stop(stopping, "retrieval-repair");
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
