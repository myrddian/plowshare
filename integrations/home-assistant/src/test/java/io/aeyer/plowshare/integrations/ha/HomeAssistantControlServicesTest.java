package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HomeAssistantControlServicesTest {
  private static HomeAssistantSettings.Action profile(String service) {
    return new HomeAssistantSettings.Action(
        service, List.of("light.first"), Map.of(), List.of(), false);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"conversation.process", "ai_task.generate_data", "ai_task.generate_image"})
  void inference_services_cannot_become_named_controls_even_with_an_explicit_alias(String service) {
    // A fixed entity target does not identify the conversation agent: HA can use agent_id or a
    // default provider. Refuse the service rather than guessing whether it reaches our own entity.
    var invalid =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new HomeAssistantActionPolicy(
                    HomeAssistantActionToolsTest.BINDING, Map.of("loop", profile(service))));
    assertTrue(invalid.getMessage().contains("unsupported named controls"));
  }

  @Test
  void a_refused_policy_replacement_preserves_the_previous_generation_and_profile()
      throws Exception {
    var policy = HomeAssistantActionToolsTest.policy();
    long admitted = policy.admit(HomeAssistantActionToolsTest.ACTION);
    assertThrows(
        IllegalArgumentException.class,
        () -> policy.replace(Map.of("lighting", profile("conversation.process"))));
    policy.require(HomeAssistantActionToolsTest.ACTION, admitted);
    assertEquals(admitted, policy.admit(HomeAssistantActionToolsTest.ACTION));
  }

  @ParameterizedTest
  @ValueSource(strings = {"describe_action", "execute_action"})
  void a_read_discovery_profile_is_not_an_execution_permit_for_a_model_endpoint(String suffix)
      throws Exception {
    var discovery = new HomeAssistantActionToolsTest.Discovery();
    discovery.offered =
        new ConfiguredAction(
            "lighting", HomeAssistantActionToolsTest.REVISION, profile("conversation.process"));
    var executor = new HomeAssistantActionToolsTest.Executor();
    var tools =
        HomeAssistantActionToolsTest.tools(
            discovery, HomeAssistantActionToolsTest.policy(), executor);
    var input = HomeAssistantActionToolsTest.input();
    if (suffix.equals("describe_action"))
      input.keySet().retainAll(List.of("alias", "action", "revision"));
    assertThrows(
        IOException.class,
        () ->
            tools.execute(
                HomeAssistantActionToolsTest.call(tools, suffix, input),
                HomeAssistantActionToolsTest.budget()));
    assertEquals(0, executor.effects);
  }

  @Test
  void ordinary_device_control_and_the_explicit_manual_profile_contract_remain_available() {
    HomeAssistantControlServices.requireSupported(profile("light.turn_on"));
    // The legacy profile value itself is unchanged. A private manual alias is explicit operator
    // composition, not a model-facing device control or an automatic inference destination.
    assertEquals("conversation.process", profile("conversation.process").service());
  }
}
