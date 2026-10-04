package io.aeyer.plowshare.server.agents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** A portable package plus scoped execution and model-discovery metadata. */
public record SkillDefinition(
    String name,
    String description,
    String instructions,
    String agent,
    Mode mode,
    List<String> allowedTools,
    Map<String, String> metadata,
    String origin,
    OrchestrationDefinition.Tier tier,
    String hash,
    String source,
    boolean agentSpecified,
    boolean agentVisible) {
  public enum Mode {
    INHERITED,
    SUMMARISED,
    NEW,
    DIRECT
  }

  private static final Set<String> KEYS =
      Set.of(
          "name",
          "description",
          "license",
          "compatibility",
          "metadata",
          "allowed-tools",
          "agent",
          "mode",
          "agentVisible");

  public SkillDefinition {
    allowedTools = List.copyOf(allowedTools);
    metadata = Map.copyOf(metadata);
  }

  /** Mode may be absent: the deployment's delegated context default is a separate decision. */
  public static SkillDefinition parse(
      DefinitionSource.Definition file, OrchestrationDefinition.Tier tier) {
    String source = file.text();
    String normalized = source.replace("\r\n", "\n");
    if (normalized.startsWith("\ufeff")) normalized = normalized.substring(1);
    if (!normalized.startsWith("---\n")) throw bad("SKILL.md needs YAML frontmatter");
    int close = normalized.indexOf("\n---\n", 4);
    if (close < 0 && normalized.endsWith("\n---")) close = normalized.length() - 4;
    if (close < 0) throw bad("SKILL.md has no closing frontmatter fence");
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(20);
    options.setCodePointLimit((int) ChannelDefinitions.MAX_DEFINITION_BYTES);
    Object loaded = new Yaml(new SafeConstructor(options)).load(normalized.substring(4, close));
    if (!(loaded instanceof Map<?, ?> keys)) throw bad("skill frontmatter must be a mapping");
    for (Object key : keys.keySet()) {
      if (!KEYS.contains(key)) throw bad("unsupported skill field '" + key + "'");
    }
    String name = text(keys, "name", true, 64);
    if (!name.matches("[\\p{L}\\p{Nd}]+(?:-[\\p{L}\\p{Nd}]+)*")
        || !name.equals(name.toLowerCase(java.util.Locale.ROOT))
        || !name.equals(file.name())) {
      throw bad(
          "skill name must match its directory and use lowercase letters, digits and single hyphens");
    }
    String description = text(keys, "description", true, 1024);
    text(keys, "license", false, Integer.MAX_VALUE);
    text(keys, "compatibility", false, 500);
    Map<String, String> metadata = new LinkedHashMap<>();
    if (keys.containsKey("metadata")) {
      if (!(keys.get("metadata") instanceof Map<?, ?> map))
        throw bad("metadata must be a string mapping");
      map.forEach(
          (k, v) -> {
            if (!(k instanceof String key) || !(v instanceof String value)) {
              throw bad("metadata keys and values must be strings");
            }
            metadata.put(key, value);
          });
    }
    String agent = text(keys, "agent", false, 128);
    String requestedMode = text(keys, "mode", false, 32);
    Mode mode = requestedMode == null ? null : Mode.valueOf(requestedMode);
    if (mode == Mode.DIRECT && agent != null) throw bad("DIRECT cannot select another agent");
    String allowed = text(keys, "allowed-tools", false, Integer.MAX_VALUE);
    List<String> tools = allowed == null ? List.of() : List.of(allowed.strip().split("\\s+"));
    String body = normalized.substring(Math.min(close + 5, normalized.length())).strip();
    if (body.isEmpty()) throw bad("skill instructions must not be empty");
    return new SkillDefinition(
        name,
        description,
        body,
        agent == null ? "interlocutor" : agent,
        mode,
        tools,
        metadata,
        file.origin(),
        tier,
        hash(source),
        source,
        keys.containsKey("agent"),
        visibility(keys));
  }

  private static boolean visibility(Map<?, ?> keys) {
    if (!keys.containsKey("agentVisible")) return false;
    if (!(keys.get("agentVisible") instanceof Boolean value))
      throw bad("'agentVisible' must be a boolean");
    return value;
  }

  /** Visibility is project policy; it does not replace the pinned package's identity. */
  public SkillDefinition withAgentVisible(boolean visible) {
    return new SkillDefinition(
        name,
        description,
        instructions,
        agent,
        mode,
        allowedTools,
        metadata,
        origin,
        tier,
        hash,
        source,
        agentSpecified,
        visible);
  }

  private static String text(Map<?, ?> keys, String key, boolean required, int max) {
    if (!keys.containsKey(key)) {
      if (required) throw bad("skill needs '" + key + "'");
      return null;
    }
    if (!(keys.get(key) instanceof String value)
        || value.isBlank()
        || value.codePointCount(0, value.length()) > max) {
      throw bad("'" + key + "' must be non-empty text of at most " + max + " characters");
    }
    return value;
  }

  private static IllegalArgumentException bad(String message) {
    return new IllegalArgumentException(message);
  }

  private static String hash(String text) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
