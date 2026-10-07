package io.aeyer.plowshare.server.agents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Pure selection and graph expansion for named agent variants; never grants authority. */
final class AgentAliases {
  /** The authored guidance levels, ordered explicitly from least to most. */
  static final List<String> GUIDANCE = List.of("minimal", "standard", "guided");

  private AgentAliases() {}

  static void validateSelection(String name, String alias, String guidance, boolean bot) {
    if (alias == null) {
      if (guidance != null) throw new IllegalArgumentException("guidance requires an alias");
      return;
    }
    if (!alias.matches("[A-Za-z0-9_-]{1,128}")) {
      throw new IllegalArgumentException(
          "alias must be a name of 1 to 128 letters, digits, underscores or hyphens");
    }
    if (bot)
      throw new IllegalArgumentException(
          "bots have durable identities and cannot declare an alias");
    if (guidance != null && !GUIDANCE.contains(guidance)) {
      throw new IllegalArgumentException("guidance must be one of " + GUIDANCE);
    }
    if (alias.equals(name) && guidance != null) {
      throw new IllegalArgumentException(
          "an agent bearing its alias's name must be the unqualified default");
    }
  }

  static Map<String, List<AgentDefinition>> families(Map<String, AgentDefinition> definitions) {
    Map<String, List<AgentDefinition>> grouped = new TreeMap<>();
    definitions.values().stream()
        .sorted(Comparator.comparing(AgentDefinition::name))
        .filter(definition -> definition.alias() != null)
        .forEach(
            definition ->
                grouped
                    .computeIfAbsent(definition.alias(), ignored -> new ArrayList<>())
                    .add(definition));
    grouped.replaceAll((name, candidates) -> List.copyOf(candidates));
    return Map.copyOf(grouped);
  }

  /** A grant to an alias reaches every variant, regardless of today's model assignment. */
  static List<AgentDefinition> targets(Map<String, AgentDefinition> definitions, String name) {
    List<AgentDefinition> family = families(definitions).get(name);
    if (family != null) return family;
    AgentDefinition exact = definitions.get(name);
    return exact == null ? List.of() : List.of(exact);
  }

  /** Invalid families are disabled together, so ambiguity cannot become an accidental fallback. */
  static Map<String, String> faults(Map<String, AgentDefinition> definitions) {
    Map<String, String> faults = new LinkedHashMap<>();
    families(definitions)
        .forEach(
            (alias, candidates) -> {
              String reason = null;
              AgentDefinition collision = definitions.get(alias);
              if (collision != null && !alias.equals(collision.alias())) {
                reason =
                    "alias '"
                        + alias
                        + "' collides with a concrete agent that does not declare that alias";
              }
              Set<String> levels = new java.util.HashSet<>();
              String model = candidates.getFirst().model();
              for (AgentDefinition candidate : candidates) {
                if (!model.equals(candidate.model())) {
                  reason =
                      "alias '"
                          + alias
                          + "' requires one model binding across its variants; resolve the model before selecting guidance";
                }
                if (!levels.add(candidate.guidance())) {
                  reason =
                      "alias '"
                          + alias
                          + "' has multiple variants for guidance '"
                          + candidate.guidance()
                          + "'";
                }
              }
              if (reason != null) {
                String message =
                    reason + ": " + candidates.stream().map(AgentDefinition::name).toList();
                for (AgentDefinition candidate : candidates) faults.put(candidate.name(), message);
              }
            });
    return Map.copyOf(faults);
  }

  /** Exact profile, then unqualified default, then the least authored guidance level. */
  static AgentDefinition select(List<AgentDefinition> candidates, String profile) {
    for (AgentDefinition candidate : candidates) {
      if (Objects.equals(candidate.guidance(), profile)) return candidate;
    }
    for (AgentDefinition candidate : candidates) {
      if (candidate.guidance() == null) return candidate;
    }
    return candidates.stream()
        .min(Comparator.comparingInt(candidate -> GUIDANCE.indexOf(candidate.guidance())))
        .orElseThrow();
  }
}
