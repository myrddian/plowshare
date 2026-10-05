package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.config.RuntimeConfig;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the corpus refuses to start with. No datasource, no endpoint.
 *
 * <p>{@code LlmConfigTest}'s shape, and for its reason: every case here is a configuration whose
 * only other report would arrive long after boot, in a place that cannot name the property that
 * caused it. A chunk target above the embedding ceiling does not fail visibly — it stores every
 * document and embeds none, and the only signal is an outcome saying a great many chunks are
 * unsearchable, on a job somebody has to go and read.
 */
class DocumentsConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(DocumentsConfig.class)
          .withBean(
              io.aeyer.plowshare.server.archive.ProjectMembers.class,
              () -> Mockito.mock(io.aeyer.plowshare.server.archive.ProjectMembers.class))
          .withBean(JdbcTemplate.class, () -> Mockito.mock(JdbcTemplate.class))
          .withBean(EmbeddingClient.class, () -> Mockito.mock(EmbeddingClient.class))
          .withBean(LlmProperties.class, DocumentsConfigTest::llm)
          .withBean(
              Tokenizer.class,
              () ->
                  new io.aeyer.plowshare.server.llm.tokens.FixtureTokenizer(
                      RatioTokenizer.DEFAULT_CHARACTERS_PER_TOKEN))
          .withBean(
              UnitOfWork.class,
              () ->
                  new UnitOfWork() {
                    @Override
                    public <T> T inTransaction(Supplier<T> work) {
                      return work.get();
                    }
                  });

  /**
   * The ceiling as the shipped {@code application.yml} sets it. Written out because an {@code
   * ApplicationContextRunner} loads no YAML, so without this every case below would fail on the
   * ceiling rather than on the property it is about.
   */
  private static LlmProperties llm() {
    LlmProperties props = new LlmProperties();
    props.setEmbeddingModel("nomic-embed-text");
    props.setEmbeddingDim(768);
    props.setEmbeddingMaxInputTokens(1536);
    props.setPools(List.of());
    return props;
  }

  private static String[] shipped(String... extra) {
    String[] base = {
      "plowshare.documents.chunk-target-tokens=400",
      "plowshare.documents.embed-batch-size=32",
      "plowshare.documents.ingest-budget=300",
      "plowshare.documents.span-size=12",
    };
    String[] all = new String[base.length + extra.length];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(extra, 0, all, base.length, extra.length);
    return all;
  }

  @Test
  void the_shipped_numbers_start() {
    runner
        .withPropertyValues(shipped())
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertNotNull(context.getBean(IngestService.class));
              assertNotNull(context.getBean(DocumentStore.class));
            });
  }

  /**
   * <b>One embedding client, so the corpus and the questions asked of it cannot be in two different
   * spaces.</b>
   *
   * <p>{@link RetrievalService}'s whole argument reduces to this: the vectors on disk were produced
   * by whatever {@code plowshare.llm.embedding-model} named at ingest, and a question turned into a
   * vector by any other model is not ranked badly, it is not ranked at all — the distances compute,
   * the rows sort, and nothing anywhere reports it.
   *
   * <p>Two halves hold that, and this is the second. The first is structural and needs no test:
   * {@code DocumentStore.searchByVector} is package-private, so no vector from outside {@code
   * documents} can reach the index. The second is that both services here are handed <em>the same
   * bean</em>, which is a fact about this context rather than about either class — and it stops
   * being true the moment a second {@link EmbeddingClient} is declared, which is a change nothing
   * else would notice.
   */
  @Test
  void the_corpus_and_the_questions_asked_of_it_go_through_one_embedding_client() {
    runner
        .withPropertyValues(shipped())
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertNotNull(context.getBean(RetrievalService.class));
              org.junit.jupiter.api.Assertions.assertArrayEquals(
                  new String[] {"embeddingClient"},
                  context.getBeanNamesForType(EmbeddingClient.class),
                  "a second embedding client is a corpus embedded by one model and"
                      + " questioned with another, and nothing downstream can tell"
                      + " that from a search that worked");
            });
  }

  /**
   * The two bounds are owned by two different prefixes and nothing but this holds them in step.
   *
   * <p>The target is this pipeline's and the ceiling is the model's — put in {@code plowshare.llm}
   * on purpose, because every caller of the embedding endpoint needs it and two spellings that had
   * to agree is the drift {@code LlmProperties} was written about. The price of splitting them is
   * this check.
   */
  @Test
  void a_chunk_target_above_the_embedding_ceiling_stops_the_boot() {
    runner
        .withPropertyValues(shipped("plowshare.documents.chunk-target-tokens=4096"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("chunk-target-tokens"), message);
              assertTrue(message.contains("embedding-max-input-tokens"), message);
            });
  }

  @Test
  void a_non_positive_chunk_target_stops_the_boot() {
    runner
        .withPropertyValues(shipped("plowshare.documents.chunk-target-tokens=0"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  trail(context.getStartupFailure()).contains("chunk-target-tokens"),
                  trail(context.getStartupFailure()));
            });
  }

  /**
   * A batch of zero is an ingest that stores every document and embeds nothing, which reads exactly
   * like an ingest that worked.
   */
  @Test
  void a_non_positive_embed_batch_size_stops_the_boot() {
    runner
        .withPropertyValues(shipped("plowshare.documents.embed-batch-size=0"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  trail(context.getStartupFailure()).contains("embed-batch-size"),
                  trail(context.getStartupFailure()));
            });
  }

  /**
   * An ingest allowance of zero is an ingest that labels nothing, which reads exactly like an
   * ingest that worked.
   *
   * <p>{@code embed-batch-size}'s case one derived thing over, and it matters more here: an
   * unembedded document is invisible to search, which somebody notices; an unlabelled one answers
   * every search and has nothing to say about itself, which nobody does.
   */
  @Test
  void a_non_positive_ingest_budget_stops_the_boot() {
    runner
        .withPropertyValues(shipped("plowshare.documents.ingest-budget=0"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  trail(context.getStartupFailure()).contains("ingest-budget"),
                  trail(context.getStartupFailure()));
            });
  }

  /**
   * <b>The boot check validates what an operator configured, not what a runtime write left in the
   * map.</b>
   *
   * <p>Two things make that the right reading, and the first is an ordering fact rather than a
   * preference. {@code RuntimeConfigSeed} is a {@code SmartInitializingSingleton}, so it writes the
   * map in {@code afterSingletonsInstantiated} — <em>after</em> this configuration has built {@code
   * IngestService} during {@code finishBeanFactoryInitialization}. A check on the live value
   * therefore reads the map at the one moment it is guaranteed not to have been reconciled with the
   * environment yet: an operator pins {@code PLOWSHARE_INGEST_BUDGET=2000}, a stale {@code 0} sits
   * in the map from months ago, and the boot is refused over a number nobody typed and the pin that
   * would have replaced it never gets to run.
   *
   * <p>The second is that checking the map here never bought the guarantee it resembled. Spec §1.1
   * already accepts that a live key is checked at boot and <em>not</em> on write, so a {@code PUT}
   * of zero one second after this check passes is legal either way. A check that reads the live
   * value gets no coverage for that and pays for it with the refusal above.
   *
   * <p>The two assertions after the startup one are what keep this from passing vacuously: they say
   * the map really is holding an unusable value and the accessor really is reading it, so the boot
   * that just succeeded succeeded because it asked the other question.
   */
  @Test
  void the_boot_check_reads_the_value_as_bound_and_not_as_written() {
    runner
        .withBean(RuntimeConfig.class, () -> mapHolding("0"))
        .withPropertyValues(shipped())
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              DocumentsProperties props = context.getBean(DocumentsProperties.class);
              org.junit.jupiter.api.Assertions.assertEquals(
                  0,
                  props.ingestBudgetNow(),
                  "the map is not being read through the live accessor at all, so this"
                      + " boot did not have to choose between two answers");
              org.junit.jupiter.api.Assertions.assertEquals(
                  300,
                  props.getIngestBudget(),
                  "the bound accessor is answering something other than what this"
                      + " context bound, so the two answers this boot chose between"
                      + " were not 0 and 300 and the choice it made is unpinned");
            });
  }

  /**
   * A map that answers one value for every key.
   *
   * <p>A stub rather than the real Postgres {@code DocumentsPropertiesTest} uses, and the
   * difference in what the two are about is why. That class asserts what an accessor does when a
   * store fails, so the exception has to be the one Spring's own translation produces. This asserts
   * <em>which accessor</em> a boot check calls, which is answered by a map holding a number this
   * configuration refuses — and standing a container up to hold one row would put a database inside
   * the class whose whole heading is "no datasource, no endpoint".
   */
  private static RuntimeConfig mapHolding(String value) {
    return new RuntimeConfig(Mockito.mock(JdbcTemplate.class)) {
      @Override
      public java.util.Optional<String> get(String key) {
        return java.util.Optional.of(value);
      }
    };
  }

  /**
   * The class carries no default for either key, so {@code application.yml} is the only place they
   * live — {@link LlmProperties}' rule, and the boot checks above are what turn "no Java default"
   * into a failure naming the key.
   */
  @Test
  void the_class_carries_no_default_for_the_keys_the_yaml_owns() {
    DocumentsProperties unbound = new DocumentsProperties();

    org.junit.jupiter.api.Assertions.assertEquals(0, unbound.getChunkTargetTokens());
    org.junit.jupiter.api.Assertions.assertEquals(0, unbound.getEmbedBatchSize());
    org.junit.jupiter.api.Assertions.assertEquals(0, unbound.getIngestBudget());
    org.junit.jupiter.api.Assertions.assertEquals(0, unbound.getSpanSize());
  }

  private static String trail(Throwable failure) {
    StringBuilder text = new StringBuilder();
    Throwable current = failure;
    while (current != null) {
      text.append(current.getMessage()).append('\n');
      current = current.getCause();
    }
    return text.toString();
  }
}
