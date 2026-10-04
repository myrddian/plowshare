package io.aeyer.plowshare.server.agents.digests;

import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.LlmConfig;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class DigestConfig {
  /**
   * Refuses the retired {@code plowshare.memory.model} key here rather than in {@code LlmConfig},
   * on the controller's ruling for this task: {@code MemoryProperties} is already constructed in
   * this class, and a {@code Environment} is no more awkward to take here than the properties
   * object already is. See {@link LlmConfig#requireMemoryModelRetired} for the message and the
   * argument for checking the raw key at all, a commit before {@code MemoryProperties.model} is
   * actually removed.
   */
  public DigestConfig(Environment environment) {
    LlmConfig.requireMemoryModelRetired(retiredMemoryKeyProbe(environment));
  }

  private static Map<String, Object> retiredMemoryKeyProbe(Environment environment) {
    String key = "plowshare.memory.model";
    return environment.containsProperty(key) ? Map.of(key, environment.getProperty(key)) : Map.of();
  }

  @Bean
  public DigestStore digestStore(JdbcTemplate jdbc, UnitOfWork transactions, PassageIndex index) {
    return new DigestStore(jdbc, transactions).embeddingGeneration(index.generation());
  }

  @Bean
  public DigestModel digestModel(
      LlmDispatcher dispatcher,
      ObjectProvider<Compaction> logs,
      MemoryProperties properties,
      LlmProperties llmProperties) {
    return new DigestModel(
        dispatcher, logs::getObject, properties, () -> llmProperties.systemSpecifier("memory"));
  }

  @Bean
  public Digester digester(
      DigestStore store,
      Archive archive,
      DigestModel model,
      EmbeddingClient embeddings,
      MemoryProperties properties) {
    return new Digester(store, archive, model, embeddings, properties);
  }

  @Bean
  public Navigator navigator(
      DigestStore store,
      Archive archive,
      EntryStore entries,
      ReasonLog reasons,
      DigestModel model,
      PassageIndex index) {
    return new Navigator(store, archive, entries, reasons, model).seeded(index);
  }
}
