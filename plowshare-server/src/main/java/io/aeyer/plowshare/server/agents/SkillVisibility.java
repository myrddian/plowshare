package io.aeyer.plowshare.server.agents;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** A project's skill discovery policy; it never grants a skill or changes its source. */
final class SkillVisibility {
  private SkillVisibility() {}

  static Map<String, Boolean> parse(String source) {
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
    if (skills.size() > ChannelDefinitions.MAX_DEFINITIONS) {
      throw new IllegalArgumentException("too many skill visibility overrides");
    }
    Map<String, Boolean> result = new LinkedHashMap<>();
    skills.forEach(
        (key, value) -> {
          if (!(key instanceof String name)
              || !name.matches("[\\p{L}\\p{Nd}]+(?:-[\\p{L}\\p{Nd}]+)*")
              || !name.equals(name.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("invalid skill name in skills.yml");
          }
          if (!(value instanceof Map<?, ?> fields)
              || !fields.keySet().equals(Set.of("agentVisible"))
              || !(fields.get("agentVisible") instanceof Boolean visible)) {
            throw new IllegalArgumentException(
                "skill '" + name + "' needs only an agentVisible boolean");
          }
          result.put(name, visible);
        });
    return Map.copyOf(result);
  }
}
