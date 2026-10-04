package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.server.archive.ClientProjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Recognized project settings in the existing version 1 identity manifest. */
public record ProjectConfiguration(String source, JsonNode body) {
  static final int MAX_BYTES = 65536;
  static final List<String> FILES =
      List.of(".plowshare/plowshare", ".plowshare/project", "plowshare");
  public static final ProjectConfiguration NONE = new ProjectConfiguration("definition", null);
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static ProjectConfiguration parse(String text, String project, String source) {
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("Project manifest exceeds 64 KiB");
    if (!text.stripLeading().startsWith("{")) {
      if (text.stripLeading().startsWith("["))
        throw new IllegalArgumentException("Project manifest must be an object");
      return NONE;
    }
    try {
      JsonNode body = JSON.readTree(text);
      String name = body.path("name").asText();
      if (!body.path("version").isIntegralNumber()
          || !body.path("version").canConvertToInt()
          || body.path("version").intValue() != 1
          || !body.path("name").isTextual()
          || name.isBlank()
          || !name.equals(name.strip())
          || name.length() > 512
          || name.matches("(?s).*[\\r\\n\\x00].*")
          || project != null && !name.equals(ClientProjects.label(project)))
        throw new IllegalArgumentException("Invalid project manifest identity or version");
      var config = new ProjectConfiguration(source, body);
      config.caps();
      config.commands();
      config.skills();
      config.defaultBot();
      return config;
    } catch (IOException malformed) {
      throw new IllegalArgumentException("Invalid JSON project manifest", malformed);
    }
  }

  public ProjectCaps caps() {
    return body == null ? ProjectCaps.NONE : ManifestCaps.parse(body, source);
  }

  public EnvironmentFile.Parsed commands() {
    JsonNode commands = body == null ? null : body.get("commands");
    if (commands == null) return EnvironmentFile.Parsed.EMPTY;
    if (!commands.isObject())
      throw new IllegalArgumentException("Project commands must be an object");
    commands
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!key.equals("local") && !key.equals("server"))
                throw new IllegalArgumentException("Unknown command side: " + key);
            });
    return EnvironmentFile.commands(
        JSON.convertValue(
            commands, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
  }

  public Map<String, Boolean> skills() {
    JsonNode skills = body == null ? null : body.get("skills");
    return skills == null ? Map.of() : SkillVisibility.parse("{\"skills\":" + skills + "}");
  }

  public Optional<DefinitionResolver.DefaultBot> defaultBot() {
    JsonNode bot = body == null ? null : body.get("defaultBot");
    if (bot == null) return Optional.empty();
    if (!bot.isTextual() || !bot.textValue().matches("[\\p{L}\\p{Nd}]+(?:-[\\p{L}\\p{Nd}]+)*"))
      throw new IllegalArgumentException("Invalid defaultBot");
    return Optional.of(new DefinitionResolver.DefaultBot(bot.textValue(), source));
  }

  static ProjectConfiguration local(ChannelDefinitions definitions, String project) {
    for (String file : FILES) {
      var read = definitions.resource(file);
      if (read.unreadable() != null)
        throw new IllegalArgumentException(file + ": " + read.unreadable());
      if (read.absent()
          || read.text().isBlank()
          || file.equals("plowshare") && read.text().startsWith("#!")) continue;
      return parse(read.text(), project, file);
    }
    return NONE;
  }

  static ProjectConfiguration server(Path workspace, List<Path> exclusions, String project) {
    if (workspace == null) return NONE;
    try {
      Path root = workspace.toRealPath();
      for (String file : FILES) {
        Path target = root.resolve(file), at = root;
        for (Path segment : root.relativize(target)) {
          at = at.resolve(segment);
          if (Files.isSymbolicLink(at))
            throw new IOException("Project manifest must not use symbolic links");
        }
        if (!FileAccess.of(List.of(target), exclusions).permits(target))
          throw new IOException("Project manifest is excluded from this workspace");
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) continue;
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("Project manifest must be a regular file");
        try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
          byte[] bytes = input.readNBytes(MAX_BYTES + 1);
          if (bytes.length > MAX_BYTES) throw new IOException("Project manifest exceeds 64 KiB");
          String text = new String(bytes, StandardCharsets.UTF_8);
          if (text.isBlank() || file.equals("plowshare") && text.startsWith("#!")) continue;
          return parse(text, project, "the server's " + file);
        } catch (NoSuchFileException absent) {
          /* Next identity location. */
        }
      }
      return NONE;
    } catch (IOException invalid) {
      throw new IllegalArgumentException(
          "The project manifest could not be read: " + invalid.getMessage(), invalid);
    }
  }
}
