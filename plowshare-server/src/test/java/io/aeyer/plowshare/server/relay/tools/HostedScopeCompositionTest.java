package io.aeyer.plowshare.server.relay.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.ApplicationResources;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.providers.ProviderCredentialGrants;
import io.aeyer.plowshare.server.relay.*;
import io.aeyer.plowshare.server.session.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Eager composition must not resolve the definition/registry/invocation cycle. */
class HostedScopeCompositionTest {
  @Test
  void composite_registry_and_worker_authority_share_the_existing_ledger_without_a_bean_cycle() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(ApplicationResources.class, () -> mock(ApplicationResources.class));
      context.registerBean(ProjectWorkspaces.class, () -> mock(ProjectWorkspaces.class));
      context.registerBean(ProjectMembers.class, () -> mock(ProjectMembers.class));
      context.registerBean(ProjectNames.class, () -> mock(ProjectNames.class));
      context.registerBean(AdminStore.class, () -> mock(AdminStore.class));
      context.registerBean(
          ProviderCredentialGrants.class, () -> mock(ProviderCredentialGrants.class));
      context.registerBean(RelayLogRepository.class, () -> mock(RelayLogRepository.class));
      context.registerBean(RelayPortRepository.class, () -> mock(RelayPortRepository.class));
      context.registerBean(Relay.class, () -> mock(Relay.class));
      context.registerBean(SessionRegistry.class, () -> mock(SessionRegistry.class));
      context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
      context.registerBean(UnitOfWork.class, () -> UnitOfWork.NONE);
      context.registerBean(RelayProperties.class, RelayProperties::new);
      context.registerBean(
          com.fasterxml.jackson.databind.ObjectMapper.class,
          () -> new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
      context.register(RelayToolConfiguration.class);
      context.refresh();
      assertInstanceOf(
          CombinedToolScopeConnections.class, context.getBean(ToolScopeConnections.class));
      assertInstanceOf(OwnedHostedToolScopes.class, context.getBean(HostedToolScopes.class));
      assertInstanceOf(JdbcRelayToolRepository.class, context.getBean(RelayToolRepository.class));
      assertNotNull(context.getBean(ApplicationToolRegistry.class));
    }
  }
}
