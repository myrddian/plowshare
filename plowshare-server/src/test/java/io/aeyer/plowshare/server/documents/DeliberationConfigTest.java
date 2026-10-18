package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
          .withBean(Compaction.class, () -> Mockito.mock(Compaction.class))
          .withBean(
              io.aeyer.plowshare.server.access.SystemOperations.class,
              () -> Mockito.mock(io.aeyer.plowshare.server.access.SystemOperations.class));

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

  /** Small finite policies are valid choices, even when a full pass needs more. */
  @Test
  void explicitSmallPolicyIsValidAndEnforcedByTheOwningOperation() {
    runner
        .withPropertyValues("plowshare.documents.ask-budget=4")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(4, context.getBean(DocumentsProperties.class).askAllowance().limit());
            });
  }

  /** Zero disables spending without preventing server startup. */
  @Test
  void zeroPolicyStartsWithSpendingDisabled() {
    runner
        .withPropertyValues("plowshare.documents.ask-budget=0")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(context.getBean(DocumentsProperties.class).askAllowance().exhausted());
            });
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

  @Test
  void defaultIsUnlimitedAndInvalidPolicyRefusesBoot() {
    runner.run(
        context -> {
          assertNull(context.getStartupFailure());
          assertFalse(context.getBean(DocumentsProperties.class).askAllowance().capped());
        });
    runner
        .withPropertyValues("plowshare.documents.ask-budget=-1")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  private static String causes(Throwable failure) {
    StringBuilder said = new StringBuilder();
    for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
      said.append(' ').append(cause.getMessage());
    }
    return said.toString();
  }
}
