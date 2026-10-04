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
      ProjectStore projects,
      @Value("${plowshare.projects.workspace-directory:workspaces}") String directory) {
    this.projects = projects;
    this.directory = Path.of(directory).toAbsolutePath().normalize();
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
                  Path marker = root.resolve(".plowshare/project");
                  if (Files.isSymbolicLink(marker.getParent()))
                    throw new CallerFault("Project marker directory must not be a symlink");
                  if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                        || !Files.readString(marker)
                            .lines()
                            .findFirst()
                            .orElse("")
                            .trim()
                            .equals(name))
                      throw new CallerFault(
                          "Workspace marker belongs to a different project or is invalid");
                  } else if (!disjoint) {
                    if (Files.isSymbolicLink(marker.getParent()))
                      throw new CallerFault("Project marker directory must not be a symlink");
                    Files.createDirectories(marker.getParent());
                    Files.writeString(
                        marker,
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .createObjectNode()
                                .put("version", 1)
                                .put("name", name)
                                .toString()
                            + "\n",
                        java.nio.file.StandardOpenOption.CREATE_NEW);
                    made[1] = true;
                  }
                  return root;
                } catch (IOException failed) {
                  throw new CallerFault("Server workspace could not be initialized");
                }
              });
      return Outcome.ok(ProjectView.of(created, projects.effectiveExclusions(created)));
    } catch (RuntimeException failed) {
      // Remove only files this attempt created, never an existing checkout or pipeline content.
      try {
        if (made[1]) Files.deleteIfExists(root.resolve(".plowshare/project"));
        if (made[0]) {
          Files.deleteIfExists(root.resolve(".plowshare"));
          Files.deleteIfExists(root);
        }
      } catch (IOException cleanup) {
        failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }
}
