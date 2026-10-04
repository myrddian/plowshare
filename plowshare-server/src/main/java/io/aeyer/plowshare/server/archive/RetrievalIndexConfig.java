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
      LlmProperties llm,
      Environment env) {
    // The operator must bump the generation when an alias's underlying model changes.
    String generation =
        llm.getEmbeddingModel() + ":" + env.getProperty("plowshare.retrieval.generation", "1");
    int ceiling = Math.min(512, llm.getEmbeddingMaxInputTokens());
    return new PassageIndex(
        jdbc,
        transactions,
        embeddings,
        new Chunking(tokenizer, Math.min(384, ceiling), ceiling),
        generation,
        llm.getEmbeddingDim());
  }

  @Bean
  public ConversationSearch conversationSearch(EntryStore entries, PassageIndex index) {
    return new ConversationSearch(entries, index);
  }

  @Bean
  public SmartLifecycle passageRepairWorker(PassageIndex index, Environment env) {
    return new SmartLifecycle() {
      private ScheduledExecutorService worker;

      public boolean isRunning() {
        return worker != null && !worker.isShutdown();
      }

      public boolean isAutoStartup() {
        return env.getProperty("plowshare.retrieval.worker-enabled", Boolean.class, true);
      }

      public void start() {
        if (isRunning()) return;
        worker =
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("retrieval-repair").factory());
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
        if (worker != null) worker.shutdownNow();
      }
    };
  }
}
