package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.server.archive.ClientProjects;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * File configuration boundary. Legacy Externals retain membership; managed and adopted Applications
 * require valid manifests and explicit grants.
 */
@Component
public final class WorkspaceApplicationPolicy implements ApplicationPolicy {
  public static final String FILE = "plowshare.json";
  private static final Boundary EXTERNAL = new Boundary(Kind.EXTERNAL, Map.of());
  private static final Boundary INVALID = new Boundary(Kind.INVALID, Map.of());
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final ProjectWorkspaces projects;
  private final io.aeyer.plowshare.server.archive.ApplicationRegistrations registrations;

  public WorkspaceApplicationPolicy(
      ProjectWorkspaces projects,
      io.aeyer.plowshare.server.archive.ApplicationRegistrations registrations) {
    this.projects = projects;
    this.registrations = registrations;
  }

  @Override
  public Boundary read(String project) {
    if (project == null
        || project.startsWith("personal:")
        || ClientProjects.privateProject(project)) return EXTERNAL;
    boolean required = registrations.required(project);
    var recorded = projects.find(project);
    if (recorded.isEmpty())
      return required ? INVALID : EXTERNAL; // find never returns another machine's workspace.
    var row = recorded.orElseThrow();
    // Plowshare owns MANAGED sources: they must never become legacy external projects.
    // Earlier registrations without an adopted boundary also need a valid manifest and grants.
    if (row.type().equals("MANAGED") && !required) {
      registrations.require(project);
      required = true;
    }
    Path file = row.workspace().resolve(FILE);
    try {
      if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return required ? INVALID : EXTERNAL;
      if (!required) registrations.require(project);
      Path root = row.workspace().toRealPath();
      file = root.resolve(FILE);
      if (Files.isSymbolicLink(file)
          || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || !FileAccess.of(List.of(file), projects.effectiveExclusions(row)).permits(file))
        return INVALID;
      try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = input.readNBytes(ProjectConfiguration.MAX_BYTES + 1);
        if (bytes.length > ProjectConfiguration.MAX_BYTES) return INVALID;
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return parse(text, project);
      }
    } catch (IOException | IllegalArgumentException | SecurityException invalid) {
      return INVALID; // Never fall back to legacy grants after an unreadable or malformed boundary.
    }
  }

  /** Validates the versioned application settings and converts only declared authority fields. */
  public static Boundary parse(String text, String project) throws IOException {
    if (!text.stripLeading().startsWith("{"))
      throw new IllegalArgumentException("Application manifest must be JSON");
    ProjectConfiguration.parse(text, project, FILE); // Validate the existing runtime settings too.
    JsonNode body = JSON.readTree(text);
    validateRouting(body.get("routing"));
    JsonNode access = body.get("access");
    if (access == null) return new Boundary(Kind.APPLICATION, Map.of());
    fields(access, List.of("accounts"));
    JsonNode accounts = access.get("accounts");
    if (accounts == null || !accounts.isArray() || accounts.size() > 256)
      throw new IllegalArgumentException("Application access needs a bounded accounts list");
    Map<String, ProjectRole> grants = new HashMap<>();
    for (JsonNode entry : accounts) {
      fields(entry, List.of("handle", "role"));
      JsonNode handle = entry.get("handle"), role = entry.get("role");
      if (handle == null
          || !handle.isTextual()
          || !identity(handle.textValue())
          || role == null
          || !role.isTextual()
          || !List.of("VIEWER", "CONTRIBUTOR", "MANAGER").contains(role.textValue()))
        throw new IllegalArgumentException("Invalid application account grant");
      if (grants.putIfAbsent(handle.textValue(), ProjectRole.valueOf(role.textValue())) != null)
        throw new IllegalArgumentException("Duplicate application account grant");
    }
    return new Boundary(Kind.APPLICATION, grants);
  }

  private static void validateRouting(JsonNode routing) {
    if (routing == null) return;
    fields(routing, List.of("acceptFrom", "sendTo", "routeFiles"));
    for (String field : List.of("acceptFrom", "sendTo", "routeFiles")) {
      JsonNode entries = routing.get(field);
      if (entries == null) continue;
      if (!entries.isArray() || entries.size() > 256)
        throw new IllegalArgumentException("Invalid application routing");
      var unique = new java.util.HashSet<String>();
      for (JsonNode entry : entries) {
        if (!entry.isTextual() || !identity(entry.textValue()) || !unique.add(entry.textValue()))
          throw new IllegalArgumentException("Invalid application routing entry");
        if (field.equals("routeFiles")
            && (entry.textValue().contains("\\")
                || entry.textValue().contains(":")
                || java.util.Arrays.stream(entry.textValue().split("/", -1))
                    .anyMatch(
                        segment ->
                            segment.isEmpty() || segment.equals(".") || segment.equals(".."))))
          throw new IllegalArgumentException("Invalid application route file");
      }
    }
  }

  private static boolean identity(String value) {
    return !value.isBlank()
        && value.equals(value.strip())
        && value.length() <= 512
        && value.codePoints().noneMatch(Character::isISOControl);
  }

  private static void fields(JsonNode object, List<String> allowed) {
    if (!object.isObject())
      throw new IllegalArgumentException("Application policy must be an object");
    object
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!allowed.contains(field))
                throw new IllegalArgumentException("Unknown application policy field");
            });
  }
}
