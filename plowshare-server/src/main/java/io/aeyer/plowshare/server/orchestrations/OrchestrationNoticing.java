package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Trigger;
import io.aeyer.plowshare.server.agents.TriggerNoticing;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The keyword trap: an incoming utterance matching a trigger of an orchestration the speaking agent
 * is granted and can reach, and is not already running or asking from this conversation, is named —
 * never started. Spec §6: this only notices; the model may proceed without it.
 */
public final class OrchestrationNoticing implements TriggerNoticing {

  private static final String HEADER =
      "This request may suit an orchestration. You may start it, or carry on without it:";

  private final CallerOrchestrations callers;
  private final OrchestrationStore store;

  public OrchestrationNoticing(CallerOrchestrations callers, OrchestrationStore store) {
    this.callers = Objects.requireNonNull(callers, "callers");
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public Optional<String> noticeFor(
      AgentDefinition definition,
      String utterance,
      Home home,
      String sessionId,
      String conversation) {
    Map<String, OrchestrationDefinition> granted =
        callers.granted(definition, home, conversation, sessionId);
    Map<String, Trigger> matched = new LinkedHashMap<>();
    for (OrchestrationDefinition candidate : granted.values()) {
      TriggerMatch.first(candidate.triggers(), utterance)
          .ifPresent(trigger -> matched.put(candidate.name(), trigger));
    }
    if (matched.isEmpty()) {
      return Optional.empty();
    }
    if (conversation != null) {
      Set<String> live = store.liveDefinitionsFrom(conversation);
      matched.keySet().removeAll(live);
    }
    if (matched.isEmpty()) {
      return Optional.empty();
    }
    StringBuilder text = new StringBuilder(HEADER);
    matched.forEach(
        (name, trigger) -> {
          OrchestrationDefinition orchestration = granted.get(name);
          text.append("\n- orchestrate_")
              .append(name)
              .append(" (matched \"")
              .append(trigger.text())
              .append("\"; stages: ")
              .append(
                  orchestration.stages().stream()
                      .map(OrchestrationDefinition.Stage::id)
                      .collect(Collectors.joining(", ")))
              .append(")");
        });
    return Optional.of(text.toString());
  }
}
