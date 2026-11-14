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
 *
 * <p>Also creates project Managers' schedule-only revisions ({@link ApplicationScheduleRevisions},
 * ADR 0068), which share staging, validation and the store's head fence with deployments.
 */
public final class SourceApplicationDeployments
    implements ApplicationDeployments, ApplicationScheduleRevisions {
  public static final int MAX_FILES = 128, MAX_FILE_BYTES = 65536, MAX_TOTAL_BYTES = 131072;
  private final ApplicationDeploymentStore store;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final FileStores fileStores;
  private final ApplicationPackageValidator validator;

  private final ApplicationIdentities identities;

  /** Resolves a schedule source's project id; only schedule-only revisions need it. */
  private final ProjectNames names;

  public SourceApplicationDeployments(
      ApplicationDeploymentStore store,
      ProjectMembers members,
      ProjectWorkspaces projects,
      FileStores fileStores,
      ApplicationPackageValidator validator) {
    this(store, members, projects, fileStores, validator, null);
  }

  public SourceApplicationDeployments(
      ApplicationDeploymentStore store,
      ProjectMembers members,
      ProjectWorkspaces projects,
      FileStores fileStores,
      ApplicationPackageValidator validator,
      ApplicationIdentities identities) {
    this(store, members, projects, fileStores, validator, identities, null);
  }

  public SourceApplicationDeployments(
      ApplicationDeploymentStore store,
      ProjectMembers members,
      ProjectWorkspaces projects,
      FileStores fileStores,
      ApplicationPackageValidator validator,
      ApplicationIdentities identities,
      ProjectNames names) {
    this.names = names;
    this.identities = identities;
    this.store = store;
    this.members = members;
    this.projects = projects;
    this.fileStores = fileStores;
    this.validator = validator;
  }

  private void require(String account, String project) {
    if (!members.isServerAdmin(account))
      throw new CallerFault("Application deployment requires a server administrator");
    ordinary(project);
  }

  private static void ordinary(String project) {
    if (project == null
        || project.isBlank()
        || !project.equals(project.strip())
        || project.length() > 512
        || project.codePoints().anyMatch(Character::isISOControl)
        || project.startsWith("personal:")
        || ClientProjects.privateProject(project)
        // ADR 0042, list (c): a child's source area is allocated and owned by its container under
        // plowshare.containment.root. Admitting a deployment here would give an administrator a
        // second writer for that area and make the child an ordinary Application in its own right.
        || io.aeyer.plowshare.protocol.ContainedProjects.name(project))
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
    var manifest =
        request.files().stream()
            .filter(f -> f.path().equals("plowshare.json"))
            .findFirst()
            .orElseThrow()
            .text();
    var service = serviceDeclaration(manifest);
    List<File> files = request.files();
    if (service != null) {
      if (identities == null) throw new CallerFault("Application-owned identities are unavailable");
      var retainedIdentity = identities.status(account, request.project()).identity();
      String provisional =
          retainedIdentity == null ? "@service/" + UUID.randomUUID() : retainedIdentity.principal();
      // Validate all source before reserving authority. The provisional UUID is never persisted
      // as an identity; inheritance needs a syntactically valid principal during dry admission.
      files = withManifest(files, resolveServiceManifest(manifest, provisional));
    }
    // A new, unpredictable revision directory is never overwritten, including after crashes.
    try {
      Files.createDirectories(resolved.root().getParent());
      resolve(account, request.project(), destination, placement);
      Files.createDirectory(resolved.root());
      stage(request.project(), resolved.root(), files);
      WorkspaceApplicationPolicy.parse(
          files.stream()
              .filter(f -> f.path().equals("plowshare.json"))
              .findFirst()
              .orElseThrow()
              .text(),
          request.project());
      validator.validate(request.project(), resolved.root());
      if (service != null) {
        var binding = identities.reserve(account, request.project(), service);
        files =
            withManifest(
                request.files(), ApplicationServiceManifest.resolve(manifest, binding.principal()));
        validateFiles(files);
        Files.writeString(
            resolved.root().resolve("plowshare.json"),
            files.stream()
                .filter(f -> f.path().equals("plowshare.json"))
                .findFirst()
                .orElseThrow()
                .text(),
            StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS);
        WorkspaceApplicationPolicy.parse(
            files.stream()
                .filter(f -> f.path().equals("plowshare.json"))
                .findFirst()
                .orElseThrow()
                .text(),
            request.project());
        validator.validate(request.project(), resolved.root());
      }
      if (!resolve(account, request.project(), destination, placement).equals(resolved))
        throw new CallerFault("FileStore placement changed while staging the Application");
    } catch (IOException | IllegalArgumentException invalid) {
      throw new CallerFault("Application source could not be staged or its manifest is invalid");
    }
    var retained =
        new ApplicationDeploymentStore.Retained(
            new Release(revision, digest(files), files.size()), destination, placement);
    return store.commit(
        account,
        new ApplicationDeploymentStore.Mutation(
            request.project(),
            request.requestId(),
            request.expectedRevision(),
            fingerprint,
            retained,
            resolved.root(),
            true,
            ApplicationIdentitySource.read(resolved.root())));
  }

  /**
   * Writes every file into a freshly created revision directory. Each parent segment is created or
   * re-checked without following links, and each file is created new without following links, so no
   * write can land outside the staged revision or replace an existing file.
   */
  private void stage(String project, Path root, List<File> files) throws IOException {
    for (var file : files) {
      Path target = root.resolve(file.path());
      var fence = sourceFence(project, root);
      if (!fence.permits(target)) throw new CallerFault("Application source escaped staging");
      Path at = root;
      for (Path segment : root.relativize(target.getParent())) {
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
  }

  /**
   * {@inheritDoc}
   *
   * <p>Order: the name and size rules; the caller's current project management; the active head and
   * its retained release, whose files are read back and checked against the retained digest; the
   * existing-file rule; then staging into {@code <destination>/revisions/<new uuid>}, the manifest
   * parse and the package validator; then one commit under the head fence, with {@code
   * expectedRevision} the head read at the start. A deploy or rollback that lands in between makes
   * the commit refuse with the store's {@code Application revision changed} message; nothing is
   * retried.
   *
   * <p>The revisions directory's real path is taken before staging. The new directory is created
   * there without following links and re-checked against it before staging and before commit, so a
   * link swapped into the destination cannot redirect the revision outside it.
   *
   * <p>Idempotency: each call uses a fresh request id and receipt, so two saves are two revisions.
   * A definite refusal ({@link CallerFault} or {@link ArchiveRefusedException}, which roll the
   * transaction back) removes the staged directory. Any other commit failure has an unknown outcome
   * and keeps it, because it may already be the active head.
   */
  @Override
  public String revise(
      String caller, long projectId, String scheduleName, String text, boolean overwrite) {
    String name;
    try {
      name = io.aeyer.plowshare.protocol.ScheduledWork.identity(scheduleName, "file name");
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault(invalid.getMessage());
    }
    if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES)
      throw new CallerFault("Schedule file exceeds 64 KiB");
    if (names == null) throw new CallerFault("Application schedule revisions are unavailable");
    String project =
        names
            .nameForId(projectId)
            .orElseThrow(() -> new CallerFault("Choose a registered project for schedule files"));
    ordinary(project);
    if (io.aeyer.plowshare.server.auth.ServiceCredentials.principal(caller)
        || !members.mayManage(project, caller))
      throw new CallerFault("Saving an Application schedule requires a project Manager");
    UUID active = store.status(project).activeRevision();
    if (active == null) throw new CallerFault("Deploy the Application before saving its schedules");
    var previous = store.release(project, active);
    var current = place(project, previous.placement());
    List<File> retained = read(current.root());
    if (!digest(retained).equals(previous.release().digest())
        || retained.size() != previous.release().fileCount())
      throw new CallerFault("Retained Application source changed; deploy a new revision");
    String path = "schedules/" + name + ".json";
    boolean exists = retained.stream().anyMatch(file -> file.path().equals(path));
    if (exists && !overwrite) throw new CallerFault("A schedule named " + name + " already exists");
    List<File> files = new ArrayList<>();
    for (var file : retained) if (!file.path().equals(path)) files.add(file);
    files.add(new File(path, text));
    validateFiles(files);
    UUID revision = UUID.randomUUID();
    var destination = previous.destination();
    var placement =
        new ApplicationPlacement(
            new FileStoreReference(
                destination.store(), destination.path() + "/revisions/" + revision),
            previous.placement().writableAreas());
    var resolved = place(project, placement);
    Path root = resolved.root();
    Path revisions = current.root().getParent();
    if (revisions == null || !revisions.equals(root.getParent()))
      throw new CallerFault(
          "A schedule revision must stay inside the Application's existing destination");
    Path real;
    try {
      real = revisions.toRealPath();
    } catch (IOException unavailable) {
      throw new CallerFault("Retained Application source is unavailable");
    }
    ApplicationIdentitySource identity;
    boolean created = false;
    try {
      requireStaged(root, real, false);
      Files.createDirectory(root);
      created = true;
      requireStaged(root, real, true);
      stage(project, root, files);
      WorkspaceApplicationPolicy.parse(
          files.stream()
              .filter(file -> file.path().equals("plowshare.json"))
              .findFirst()
              .orElseThrow()
              .text(),
          project);
      validator.validate(project, root);
      if (!place(project, placement).equals(resolved))
        throw new CallerFault("FileStore placement changed while staging the Application");
      identity = ApplicationIdentitySource.read(root);
      requireStaged(root, real, true);
    } catch (IOException | IllegalArgumentException invalid) {
      var refused =
          new CallerFault("Application source could not be staged or its manifest is invalid");
      if (created) discard(root, refused);
      throw refused;
    } catch (RuntimeException refused) {
      if (created) discard(root, refused);
      throw refused;
    }
    String digest = digest(files);
    var mutation =
        new ApplicationDeploymentStore.Mutation(
            project,
            UUID.randomUUID(),
            active,
            hash("schedule-revision\0" + project + "\0" + active + "\0" + revision + "\0" + digest),
            new ApplicationDeploymentStore.Retained(
                new Release(revision, digest, files.size()), destination, placement),
            root,
            true,
            identity,
            true);
    try {
      store.commit(caller, mutation);
    } catch (CallerFault | ArchiveRefusedException refused) {
      discard(root, refused);
      throw refused;
    }
    return revision.toString();
  }

  /**
   * Refuses when the revisions directory no longer resolves to {@code revisions} (its real path
   * taken before staging), or when the staged directory is a link or resolves elsewhere.
   */
  private static void requireStaged(Path root, Path revisions, boolean exists) throws IOException {
    if (!root.getParent().toRealPath().equals(revisions))
      throw new CallerFault("Application destination changed while staging the revision");
    if (exists
        && (Files.isSymbolicLink(root)
            || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
            || !root.toRealPath().equals(revisions.resolve(root.getFileName()))))
      throw new CallerFault("Application staging source changed to a link");
  }

  /**
   * Removes a refused revision's staging, deepest first and without following links. A failure to
   * remove it is attached to the refusal rather than replacing it; the orphan is never activated.
   */
  private static void discard(Path root, RuntimeException refusal) {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    } catch (IOException | java.io.UncheckedIOException failed) {
      refusal.addSuppressed(failed);
    }
  }

  private static io.aeyer.plowshare.protocol.ApplicationIdentity.Declaration serviceDeclaration(
      String source) {
    try {
      return ApplicationServiceManifest.declaration(source).orElse(null);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Invalid Application service declaration");
    }
  }

  private static String resolveServiceManifest(String source, String principal) {
    try {
      return ApplicationServiceManifest.resolve(source, principal);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Application executionAccount conflicts with its owned identity");
    }
  }

  private static List<File> withManifest(List<File> files, String text) {
    return files.stream()
        .map(file -> file.path().equals("plowshare.json") ? new File(file.path(), text) : file)
        .toList();
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
    } catch (IOException | IllegalArgumentException invalid) {
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
            false,
            ApplicationIdentitySource.read(resolved.root())));
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
    return place(project, placement);
  }

  /**
   * Resolves a placement and applies the private-area and writable-area fences, without the
   * caller's FileStore authority. Schedule-only revisions use it directly: they stay inside the
   * destination the server already staged for this project.
   */
  private FileStores.Placement place(String project, ApplicationPlacement placement) {
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
