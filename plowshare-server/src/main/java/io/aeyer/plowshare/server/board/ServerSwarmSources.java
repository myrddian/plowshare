package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.data.DataLayout;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Applications use their root/swarm; other tiers use server-owned named definitions. */
public final class ServerSwarmSources implements SwarmSources {
  private final ProjectWorkspaces projects;
  private final ApplicationPolicy applications;
  private final DataLayout data;

  public ServerSwarmSources(
      ProjectWorkspaces projects, ApplicationPolicy applications, DataLayout data) {
    this.projects = projects;
    this.applications = applications;
    this.data = data;
  }

  @Override
  public List<Source> read(String project) {
    var boundary = applications.read(project);
    if (boundary.kind() == ApplicationPolicy.Kind.INVALID)
      throw new Board.Refused("The application manifest is invalid or unavailable");
    if (boundary.kind() == ApplicationPolicy.Kind.APPLICATION) {
      var record =
          projects
              .find(project)
              .orElseThrow(() -> new Board.Refused("The application workspace is unavailable"));
      Path directory = record.workspace().resolve("swarm");
      return directory(
          record.workspace(),
          directory,
          record.reach(projects.effectiveExclusions(record)),
          "swarm/");
    }
    if (data.keepsAnything()) {
      Long id = projects.id(project);
      if (id == null) throw new Board.Refused("The project is unavailable");
      Path own = data.swarmFor(id).getParent();
      if (Files.exists(own, LinkOption.NOFOLLOW_LINKS))
        return directory(data.root(), own, null, "project:swarm/");
      Path global = data.swarmFor(null).getParent();
      if (Files.exists(global, LinkOption.NOFOLLOW_LINKS))
        return directory(data.root(), global, null, "global:swarm/");
    }
    return shipped();
  }

  static List<Source> shipped() {
    try (var input = ServerSwarmSources.class.getResourceAsStream("/global/swarm/default.md")) {
      if (input == null) throw new IllegalStateException("The shipped default swarm is missing");
      return List.of(
          new Source(
              "default", "md", decode(input.readAllBytes()), "classpath:global/swarm/default.md"));
    } catch (IOException failed) {
      throw new IllegalStateException("The shipped swarm could not be read", failed);
    }
  }

  static List<Source> directory(Path directory, FileAccess fence, String origin) {
    return directory(directory.getParent(), directory, fence, origin);
  }

  /** Resolve only the owning configured root; refuse every link introduced beneath it. */
  static List<Source> directory(Path owningRoot, Path directory, FileAccess fence, String origin) {
    try {
      Path root = owningRoot.toAbsolutePath().normalize();
      Path requested = directory.toAbsolutePath().normalize();
      if (!requested.startsWith(root))
        throw new Board.Refused("Swarm sources are outside their owning root");
      Path relative = root.relativize(requested);
      Path real = root.toRealPath();
      Path at = real;
      for (Path segment : relative) {
        at = at.resolve(segment);
        if (Files.isSymbolicLink(at))
          throw new Board.Refused("Swarm sources cannot use symbolic links");
      }
      directory = at;
      if (fence != null && !fence.permits(directory))
        throw new Board.Refused("Swarm sources are outside the permitted workspace");
      if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
      if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
        throw new Board.Refused("swarm must be a directory");
      List<Path> paths;
      try (var entries = Files.list(directory)) {
        paths = entries.limit(65).sorted().toList();
      }
      if (paths.size() > 64) throw new Board.Refused("A swarm directory holds at most 64 entries");
      var names = new HashSet<String>();
      var sources = new ArrayList<Source>();
      int total = 0;
      for (Path path : paths) {
        String file = path.getFileName().toString();
        if (!file.endsWith(".md") && !file.endsWith(".json"))
          throw new Board.Refused("Swarm definitions must be .md or .json files");
        int dot = file.lastIndexOf('.');
        String name = file.substring(0, dot);
        SwarmSelection.requireName(name);
        if (!names.add(name))
          throw new Board.Refused("Duplicate swarm type '" + name + "' across definition formats");
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          throw new Board.Refused("Swarm definitions must be regular files without links");
        if (fence != null && !fence.permits(path))
          throw new Board.Refused("A swarm definition is outside the permitted workspace");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
          byte[] bytes = input.readNBytes(65537);
          total += bytes.length;
          if (bytes.length > 65536 || total > 1048576)
            throw new Board.Refused("Swarm sources exceed their byte limit");
          sources.add(new Source(name, file.substring(dot + 1), decode(bytes), origin + file));
        }
      }
      return List.copyOf(sources);
    } catch (IOException failed) {
      throw new Board.Refused("Swarm sources could not be read as bounded UTF-8 files");
    }
  }

  private static String decode(byte[] bytes) throws IOException {
    return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
  }
}
