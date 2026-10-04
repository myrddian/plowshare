package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.todos.TodoBoard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the authority used by caller tools and WebSocket orchestration starts.
 *
 * <p>The full server requires this service. Ordinary dependency injection resolves its
 * collaborators after configuration discovery, so registration cannot depend on whether the
 * resolver's configuration was processed first. A missing collaborator fails startup rather than
 * leaving a healthy server that refuses every orchestration start.
 *
 * <p>This composition is separate from {@link OrchestrationsConfig}: narrow engine contexts can
 * still import that configuration without installing caller-facing services.
 */
@Configuration(proxyBeanMethods = false)
public class CallerOrchestrationsConfig {
  @Bean
  public CallerOrchestrations callerOrchestrations(
      OrchestrationResolver resolver,
      Callers callers,
      Orchestrations orchestrations,
      OrchestrationCancel cancel,
      OrchestrationStore store,
      TodoBoard board) {
    return new CallerOrchestrations(resolver, callers, orchestrations, cancel, store, board);
  }
}
