package io.aeyer.plowshare.server.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** A project's skill discovery policy; it never grants a skill or changes its source. */
final class SkillVisibility {
  private SkillVisibility() {}

  static Map<String, Boolean> parse(String source) {
    Map<?, ?> skills = skills(source);
    List<String> problems = problems(skills);
    // Discovery keeps its concise exception contract; the write hook renders the full list.
    if (!problems.isEmpty()) throw new IllegalArgumentException(problems.getFirst());
    Map<String, Boolean> result = new LinkedHashMap<>();
    skills.forEach(
        (key, value) -> {
          if (key instanceof String name
              && value instanceof Map<?, ?> fields
              && fields.get("agentVisible") instanceof Boolean visible) {
            result.put(name, visible);
          } else {
            throw new IllegalStateException("validated skill visibility mapping changed");
          }
        });
    return Map.copyOf(result);
  }

  /** Independent policy-entry failures, ordered by skill name; malformed YAML still throws. */
  static List<String> problems(String source) {
    return problems(skills(source));
  }

  private static Map<?, ?> skills(String source) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(20);
    options.setCodePointLimit((int) ChannelDefinitions.MAX_DEFINITION_BYTES);
    Object loaded = new Yaml(new SafeConstructor(options)).load(source);
    if (!(loaded instanceof Map<?, ?> root)
        || !root.keySet().equals(Set.of("skills"))
        || !(root.get("skills") instanceof Map<?, ?> skills)) {
      throw new IllegalArgumentException("skills.yml must contain only a skills mapping");
    }
    return skills;
  }

  private static List<String> problems(Map<?, ?> skills) {
    List<String> problems = new ArrayList<>();
    if (skills.size() > ChannelDefinitions.MAX_DEFINITIONS) {
      problems.add("too many skill visibility overrides");
    }
    skills.entrySet().stream()
        .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
        .forEach(
            entry -> {
              Object key = entry.getKey();
              Object value = entry.getValue();
              if (!(key instanceof String name)
                  || !name.matches("[\\p{L}\\p{Nd}]+(?:-[\\p{L}\\p{Nd}]+)*")
                  || !name.equals(name.toLowerCase(java.util.Locale.ROOT))) {
                problems.add("invalid skill name in skills.yml");
              }
              if (!(value instanceof Map<?, ?> fields)
                  || !fields.keySet().equals(Set.of("agentVisible"))
                  || !(fields.get("agentVisible") instanceof Boolean visible)) {
                problems.add("skill '" + key + "' needs only an agentVisible boolean");
              }
            });
    return List.copyOf(problems);
  }
}
