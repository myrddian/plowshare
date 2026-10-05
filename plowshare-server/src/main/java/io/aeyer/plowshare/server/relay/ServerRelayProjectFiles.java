package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.server.archive.ClientProjects;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalWorkspaces;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded reads in an ordinary server project or the caller's private Personal union. */
public final class ServerRelayProjectFiles implements RelayProjectFiles {
  private final ProjectWorkspaces projects;
  private final ProjectMembers members;
  private final PersonalWorkspaces personal;

  public ServerRelayProjectFiles(
      ProjectWorkspaces projects, ProjectMembers members, PersonalWorkspaces personal) {
    this.projects = Objects.requireNonNull(projects);
    this.members = Objects.requireNonNull(members);
    this.personal = Objects.requireNonNull(personal);
  }

  @Override
  public void requireAccess(Access access) {
    workspace(access);
  }

  private record Workspace(Path root, FileAccess fence) {}

  private Workspace workspace(Access access) {
    Objects.requireNonNull(access, "access");
    if (ClientProjects.privateProject(access.project())
        || !members.mayWork(access.project(), access.account()))
      throw new CallerFault("Relay configuration requires contributor access to this project");
    if (!Objects.equals(projects.id(access.project()), access.projectId()))
      throw new CallerFault("Relay project identity has changed or is unavailable");
    Optional<String> owner = projects.personalOwner(access.project());
    if (owner.isPresent()) {
      if (!owner.get().equals(access.account()))
        throw new CallerFault("This Personal Relay workspace belongs to another account");
      Path root =
          personal
              .workspace(access.project())
              .orElseThrow(() -> new CallerFault("The Personal Relay workspace is unavailable"));
      // Personal's owning API supplies one private union tree. Ordinary projects' mandatory
      // data-directory exclusion deliberately does not grant or revoke access to this tree.
      return new Workspace(root, FileAccess.of(List.of(root), List.of()));
    }
    var project =
        projects
            .find(access.project())
            .orElseThrow(
                () ->
                    new CallerFault(
                        "Relay requires a supported server workspace; no local fallback is used"));
    return new Workspace(project.workspace(), project.reach(projects.effectiveExclusions(project)));
  }

  @Override
  public Optional<String> read(Access access, String relative) {
    RelayProjectFiles.path(relative);
    Workspace workspace = workspace(access);
    try {
      Path root = workspace.root().toRealPath();
      Path path = root.resolve("Relay").resolve(relative);
      // Walk every component, including the Relay directory, before checking absence. A linked
      // missing target is still a refused configuration, never an empty activation fallback.
      Path at = root;
      for (Path segment : root.relativize(path)) {
        at = at.resolve(segment);
        if (Files.isSymbolicLink(at))
          throw new CallerFault("Relay files cannot use symbolic links");
      }
      if (!workspace.fence().permits(path))
        throw new CallerFault("Relay configuration is outside the permitted workspace");
      if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        throw new CallerFault("Relay configuration must be regular files");
      int limit = relative.endsWith(".js") ? 131072 : 65536;
      // As with normal server workspace reads, NOFOLLOW protects the final component. Java's
      // portable file APIs cannot secure parent-directory replacement during an open; this is
      // the existing workspace fence, not a stronger filesystem isolation boundary.
      try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit)
          throw new CallerFault("Relay configuration exceeds its byte limit");
        return Optional.of(decode(bytes));
      }
    } catch (IOException failed) {
      // Filesystem diagnostics may reveal private paths. Preserve only a fixed actionable error.
      throw new CallerFault("Relay configuration could not be read as bounded UTF-8 files");
    }
  }

  static String decode(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }
}
