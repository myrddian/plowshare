package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Uses registered Application schedules, the External data tier or its authenticated file channel.
 * No fallback.
 */
public final class RegisteredScheduleFiles implements ScheduleFiles {
  private static final int MAX_FILES = 256, MAX_BYTES = 65536, TOTAL_BYTES = 1048576;
  private io.aeyer.plowshare.server.agents.ApplicationResources resources =
      io.aeyer.plowshare.server.agents.ApplicationResources.NONE;

  public void useApplicationResources(
      io.aeyer.plowshare.server.agents.ApplicationResources resources) {
    this.resources = resources;
  }

  private final DataLayout data;
  private final SessionChannel channel;
  private final PresenceRegistry presences;
  private final SessionRegistry sessions;

  public RegisteredScheduleFiles(
      DataLayout data,
      SessionChannel channel,
      PresenceRegistry presences,
      SessionRegistry sessions) {
    this.data = data;
    this.channel = channel;
    this.presences = presences;
    this.sessions = sessions;
  }

  public List<Entry> read(ScheduleDefinitionStore.Source source) {
    if (source.source().equals("workspace")) return remote(source);
    Path folder = folder(source);
    List<Entry> entries = new ArrayList<>();
    try {
      safe(folder, source);
      if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return List.of();
      try (var paths = Files.list(folder)) {
        var matching =
            paths
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .limit(MAX_FILES + 1L)
                .sorted()
                .toList();
        if (matching.size() > MAX_FILES)
          throw new WorkspaceUnavailableException("Too many schedule files");
        for (Path file : matching) {
          String name = name(file);
          safe(file, source);
          if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES)
            throw new WorkspaceUnavailableException(
                "Schedule file is not a bounded regular JSON file");
          entries.add(new Entry(name, Files.readString(file, StandardCharsets.UTF_8)));
          bounded(entries);
        }
      }
      return List.copyOf(entries);
    } catch (IOException failed) {
      throw new WorkspaceUnavailableException("Schedule folder could not be read", failed);
    }
  }

  public void write(
      ScheduleDefinitionStore.Source source, String name, String text, boolean overwrite) {
    if (!source.source().equals("workspace") && resources.root(source.projectId()).isPresent())
      throw new IllegalArgumentException(
          "Update Application schedules through a new deployment revision");
    ScheduledWork.identity(name, "file name");
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("Schedule file exceeds 64 KiB");
    if (source.source().equals("workspace")) {
      ask(
          session(source),
          (overwrite
                  ? FileRequest.write(id(), remotePath(name), text)
                  : FileRequest.create(id(), remotePath(name), text))
              .forSchedules());
      return;
    }
    Path folder = folder(source), file = folder.resolve(name + ".json");
    try {
      safe(file, source);
      Files.createDirectories(folder);
      safe(file, source);
      if (!overwrite) {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return;
      }
      Path staged = Files.createTempFile(folder, "schedule-", ".tmp");
      try {
        Files.writeString(staged, text, StandardCharsets.UTF_8);
        Files.move(
            staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(staged);
      }
    } catch (IOException failed) {
      throw new WorkspaceUnavailableException("Schedule file could not be written", failed);
    }
  }

  public void delete(ScheduleDefinitionStore.Source source, String name) {
    if (!source.source().equals("workspace") && resources.root(source.projectId()).isPresent())
      throw new IllegalArgumentException(
          "Update Application schedules through a new deployment revision");
    ScheduledWork.identity(name, "file name");
    if (source.source().equals("workspace")) {
      ask(session(source), FileRequest.delete(id(), remotePath(name)).forSchedules());
      return;
    }
    Path file = folder(source).resolve(name + ".json");
    try {
      safe(file, source);
      Files.delete(file);
    } catch (IOException failed) {
      throw new WorkspaceUnavailableException("Schedule file could not be deleted", failed);
    }
  }

  private List<Entry> remote(ScheduleDefinitionStore.Source source) {
    String session = session(source);
    var roots = ask(session, FileRequest.roots(id()));
    if (roots.paths() == null || roots.paths().size() != 1)
      throw new WorkspaceUnavailableException("Schedule folders require one rooted workspace");
    // Remote names belong to the provider's filesystem, including Windows providers.
    // Validate logical segments without interpreting them as paths on the server host.
    String root = roots.paths().getFirst().replace('\\', '/').replaceAll("/+$", "");
    String folder = root + "/.plowshare/schedules";
    var listing =
        ask(session, FileRequest.glob(id(), ".plowshare/schedules/*.json").forSchedules());
    if (listing.paths() == null || listing.paths().size() > MAX_FILES)
      throw new WorkspaceUnavailableException("Invalid or oversized schedule listing");
    List<Entry> result = new ArrayList<>();
    for (String hit : listing.paths()) {
      String normalized = hit.replace('\\', '/');
      if (!normalized.startsWith("/") && !normalized.matches("^[A-Za-z]:/.*"))
        normalized = root + "/" + normalized;
      String prefix = folder + "/";
      if (!normalized.startsWith(prefix))
        throw new WorkspaceUnavailableException("Schedule listing escaped its folder");
      String leaf = normalized.substring(prefix.length());
      if (leaf.contains("/") || !leaf.endsWith(".json") || leaf.startsWith("."))
        throw new WorkspaceUnavailableException("Schedule listing escaped its folder");
      String name = ScheduledWork.identity(leaf.substring(0, leaf.length() - 5), "file name");
      var reply = ask(session, FileRequest.read(id(), hit, Window.of(0, 2000)).forSchedules());
      if (reply.span() == null || reply.span().more() || reply.span().offset() != 0)
        throw new WorkspaceUnavailableException("Schedule file was truncated");
      result.add(new Entry(name, String.join("\n", reply.span().lines())));
      bounded(result);
    }
    return List.copyOf(result);
  }

  private FileReply ask(String session, FileRequest request) {
    var reply = channel.ask(session, request, Duration.ofSeconds(10));
    if (reply == null || !request.id().equals(reply.id()) || !FileReply.OK.equals(reply.outcome()))
      throw new WorkspaceUnavailableException(
          "The schedule file channel did not complete the request");
    return reply;
  }

  @Override
  public String executionSession(ScheduleDefinitionStore.Source source) {
    return source.source().equals("workspace") ? session(source) : null;
  }

  private String session(ScheduleDefinitionStore.Source source) {
    if (source.project() == null)
      throw new WorkspaceUnavailableException("Workspace schedules require a project");
    String session =
        presences
            .serving(source.project())
            .orElseThrow(
                () -> new WorkspaceUnavailableException("The schedule workspace is offline"))
            .session();
    if (!sessions.accountOf(session).filter(source.account()::equals).isPresent())
      throw new WorkspaceUnavailableException("The schedule workspace belongs to another account");
    return session;
  }

  private Path folder(ScheduleDefinitionStore.Source source) {
    var application = resources.directory(source.projectId(), "schedules");
    if (application.isPresent()) return application.get();
    if (!data.keepsAnything())
      throw new WorkspaceUnavailableException("This server has no definitions directory");
    return data.schedulesFor(source.projectId());
  }

  private void safe(Path path, ScheduleDefinitionStore.Source source) {
    Path
        root =
            resources
                .root(source.projectId())
                .orElseGet(() -> data.root())
                .toAbsolutePath()
                .normalize(),
        at = root;
    if (!path.toAbsolutePath().normalize().startsWith(root))
      throw new WorkspaceUnavailableException("Schedule path escaped the definitions tier");
    for (Path segment : root.relativize(path.toAbsolutePath().normalize())) {
      at = at.resolve(segment);
      if (Files.isSymbolicLink(at))
        throw new WorkspaceUnavailableException("Schedule folders cannot contain symbolic links");
    }
  }

  private static String name(Path file) {
    String name = file.getFileName().toString();
    if (name.startsWith("."))
      throw new WorkspaceUnavailableException("Schedule file names cannot start with a dot");
    return ScheduledWork.identity(name.substring(0, name.length() - 5), "file name");
  }

  private static String remotePath(String name) {
    return ".plowshare/schedules/" + name + ".json";
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private static void bounded(List<Entry> entries) {
    if (entries.size() > MAX_FILES
        || entries.stream().mapToInt(e -> e.text().getBytes(StandardCharsets.UTF_8).length).sum()
            > TOTAL_BYTES
        || entries.stream()
            .anyMatch(e -> e.text().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES))
      throw new WorkspaceUnavailableException("Schedule folder exceeds its file or byte limit");
  }
}
