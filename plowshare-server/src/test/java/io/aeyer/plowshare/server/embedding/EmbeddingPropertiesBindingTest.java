package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class EmbeddingPropertiesBindingTest {
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(EmbeddingProperties.class)
  static class Binding {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(Binding.class);

  private ApplicationContextRunner configured() {
    var configured = runner.withPropertyValues("plowshare.embeddings.enabled=true");
    for (String slot : new String[] {"code", "prose"})
      configured =
          configured.withPropertyValues(
              "plowshare.embeddings." + slot + ".model-id=" + slot + "-encoder",
              "plowshare.embeddings." + slot + ".model-revision=weights-v1",
              "plowshare.embeddings." + slot + ".dimensions=4096",
              "plowshare.embeddings." + slot + ".query-prefix=",
              "plowshare.embeddings." + slot + ".document-prefix=",
              "plowshare.embeddings." + slot + ".pooling=cls",
              "plowshare.embeddings." + slot + ".normalization=l2",
              "plowshare.embeddings." + slot + ".reduction=none",
              "plowshare.embeddings." + slot + ".max-input-tokens=8192",
              "plowshare.embeddings." + slot + ".search-mode=hnsw_binary",
              "plowshare.embeddings." + slot + ".distance=cosine",
              "plowshare.embeddings." + slot + ".tokenizer.file=/explicit/fixture/tokenizer.json",
              "plowshare.embeddings." + slot + ".tokenizer.sha256=" + "a".repeat(64));
    return configured;
  }

  @Test
  void bothSlotsBindWithExplicitEmptyPrefixesAndNoLegacyModelSettings() {
    configured()
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var properties = context.getBean(EmbeddingProperties.class);
              properties.validate();
              assertEquals("", properties.code().queryPrefix());
              assertEquals("prose-encoder", properties.prose().modelId());
              assertEquals(4096, properties.code().definition().dimensions());
              assertEquals(
                  EmbeddingSearchPolicy.Distance.COSINE, properties.code().policy().distance());
            });
  }

  @Test
  void unsupportedFieldsDoNotSilentlyBind() {
    configured()
        .withPropertyValues("plowshare.embeddings.code.raw-json=invalid")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }
}
