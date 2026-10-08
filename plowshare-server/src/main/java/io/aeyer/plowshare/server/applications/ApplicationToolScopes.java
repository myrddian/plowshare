package io.aeyer.plowshare.server.applications;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.relay.tools.RelayToolDefinition;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Explicit owner policy, independent of the provider's discovered catalogue. */
public record ApplicationToolScopes(
    String executionAccount, List<Scope> toolScopes, List<Assignment> toolGrants) {
  public static final ApplicationToolScopes EMPTY =
      new ApplicationToolScopes(null, List.of(), List.of());
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(
              DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public ApplicationToolScopes {
    if (executionAccount != null) RelayPort.identity(executionAccount);
    toolScopes = List.copyOf(toolScopes);
    toolGrants = List.copyOf(toolGrants);
    if (!toolScopes.isEmpty() && executionAccount == null)
      throw new IllegalArgumentException("Application tool scopes require an executionAccount");
    for (Assignment assignment : toolGrants)
      if (toolScopes.stream().noneMatch(scope -> scope.scope().equals(assignment.toolScope())))
        throw new IllegalArgumentException("Unknown application tool scope");
    if (toolScopes.size() > 32
        || toolGrants.size() > 256
        || toolScopes.stream().map(Scope::scope).distinct().count() != toolScopes.size()
        || new HashSet<>(toolGrants).size() != toolGrants.size())
      throw new IllegalArgumentException("Invalid application tool scope assignments");
  }

  public record Scope(String scope, String provider, List<String> grants) {
    public Scope {
      identifier(scope);
      new RelayToolDefinition(
          "validation", provider, "validation", "validation", "validation", List.of(), 30);
      grants = ApplicationToolScopes.grants(grants);
    }

    public boolean permits(String tool) {
      return grants.contains("*") || grants.contains(tool);
    }
  }

  public record Assignment(String toolScope, String agent) {
    public Assignment {
      identifier(toolScope);
      identifier(agent);
    }
  }

  public boolean permits(String account, String agent, String provider, String tool) {
    if (executionAccount != null && !executionAccount.equals(account)) return false;
    return toolScopes.stream()
        .anyMatch(
            s ->
                s.provider().equals(provider)
                    && s.permits(tool)
                    && toolGrants.contains(new Assignment(s.scope(), agent)));
  }

  public static void identifier(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9]*(?:[_-][a-z0-9]+)*") || value.length() > 64)
      throw new IllegalArgumentException("Invalid tool scope or agent identifier");
  }

  public static List<String> grants(List<String> values) {
    if (values == null
        || values.isEmpty()
        || values.size() > 128
        || new HashSet<>(values).size() != values.size())
      throw new IllegalArgumentException("Tool scope needs unique bounded grants");
    for (String value : values)
      if (!"*".equals(value)
          && (value == null
              || !value.matches("[a-z][a-z0-9]*(?:_[a-z0-9]+)*")
              || value.length() > 64))
        throw new IllegalArgumentException("Invalid tool scope grant");
    if (values.contains("*") && values.size() != 1)
      throw new IllegalArgumentException("Wildcard must be the only scope grant");
    return List.copyOf(values);
  }

  /** Parse only this policy slice; the owning manifest parser validates the other fields. */
  public static ApplicationToolScopes parse(String text) throws IOException {
    JsonNode root = JSON.readTree(text);
    if (root == null || !root.isObject())
      throw new IllegalArgumentException("Invalid application manifest");
    String account = null;
    if (root.has("executionAccount")) {
      if (!root.get("executionAccount").isTextual())
        throw new IllegalArgumentException("Invalid execution account");
      account = root.get("executionAccount").textValue();
    }
    List<Scope> scopes = readList(root, "toolScopes", Scope.class);
    List<Assignment> assignments = readList(root, "toolGrants", Assignment.class);
    return new ApplicationToolScopes(account, scopes, assignments);
  }

  private static <T> List<T> readList(JsonNode root, String field, Class<T> type)
      throws IOException {
    if (!root.has(field)) return List.of();
    JsonNode value = root.get(field);
    if (!value.isArray() || value.size() > 256)
      throw new IllegalArgumentException("Invalid " + field);
    List<T> result = new ArrayList<>();
    for (JsonNode entry : value) result.add(JSON.treeToValue(entry, type));
    return List.copyOf(result);
  }

  public static ApplicationToolScopes read(Path root) {
    Path file = root.resolve("plowshare.json");
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return EMPTY;
    try {
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Invalid application manifest");
      try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = input.readNBytes(65537);
        if (bytes.length > 65536) throw new IOException("Application manifest exceeds 64 KiB");
        return parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      }
    } catch (IOException | IllegalArgumentException invalid) {
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Invalid application tool scope policy");
    }
  }
}
