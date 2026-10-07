package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.harness.Harness;
import io.aeyer.plowshare.server.harness.HarnessConfiguration;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class AgentGuidanceTest {
  private static Harness harness(Map<String, String> assignments, String fallback) {
    return new Harness(
        new HarnessConfiguration(
            Map.of("minimal", List.of(), "standard", List.of(), "guided", List.of()),
            assignments,
            fallback));
  }

  private static AgentGuidance guidance(LlmDispatcher dispatcher, Harness harness) {
    var beans = new StaticListableBeanFactory(Map.of("harness", harness));
    return new AgentsConfig().agentGuidance(dispatcher, beans.getBeanProvider(Harness.class));
  }

  @Test
  void uses_the_effective_profile_of_the_resolved_model_not_the_class_name() {
    var dispatcher = mock(LlmDispatcher.class);
    when(dispatcher.wireModelsFor("reasoning")).thenReturn(Set.of("large-model"));
    var guidance = guidance(dispatcher, harness(Map.of("large-model", "minimal"), "standard"));
    assertEquals("minimal", guidance.profileFor("reasoning"));
  }

  @Test
  void uses_the_harness_default_when_the_model_has_no_assignment() {
    var dispatcher = mock(LlmDispatcher.class);
    when(dispatcher.wireModelsFor("reasoning")).thenReturn(Set.of("model"));
    assertEquals(
        "guided", guidance(dispatcher, harness(Map.of(), "guided")).profileFor("reasoning"));
    assertNull(guidance(dispatcher, harness(Map.of(), null)).profileFor("reasoning"));
  }

  @Test
  void pools_may_share_a_profile_but_a_mixed_binding_is_refused() {
    var dispatcher = mock(LlmDispatcher.class);
    when(dispatcher.wireModelsFor("reasoning")).thenReturn(Set.of("large", "small"));
    assertEquals(
        "minimal",
        guidance(dispatcher, harness(Map.of("large", "minimal", "small", "minimal"), null))
            .profileFor("reasoning"));
    assertThrows(
        IllegalStateException.class,
        () ->
            guidance(dispatcher, harness(Map.of("large", "minimal", "small", "guided"), null))
                .profileFor("reasoning"));
    assertThrows(
        IllegalStateException.class,
        () ->
            guidance(dispatcher, harness(Map.of("large", "minimal"), null))
                .profileFor("reasoning"));
  }
}
