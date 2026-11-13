package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class RelayPublishingConfigurationTest {
  @Test
  void a_grant_in_the_other_definition_registry_cannot_authorize_publication() {
    var agents = mock(DefinitionResolver.class);
    var orchestrations = mock(OrchestrationResolver.class);
    var registry = mock(AgentRegistry.class);
    var agent = mock(AgentDefinition.class);
    var script = mock(AgentDefinition.class);
    var orchestration = mock(OrchestrationDefinition.class);
    var members = mock(ProjectMembers.class);
    var projects = mock(ProjectWorkspaces.class);
    var repository = mock(RelayPublishingRepository.class);
    var owner =
        UsageAttribution.project("alice", "7", UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root("conversation"),
                UsageLineage.root("run"),
                UsageLineage.NONE,
                "writer",
                1L,
                1L);
    var caller = new DefinitionResolver.Caller(7L, "session", "alice");
    when(agents.forCaller(caller)).thenReturn(registry);
    when(registry.findConcrete("writer")).thenReturn(Optional.of(agent));
    when(agent.tools()).thenReturn(List.of());
    when(orchestrations.forCaller(caller)).thenReturn(Map.of("writer", orchestration));
    when(orchestration.conductor()).thenReturn(script);
    when(script.tools()).thenReturn(List.of("relay_publish"));
    when(members.mayWork("research", "alice")).thenReturn(true);
    when(projects.personalOwner("research")).thenReturn(Optional.empty());
    when(projects.id("research")).thenReturn(7L);
    when(repository.ancestry(7, owner)).thenReturn(Optional.of(RelayCausation.root("root")));
    var receipt =
        new RelayPublishing.Receipt(
            UUID.randomUUID(), "input", "1", Instant.parse("2026-11-13T00:00:00Z"));
    when(repository.publish(any())).thenReturn(receipt);
    var grants = new RelayPublishingProperties();
    grants.setGrants(List.of(new RelayPublishingProperties.Grant("research", "writer", "input")));
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("agents", agents);
    beans.registerSingleton("orchestrations", orchestrations);
    var publishing =
        new RelayPublishingConfiguration()
            .relayPublishing(
                repository,
                grants,
                beans.getBeanProvider(DefinitionResolver.class),
                beans.getBeanProvider(OrchestrationResolver.class),
                members,
                projects,
                new RelayProperties());
    var agentCall =
        new RelayPublishing.Execution(
            owner, "session", "call", RelayPublishing.DefinitionKind.AGENT);
    var scriptCall =
        new RelayPublishing.Execution(
            owner, "session", "call", RelayPublishing.DefinitionKind.ORCHESTRATION);
    var request = new RelayPublishing.Request("input", "text");
    assertThrows(CallerFault.class, () -> publishing.publish("research", agentCall, request));
    verifyNoInteractions(repository);
    assertEquals(receipt, publishing.publish("research", scriptCall, request));
    when(script.tools()).thenReturn(List.of());
    when(agent.tools()).thenReturn(List.of("relay_publish"));
    assertThrows(CallerFault.class, () -> publishing.publish("research", scriptCall, request));
    assertEquals(receipt, publishing.publish("research", agentCall, request));
  }
}
