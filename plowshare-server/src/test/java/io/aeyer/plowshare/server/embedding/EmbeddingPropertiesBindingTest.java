package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

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

  @Test
  void searchPolicyDefaultsToUnlimitedInBothEncoderModes() {
    for (var mode :
        new ApplicationContextRunner[] {
          runner,
          configured(),
          runner.withPropertyValues("plowshare.embeddings.search-budget=unlimited"),
          configured().withPropertyValues("plowshare.embeddings.search-budget=UNLIMITED")
        })
      mode.run(
          context -> {
            assertNull(context.getStartupFailure());
            assertNull(context.getBean(EmbeddingProperties.class).searchLimit());
          });
  }

  @Test
  void shippedDefaultAndExplicitEmptyOverrideBindUnlimited() throws java.io.IOException {
    var defaults =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"))
            .getFirst();
    for (var mode :
        new ApplicationContextRunner[] {
          runner, runner.withPropertyValues("PLOWSHARE_SEMANTIC_SEARCH_BUDGET=")
        })
      mode.withInitializer(
              context -> context.getEnvironment().getPropertySources().addLast(defaults))
          .run(
              context -> {
                if (context.getStartupFailure() != null)
                  throw new AssertionError(
                      "Shipped embedding configuration must bind", context.getStartupFailure());
                assertNull(context.getBean(EmbeddingProperties.class).searchLimit());
              });
    runner
        .withPropertyValues("plowshare.embeddings.search-budget=")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertNull(context.getBean(EmbeddingProperties.class).searchLimit());
            });
  }

  @Test
  void searchPolicyBindsDisabledAndLimitedAndRejectsInvalidLimits() {
    for (int limit : new int[] {0, 1, 50})
      runner
          .withPropertyValues(
              "plowshare.embeddings.enabled=false", "plowshare.embeddings.search-budget=" + limit)
          .run(
              context -> {
                assertNull(context.getStartupFailure());
                assertEquals(limit, context.getBean(EmbeddingProperties.class).searchLimit());
              });
    for (String invalid : new String[] {"-1", "unknown", "1.5", "2147483648"})
      runner
          .withPropertyValues("plowshare.embeddings.search-budget=" + invalid)
          .run(context -> assertNotNull(context.getStartupFailure()));
  }
}
