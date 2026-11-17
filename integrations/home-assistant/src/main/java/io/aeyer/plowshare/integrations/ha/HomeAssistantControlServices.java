package io.aeyer.plowshare.integrations.ha;

import java.util.Set;

/** Capability ceiling for named device controls, independent of HA credentials and tool grants. */
final class HomeAssistantControlServices {
  // These HA service names can re-enter this package's own model-backed entities. The current
  // device profile does not carry a verified destination identity or bounded invocation ancestry,
  // so even an explicitly configured profile cannot make them named device controls. Legacy
  // operator-authored manual aliases retain their separate configuration contract.
  private static final Set<String> INFERENCE_SERVICES =
      Set.of("conversation.process", "ai_task.generate_data", "ai_task.generate_image");

  private HomeAssistantControlServices() {}

  static void requireSupported(HomeAssistantSettings.Action profile) {
    if (INFERENCE_SERVICES.contains(profile.service()))
      throw new IllegalArgumentException(
          "HA conversation and AI Task inference services are unsupported named controls;"
              + " verified destination identity and recursion policy are required");
  }
}
