package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ProjectView;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server workspace provisioning is a single authenticated WS mutation. */
@Component
public class ServerProjectFrames implements FrameArea {
  public record Create(String name, String workspace, String type, List<String> writePaths) {}

  private final ProjectStore projects;
  private final Path directory;

  public ServerProjectFrames(
      ProjectStore projects, @Value("${plowshare.projects.workspace-directory}") String directory) {
    this.projects = java.util.Objects.requireNonNull(projects, "projects");
    if (directory == null || directory.isBlank() || directory.indexOf('\0') >= 0)
      throw new IllegalArgumentException("plowshare.projects.workspace-directory is required");
    Path configured = Path.of(directory);
    if (!configured.isAbsolute())
      throw new IllegalArgumentException("plowshare.projects.workspace-directory must be absolute");
    this.directory = configured.normalize();
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(FrameTypes.PROJECT_CREATE, this::create);
  }

  private Outcome create(Map<String, Object> payload, Asking asking) {
    Create request = Payloads.as(payload, Create.class, FrameTypes.PROJECT_CREATE);
    String name = request.name();
    if (name == null
        || name.isBlank()
        || !name.equals(name.trim())
        || name.length() > 512
        || name.matches("(?s).*[\\r\\n\\x00].*"))
      throw new CallerFault(
          "Use a nonblank project name of at most 512 characters without line breaks");
    String type = request.type() == null ? "MANAGED" : request.type();
    if (!type.equals("MANAGED") && !type.equals("DISJOINT"))
      throw new CallerFault("Choose MANAGED or DISJOINT");
    boolean disjoint = type.equals("DISJOINT");
    List<String> writes =
        request.writePaths() == null ? (disjoint ? List.of() : List.of(".")) : request.writePaths();
    for (String path : writes) {
      if (path == null
          || path.isBlank()
          || path.contains("\\")
          || Path.of(path).isAbsolute()
          || java.util.Arrays.asList(path.split("/")).contains(".."))
        throw new CallerFault(
            "writePaths must name relative workspace areas, without traversal; use . for the whole workspace");
    }
    if (disjoint && (request.workspace() == null || request.workspace().isBlank()))
      throw new CallerFault("DISJOINT needs an existing server workspace");
    Path root =
        request.workspace() == null || request.workspace().isBlank()
            ? directory.resolve(UUID.randomUUID().toString())
            : Path.of(request.workspace()).toAbsolutePath().normalize();
    boolean[] made = {false, false};
    boolean[] application = {false};
    boolean committed = false;
    try {
      ProjectRecord created =
          projects.createServer(
              name,
              type,
              writes,
              asking.handle(),
              () -> {
                try {
                  if (disjoint) {
                    if (!Files.isDirectory(root))
                      throw new CallerFault("DISJOINT workspace must already exist on the server");
                  } else {
                    if (!Files.exists(root)) {
                      Files.createDirectories(root.getParent());
                      Files.createDirectory(root);
                      made[0] = true;
                    }
                    if (!Files.isDirectory(root))
                      throw new CallerFault("Workspace must be a directory");
                  }
                  Path marker = root.resolve("plowshare.json");
                  application[0] = !disjoint || !Files.notExists(marker, LinkOption.NOFOLLOW_LINKS);
                  if (!Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(marker)
                        || Files.size(marker) > 65536)
                      throw new CallerFault("Application manifest must be a bounded regular file");
                    try (var input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
                      byte[] bytes = input.readNBytes(65537);
                      if (bytes.length > 65536)
                        throw new CallerFault("Application manifest is too large");
                      io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy.parse(
                          java.nio.charset.StandardCharsets.UTF_8
                              .newDecoder()
                              .decode(java.nio.ByteBuffer.wrap(bytes))
                              .toString(),
                          name);
                    }
                  } else if (!disjoint) {
                    var json = new com.fasterxml.jackson.databind.ObjectMapper();
                    var manifest = json.createObjectNode().put("version", 1).put("name", name);
                    manifest
                        .putObject("access")
                        .putArray("accounts")
                        .addObject()
                        .put("handle", asking.requireHandle(FrameTypes.PROJECT_CREATE))
                        .put("role", "MANAGER");
                    Files.writeString(
                        marker,
                        manifest.toString() + "\n",
                        java.nio.file.StandardOpenOption.CREATE_NEW);
                    made[1] = true;
                  }
                  return new ProjectStore.ServerWorkspace(root, application[0]);
                } catch (IOException | IllegalArgumentException failed) {
                  throw new CallerFault("Server workspace could not be initialized");
                }
              });
      committed = true;
      return Outcome.ok(
          ProjectView.of(created, projects.effectiveExclusions(created))
              .application(application[0]));
    } catch (RuntimeException failed) {
      // Once registration committed, a later failure has uncertain delivery; preserve its source.
      if (committed) throw failed;
      // Remove only files this attempt created, never an existing checkout or pipeline content.
      try {
        if (made[1]) Files.deleteIfExists(root.resolve("plowshare.json"));
        if (made[0]) {
          Files.deleteIfExists(root);
        }
      } catch (IOException cleanup) {
        failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }
}
