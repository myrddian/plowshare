package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Application source browsing shares the deployed manifest, membership and workspace fences. */
@Component
public final class WorkspaceApplicationFiles implements ApplicationFiles {
  public static final int MAX_BYTES = 262144;
  private final ProjectWorkspaces projects;
  private final ProjectMembers members;
  private final ApplicationPolicy applications;

  public WorkspaceApplicationFiles(
      ProjectWorkspaces projects, ProjectMembers members, ApplicationPolicy applications) {
    this.projects = projects;
    this.members = members;
    this.applications = applications;
  }

  private record Source(ProjectRecord project, Path root, Path path, FileAccess fence) {}

  private Source source(Caller caller, String relative) throws IOException {
    if (caller == null || caller.project() == null || caller.account() == null)
      throw new CallerFault("Application files need an authenticated account and project");
    members.requireRole(caller.project(), caller.account(), ProjectRole.VIEWER);
    if (applications.read(caller.project()).kind() != ApplicationPolicy.Kind.APPLICATION)
      throw new CallerFault("Choose an available server Application");
    var row =
        projects
            .find(caller.project())
            .orElseThrow(() -> new CallerFault("The server Application workspace is unavailable"));
    path(relative);
    Path root = row.workspace().toRealPath(),
        target = relative.isEmpty() ? root : root.resolve(relative);
    var fence = row.reach(projects.effectiveExclusions(row));
    Path at = root;
    for (Path segment : root.relativize(target)) {
      at = at.resolve(segment);
      if (Files.isSymbolicLink(at))
        throw new CallerFault("Application files cannot use symbolic links");
    }
    if (!fence.permits(target))
      throw new CallerFault("This path is outside the permitted Application workspace");
    return new Source(row, root, target, fence);
  }

  /** Paths are portable, relative and canonical; the empty path names the root for listing only. */
  public static void path(String path) {
    if (path == null
        || path.length() > 2048
        || path.contains("\\")
        || path.startsWith("/")
        || path.contains(":")) throw new CallerFault("Application file paths must be relative");
    if (path.isEmpty()) return;
    for (String part : path.split("/", -1))
      if (part.isEmpty()
          || part.equals(".")
          || part.equals("..")
          || part.equalsIgnoreCase(".git")
          || part.codePoints().anyMatch(Character::isISOControl))
        throw new CallerFault(
            "Application file paths cannot contain traversal, Git metadata or control characters");
  }

  @Override
  public Listing list(Caller caller, String path) {
    try {
      var source = source(caller, path);
      if (!Files.isDirectory(source.path(), LinkOption.NOFOLLOW_LINKS))
        throw new CallerFault("Choose a directory to browse");
      var entries = new ArrayList<Entry>();
      int scanned = 0;
      boolean more = false;
      try (var children = Files.newDirectoryStream(source.path())) {
        for (Path child : children) {
          if (++scanned > 10000)
            throw new CallerFault("This directory exceeds the browser entry limit");
          String relative = source.root().relativize(child).toString().replace('\\', '/');
          if (Files.isSymbolicLink(child)
              || !source.fence().permits(child)
              || child.getFileName().toString().equalsIgnoreCase(".git")) continue;
          // Refuse names the request boundary cannot address, rather than exposing misleading
          // links.
          try {
            path(relative);
          } catch (CallerFault invalid) {
            continue;
          }
          if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
              || Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS))
            entries.add(
                new Entry(
                    relative,
                    child.getFileName().toString(),
                    Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)));
        }
      }
      entries.sort(Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::name));
      if (entries.size() > 200) {
        more = true;
        entries.subList(200, entries.size()).clear();
      }
      return new Listing(caller.project(), path, entries, more);
    } catch (IOException | SecurityException unavailable) {
      throw new CallerFault("The Application directory could not be read");
    }
  }

  private byte[] bytes(Source source) throws IOException {
    if (!Files.isRegularFile(source.path(), LinkOption.NOFOLLOW_LINKS))
      throw new CallerFault("Choose an existing regular text file");
    try (var input = Files.newInputStream(source.path(), LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES)
        throw new CallerFault("This file exceeds the 256 KiB text editor limit");
      return bytes;
    }
  }

  private static String revision(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private boolean writable(Caller caller, Source source) {
    if (!members.mayWork(caller.project(), caller.account())) return false;
    String relative = source.root().relativize(source.path()).toString().replace('\\', '/');
    // Editing runtime definitions or the access/routing manifest needs definition-management
    // authority.
    boolean configuration =
        relative.equals("plowshare.json")
            || relative.startsWith(".plowshare/")
            || relative.startsWith("Relay/");
    if (configuration && !members.mayManage(caller.project(), caller.account())) return false;
    return source.project().writePaths().stream()
        .anyMatch(
            allowed ->
                allowed.equals(".")
                    || relative.equals(allowed)
                    || relative.startsWith(allowed + "/"));
  }

  private static String text(byte[] bytes) throws java.nio.charset.CharacterCodingException {
    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    if (text.indexOf(0) >= 0) throw new CallerFault("This file is not editable text");
    return text;
  }

  @Override
  public Document read(Caller caller, String path) {
    try {
      var source = source(caller, path);
      byte[] bytes = bytes(source);
      String text = text(bytes);
      return new Document(caller.project(), path, text, revision(bytes), writable(caller, source));
    } catch (IOException | SecurityException unavailable) {
      throw new CallerFault("The Application file could not be read as bounded UTF-8 text");
    }
  }

  @Override
  public synchronized Document save(Caller caller, String path, String text, String expected) {
    if (text == null
        || text.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff)
        || text.indexOf(0) >= 0
        || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
        || expected == null
        || !expected.matches("[a-f0-9]{64}"))
      throw new CallerFault("Saving needs bounded text and its reviewed file revision");
    Path temporary = null;
    try {
      var source = source(caller, path);
      if (!writable(caller, source))
        throw new CallerFault("Write access is not allowed for this Application file");
      byte[] existing = bytes(source);
      text(existing);
      if (!revision(existing).equals(expected))
        throw new CallerFault("The file changed elsewhere. Refresh and review it before saving");
      if (path.equals("plowshare.json")) {
        try {
          WorkspaceApplicationPolicy.parse(text, caller.project());
        } catch (IllegalArgumentException invalid) {
          throw new CallerFault("The Application manifest must remain valid");
        }
      }
      temporary = Files.createTempFile(source.path().getParent(), ".plowshare-edit-", ".tmp");
      if (Files.getFileStore(source.path()).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(
            temporary, Files.getPosixFilePermissions(source.path(), LinkOption.NOFOLLOW_LINKS));
      Files.writeString(
          temporary, text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
      // Recheck authority and containment before publishing. The atomic replacement avoids partial
      // files;
      // external source owners do not participate in this service's JVM serialization or content
      // check.
      source = source(caller, path);
      if (!writable(caller, source) || !revision(bytes(source)).equals(expected))
        throw new CallerFault("The file or its permissions changed. Refresh before saving");
      Files.move(
          temporary,
          source.path(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      temporary = null;
      return new Document(
          caller.project(),
          path,
          text,
          revision(text.getBytes(StandardCharsets.UTF_8)),
          writable(caller, source));
    } catch (IOException | SecurityException unavailable) {
      throw new CallerFault("The Application file could not be saved atomically");
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException cleanup) {
          throw new IllegalStateException(
              "Could not remove an unpublished Application edit", cleanup);
        }
    }
  }
}
