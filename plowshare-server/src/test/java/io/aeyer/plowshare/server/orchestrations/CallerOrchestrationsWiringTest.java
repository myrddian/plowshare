package io.aeyer.plowshare.server.orchestrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.todos.TodoBoard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/** Caller authority must be registered regardless of configuration discovery order. */
class CallerOrchestrationsWiringTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void caller_authority_is_registered_in_either_configuration_order(boolean resolverFirst) {
    try (var context = new AnnotationConfigApplicationContext()) {
      if (resolverFirst) context.register(ResolverConfiguration.class);
      var scanner = new ClassPathBeanDefinitionScanner(context);
      scanner.addExcludeFilter(new AnnotationTypeFilter(TestConfiguration.class));
      scanner.scan("io.aeyer.plowshare.server.orchestrations");
      if (!resolverFirst) context.register(ResolverConfiguration.class);

      // Inspect production registrations before singleton creation. This exercises the
      // configuration-order condition without replacing the engine with database mocks.
      var factory = context.getDefaultListableBeanFactory();
      new ConfigurationClassPostProcessor().postProcessBeanDefinitionRegistry(factory);
      assertTrue(factory.containsBeanDefinition("orchestrationResolver"));
      assertTrue(
          factory.containsBeanDefinition("callerOrchestrations"),
          "A registered resolver must not leave orchestration starts unavailable");
    }
  }

  @Test
  void a_missing_resolver_fails_startup_instead_of_disabling_caller_authority() {
    new ApplicationContextRunner()
        .withUserConfiguration(CallerOrchestrationsConfig.class)
        .withBean(Callers.class, () -> mock(Callers.class))
        .withBean(Orchestrations.class, () -> mock(Orchestrations.class))
        .withBean(OrchestrationCancel.class, () -> mock(OrchestrationCancel.class))
        .withBean(OrchestrationStore.class, () -> mock(OrchestrationStore.class))
        .withBean(TodoBoard.class, () -> mock(TodoBoard.class))
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
                    .hasStackTraceContaining(OrchestrationResolver.class.getName()));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ResolverConfiguration {
    @Bean
    OrchestrationResolver orchestrationResolver() {
      return mock(OrchestrationResolver.class);
    }
  }
}
