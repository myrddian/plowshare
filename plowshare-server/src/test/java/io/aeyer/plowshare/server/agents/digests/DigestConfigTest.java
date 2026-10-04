package io.aeyer.plowshare.server.agents.digests;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * What actually stops the boot when {@code DigestConfig} is wired into a real context, as opposed
 * to what a unit test proves about the pure function it delegates to.
 *
 * <p>{@code LlmConfigTest} covers {@code LlmConfig.requireMemoryModelRetired} by calling it
 * directly with a hand-built {@code Map}, which proves the message but not that anything calls it.
 * This class proves the wiring: that setting {@code plowshare.memory.model} through a real property
 * source reaches the refusal during context refresh, before any bean this configuration declares —
 * {@code DigestStore}, {@code DigestModel}, {@code Digester}, {@code Navigator} — is ever created.
 * None of those beans' dependencies (a {@code JdbcTemplate}, an {@code Archive}, an {@code
 * LlmDispatcher}, an {@code EmbeddingClient}) are registered here, and none need to be: {@code
 * DigestConfig}'s own constructor is what Spring must instantiate before it can call any
 * {@code @Bean} method declared on it, so a constructor that throws stops refresh before those
 * dependencies would ever be looked up.
 */
class DigestConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(DigestConfig.class);

  private static String trail(Throwable failure) {
    StringBuilder text = new StringBuilder();
    Throwable current = failure;
    while (current != null) {
      text.append(current.getMessage()).append('\n');
      current = current.getCause();
    }
    return text.toString();
  }

  /**
   * The retired key, bound the way an operator's YAML binds it, stops the boot naming where it
   * moved to — not merely when {@code requireMemoryModelRetired} is called as a function, but when
   * {@code DigestConfig} is the thing Spring is asked to start.
   */
  @Test
  void a_bound_plowshare_memory_model_property_stops_the_boot_naming_its_replacement() {
    runner
        .withPropertyValues("plowshare.memory.model=fast")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("plowshare.memory.model"), message);
              assertTrue(message.contains("plowshare.llm.system-overrides.memory"), message);
            });
  }
}
