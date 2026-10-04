package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * What the ask refuses to start with.
 *
 * <p>{@code DocumentsConfigTest}'s shape and its reason: a refusal nothing calls is a check nobody
 * runs, and this one's only other report would arrive at the first question somebody asked — as a
 * pass that ended at {@code CALL_BUDGET} with no answer, on a job they would have to go and read.
 *
 * <p><b>Its own class rather than a case in {@code DocumentsConfigTest}</b>, because {@link
 * DeliberationConfig} is its own configuration for {@code SummariserConfig}'s reason: it spans two
 * packages, so a context built for it needs the agent runtime and a context built for {@link
 * DocumentsConfig} must not.
 */
class DeliberationConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(DeliberationConfig.class)
          .withBean(DocumentStore.class, () -> Mockito.mock(DocumentStore.class))
          .withBean(RetrievalService.class, () -> Mockito.mock(RetrievalService.class))
          .withBean(CitationStore.class, () -> Mockito.mock(CitationStore.class))
          .withBean(JobRuntime.class, () -> Mockito.mock(JobRuntime.class))
          .withBean(Compaction.class, () -> Mockito.mock(Compaction.class));

  @Test
  void the_shipped_allowance_starts() {
    runner
        .withPropertyValues("plowshare.documents.ask-budget=5")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertNotNull(context.getBean(Deliberation.class));
            });
  }

  /**
   * <b>An allowance below one pass's worst case is refused at boot, naming the key.</b>
   *
   * <p>Three is the tempting number and it is the wrong one: a pass is three stages <em>plus</em>
   * one retry of a critic whose JSON did not parse, and that retry is the ordinary failure it
   * exists for rather than an exceptional one. An allowance of three would produce a deliberation
   * that reports {@code CALL_BUDGET} the first time a small model answers a JSON question in prose
   * — which is a configuration mistake presenting as an intermittent one.
   */
  @Test
  void an_allowance_that_cannot_cover_a_pass_is_refused_and_names_the_key() {
    runner
        .withPropertyValues("plowshare.documents.ask-budget=4")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String said =
                  context.getStartupFailure().getMessage() + causes(context.getStartupFailure());
              assertTrue(said.contains("plowshare.documents.ask-budget"), said);
              assertTrue(said.contains("retry"), said);
            });
  }

  /**
   * Zero is the same refusal and not a different one: a pass that may make no model call is not a
   * smaller pass.
   */
  @Test
  void an_allowance_of_none_is_refused_too() {
    runner
        .withPropertyValues("plowshare.documents.ask-budget=0")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  /**
   * The registry arrives as a supplier, so a server with no agent directory still builds the bean —
   * the degraded state {@link Deliberation#ask} says out loud rather than a boot that refuses to
   * start.
   */
  @Test
  void a_server_with_no_agent_registry_still_builds_the_ask() {
    runner
        .withPropertyValues("plowshare.documents.ask-budget=5")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(
                  context.getBeanNamesForType(AgentRegistry.class).length == 0,
                  "this context deliberately has no registry");
              assertNotNull(context.getBean(Deliberation.class));
            });
  }

  private static String causes(Throwable failure) {
    StringBuilder said = new StringBuilder();
    for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
      said.append(' ').append(cause.getMessage());
    }
    return said.toString();
  }
}
