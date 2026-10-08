package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.protocol.ApplicationDeployment.*;
import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.FileStores;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Staging is outside the database transaction. Only a committed placement is runnable. Staged
 * orphans are retained after uncertain commit; they are never inferred to be active or replayed.
 */
public final class SourceApplicationDeployments implements ApplicationDeployments {
  public static final int MAX_FILES = 128, MAX_FILE_BYTES = 65536, MAX_TOTAL_BYTES = 131072;
  private final ApplicationDeploymentStore store;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final FileStores fileStores;
  private final ApplicationPackageValidator validator;

  public SourceApplicationDeployments(
      ApplicationDeploymentStore store,
      ProjectMembers members,
      ProjectWorkspaces projects,
      FileStores fileStores,
      ApplicationPackageValidator validator) {
    this.store = store;
    this.members = members;
    this.projects = projects;
    this.fileStores = fileStores;
    this.validator = validator;
  }

  private void require(String account, String project) {
    if (!members.isServerAdmin(account))
      throw new CallerFault("Application deployment requires a server administrator");
    if (project == null
        || project.isBlank()
        || !project.equals(project.strip())
        || project.length() > 512
        || project.codePoints().anyMatch(Character::isISOControl)
        || project.startsWith("personal:")
        || ClientProjects.privateProject(project))
      throw new CallerFault("Choose an ordinary Application project name");
  }

  @Override
  public Receipt deploy(String account, Deploy request) {
    require(account, request.project());
    Objects.requireNonNull(request.requestId());
    validateFiles(request.files());
    String digest = digest(request.files());
    String fingerprint = fingerprint(request, digest);
    var prior = store.receipt(account, request.project(), request.requestId(), fingerprint);
    if (prior.isPresent()) return prior.get();
    UUID revision = UUID.randomUUID();
    var destination = request.destination();
    if (destination.path().isEmpty())
      throw new CallerFault("Deployment destination must be a dedicated relative directory");
    String path = destination.path() + "/revisions/" + revision;
    var placement =
        new ApplicationPlacement(
            new FileStoreReference(destination.store(), path), request.writableAreas());
    var resolved = resolve(account, request.project(), destination, placement);
    // A new, unpredictable revision directory is never overwritten, including after crashes.
    try {
      Files.createDirectories(resolved.root().getParent());
      resolve(account, request.project(), destination, placement);
      Files.createDirectory(resolved.root());
      for (var file : request.files()) {
        Path target = resolved.root().resolve(file.path());
        var fence = sourceFence(request.project(), resolved.root());
        if (!fence.permits(target)) throw new CallerFault("Application source escaped staging");
        Path at = resolved.root();
        for (Path segment : resolved.root().relativize(target.getParent())) {
          at = at.resolve(segment);
          if (Files.notExists(at, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(at);
          if (Files.isSymbolicLink(at) || !Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS))
            throw new CallerFault("Application staging source changed to a link");
        }
        Files.writeString(
            target,
            file.text(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            LinkOption.NOFOLLOW_LINKS);
      }
      WorkspaceApplicationPolicy.parse(
          request.files().stream()
              .filter(f -> f.path().equals("plowshare.json"))
              .findFirst()
              .orElseThrow()
              .text(),
          request.project());
      validator.validate(request.project(), resolved.root());
      if (!resolve(account, request.project(), destination, placement).equals(resolved))
        throw new CallerFault("FileStore placement changed while staging the Application");
    } catch (IOException invalid) {
      throw new CallerFault("Application source could not be staged or its manifest is invalid");
    }
    var retained =
        new ApplicationDeploymentStore.Retained(
            new Release(revision, digest, request.files().size()), destination, placement);
    return store.commit(
        account,
        new ApplicationDeploymentStore.Mutation(
            request.project(),
            request.requestId(),
            request.expectedRevision(),
            fingerprint,
            retained,
            resolved.root(),
            true));
  }

  @Override
  public Receipt activate(String account, Activate request) {
    require(account, request.project());
    Objects.requireNonNull(request.requestId());
    Objects.requireNonNull(request.expectedRevision());
    Objects.requireNonNull(request.revision());
    String fingerprint =
        hash(
            "activate\0"
                + request.project()
                + "\0"
                + request.expectedRevision()
                + "\0"
                + request.revision());
    var prior = store.receipt(account, request.project(), request.requestId(), fingerprint);
    if (prior.isPresent()) return prior.get();
    var retained = store.release(request.project(), request.revision());
    var resolved =
        resolve(account, request.project(), retained.destination(), retained.placement());
    List<File> files = read(resolved.root());
    if (!digest(files).equals(retained.release().digest())
        || files.size() != retained.release().fileCount())
      throw new CallerFault("Retained Application source changed; deploy a new revision");
    try {
      WorkspaceApplicationPolicy.parse(
          files.stream()
              .filter(f -> f.path().equals("plowshare.json"))
              .findFirst()
              .orElseThrow()
              .text(),
          request.project());
    } catch (IOException invalid) {
      throw new CallerFault("Retained Application manifest is invalid");
    }
    validator.validate(request.project(), resolved.root());
    return store.commit(
        account,
        new ApplicationDeploymentStore.Mutation(
            request.project(),
            request.requestId(),
            request.expectedRevision(),
            fingerprint,
            retained,
            resolved.root(),
            false));
  }

  @Override
  public Status status(String account, String project) {
    require(account, project);
    return store.status(project);
  }

  @Override
  public Receipt receipt(String account, String project, UUID requestId) {
    require(account, project);
    return store
        .receipt(account, project, requestId, null)
        .orElseThrow(
            () ->
                new CallerFault(
                    "Deployment receipt not found; absence does not authorize an automatic replay"));
  }

  private FileStores.Placement resolve(
      String account,
      String project,
      FileStoreReference destination,
      ApplicationPlacement placement) {
    if (!fileStores.permits(destination, account, ProjectRole.MANAGER))
      throw new CallerFault("Deployment needs MANAGER access to the destination FileStore");
    var resolved = fileStores.resolve(placement);
    var fence = sourceFence(project, resolved.root());
    if (!fence.permits(resolved.root()))
      throw new CallerFault("Deployment destination overlaps a private server area");
    for (var area : resolved.writableAreas()) {
      if (resolved.root().startsWith(area) || area.startsWith(resolved.root().getParent()))
        throw new CallerFault("Writable areas must be separate from retained deployment source");
    }
    return resolved;
  }

  private FileAccess sourceFence(String project, Path root) {
    var row =
        projects
            .find(project)
            .orElseGet(
                () -> new ProjectRecord(project, root, List.of(), List.of(), "MANAGED", List.of()));
    return FileAccess.of(List.of(root), projects.effectiveExclusions(row));
  }

  private static String readText(Path path) throws IOException {
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
      if (bytes.length > MAX_FILE_BYTES)
        throw new IOException("Retained source grew beyond its byte limit");
      return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
  }

  /**
   * Portable package boundary: text only, explicit paths, no hidden state, traversal or collisions.
   */
  public static void validateFiles(List<File> files) {
    try {
      io.aeyer.plowshare.protocol.ApplicationDeployment.validateFiles(files);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault(invalid.getMessage());
    }
  }

  private static List<File> read(Path root) {
    try (var paths = Files.walk(root)) {
      var entries = paths.limit(MAX_FILES * 18L + 1).toList();
      if (entries.size() > MAX_FILES * 18 || entries.stream().anyMatch(Files::isSymbolicLink))
        throw new CallerFault("Retained source is linked or oversized");
      List<File> files = new ArrayList<>();
      for (Path path : entries) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || Files.size(path) > MAX_FILE_BYTES)
          throw new CallerFault("Retained source must contain bounded regular text files");
        files.add(new File(root.relativize(path).toString().replace('\\', '/'), readText(path)));
      }
      validateFiles(files);
      return files;
    } catch (IOException unavailable) {
      throw new CallerFault("Retained Application source is unavailable");
    }
  }

  /** Length framing prevents path text from impersonating separators or another admitted area. */
  private static String fingerprint(Deploy request, String digest) {
    var value = new StringBuilder("deploy:");
    part(value, request.project());
    part(value, request.expectedRevision() == null ? "" : request.expectedRevision().toString());
    reference(value, request.destination());
    value.append(request.writableAreas().size()).append(':');
    for (var area : request.writableAreas()) reference(value, area);
    part(value, digest);
    return hash(value.toString());
  }

  private static void reference(StringBuilder value, FileStoreReference reference) {
    part(value, reference.store());
    part(value, reference.path());
  }

  private static void part(StringBuilder value, String part) {
    value.append(part.length()).append(':').append(part);
  }

  static String digest(List<File> files) {
    StringBuilder source = new StringBuilder();
    files.stream()
        .sorted(Comparator.comparing(File::path))
        .forEach(
            file ->
                source
                    .append(file.path().length())
                    .append(':')
                    .append(file.path())
                    .append(':')
                    .append(file.text().getBytes(StandardCharsets.UTF_8).length)
                    .append(':')
                    .append(file.text()));
    return hash(source.toString());
  }

  private static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
