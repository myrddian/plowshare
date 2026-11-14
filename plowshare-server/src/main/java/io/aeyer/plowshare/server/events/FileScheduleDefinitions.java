package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.*;

/** Files are desired configuration; the existing database owns execution and next-fire state. */
public final class FileScheduleDefinitions implements ScheduleDefinitions {
  private final ScheduleDefinitionStore store;
  private final ScheduleFiles files;
  private final ProjectStore projects;
  private final ProjectMembers members;
  private final Authority authority;
  private final ScheduleControlAuthority controls;
  private final ApplicationProjects applications;
  private final ApplicationScheduleAudit audit;
  private final io.aeyer.plowshare.server.applications.ApplicationScheduleRevisions revisions;

  public FileScheduleDefinitions(
      ScheduleDefinitionStore store,
      ScheduleFiles files,
      ProjectStore projects,
      ProjectMembers members,
      Authority authority,
      ScheduleControlAuthority controls,
      ApplicationProjects applications,
      ApplicationScheduleAudit audit,
      io.aeyer.plowshare.server.applications.ApplicationScheduleRevisions revisions) {
    this.revisions = revisions;
    this.store = store;
    this.files = files;
    this.projects = projects;
    this.members = members;
    this.authority = authority;
    this.controls = controls;
    this.applications = applications;
    this.audit = audit;
  }

  /**
   * Saves one schedule file and returns its effective projection.
   *
   * <p>Workspace, global and ordinary project server sources register the caller as the folder's
   * owner and write through {@link ScheduleFiles#write}. A project server source whose folder is an
   * Application root (the {@link ScheduleFiles#applicationOwned} predicate) takes the Application
   * path of ADR 0068 instead; see {@link #saveApplication}.
   */
  public ScheduledWork.File save(String account, ScheduledWork.Save request) {
    var pending = pending(account, request.project(), request.source());
    // Every path requires a project Manager (or a server administrator for global folders). This
    // runs before the Application probe so an unauthorized caller learns nothing about the root.
    manage(pending);
    if (pending.project() != null && files.applicationOwned(pending))
      return saveApplication(account, request, pending);
    var source = store.register(account, pending.projectId(), pending.source());
    authority.validate(source, request.definition());
    CronSchedule.parse(request.definition().cron(), request.definition().zone())
        .nextAfter(Instant.now());
    files.write(
        source,
        request.name(),
        ScheduleDefinitionCodec.write(request.definition()),
        request.overwrite());
    // File IO finishes before the projection transaction. A crash here is recovered by
    // reconciliation;
    // an uncertain file mutation is never automatically replayed by a client.
    requireComplete(reconcile(source));
    // Reconciliation commits each file separately. This final keyed read is current rather than
    // reusing the pre-scan deletion snapshot or reloading every sibling definition.
    return store.snapshot(source, request.name()).orElseThrow().effective();
  }

  /**
   * A project Manager's save on an Application root (ADR 0068).
   *
   * <p>Order: the caller is already authorized as a current project Manager; resolve the project
   * type and the existing registered source, which is never created or reassigned here; require the
   * registered owner's execution access and validate the actions under that owner and parse the
   * cron; write; reconcile; audit. Nothing is written until every check has passed.
   *
   * <p>The write depends on the project type. A MANAGED project's save becomes a schedule-only
   * deployment revision ({@link
   * io.aeyer.plowshare.server.applications.ApplicationScheduleRevisions}), committed under the
   * deployment head fence, and its audit target carries {@code @<revision>}. Reconciliation then
   * reads the new revision, which is the project's workspace once the revision commits. Any other
   * Application root is written in place.
   *
   * <p>No transaction spans the file write and the projection. A crash after the write and before
   * reconciliation leaves a valid file that the poll reconciles on its next tick, exactly as an
   * ordinary save does. The audit row is written after reconciliation and before its result is
   * reported, so a written file is audited even when reconciliation then reports the folder
   * unavailable; an audit failure is reported to the caller, whose file has nonetheless landed.
   */
  private ScheduledWork.File saveApplication(
      String account, ScheduledWork.Save request, ScheduleDefinitionStore.Source pending) {
    var project =
        applications
            .find(pending.projectId())
            .orElseThrow(() -> new CallerFault("Choose a registered project for schedule files"));
    var source =
        store
            .registered(pending.projectId(), pending.source())
            .orElseThrow(
                () -> new CallerFault("No registered schedule source for this Application"));
    // Reconciliation suspends every file of an owner without execution access. Refuse first
    // rather than write a file that the same save would then report as unavailable.
    if (!members.mayWork(source.project(), source.account()))
      throw new CallerFault("The registered schedule owner no longer has execution access");
    authority.validate(source, request.definition());
    CronSchedule.parse(request.definition().cron(), request.definition().zone())
        .nextAfter(Instant.now());
    String text = ScheduleDefinitionCodec.write(request.definition());
    String target = project.name() + "/" + request.name();
    if (project.type() == ApplicationProjects.ProjectType.MANAGED)
      target +=
          "@"
              + revisions.revise(
                  account, pending.projectId(), request.name(), text, request.overwrite());
    else files.writeApplication(source, request.name(), text, request.overwrite());
    var scan = reconcile(source);
    audit.saved(account, target);
    requireComplete(scan);
    return store.snapshot(source, request.name()).orElseThrow().effective();
  }

  public List<ScheduledWork.File> sync(String account, ScheduledWork.Sync request) {
    var source = enroll(account, request.project(), request.source());
    requireComplete(reconcile(source));
    return store.files(source);
  }

  public List<ScheduledWork.File> list(String account) {
    return store.sources().stream()
        .filter(s -> s.account().equals(account))
        .filter(s -> s.project() == null || members.mayUse(s.project(), account))
        .flatMap(s -> store.files(s).stream())
        .toList();
  }

  public List<ScheduledWork.File> list(String account, String project) {
    requireRead(account, project);
    return store.sources().stream()
        .filter(s -> project.equals(s.project()))
        .flatMap(s -> store.files(s).stream())
        .toList();
  }

  public void requireRead(String account, String project) {
    if (project == null
        || project.isBlank()
        || !project.equals(project.strip())
        || project.length() > 256
        || project.codePoints().anyMatch(Character::isISOControl)
        || project.startsWith("personal:")
        || project.startsWith("Personal:")
        || io.aeyer.plowshare.server.archive.ClientProjects.privateProject(project)
        // ADR 0042, list (c): ADR 0020 gives a schedule operational authority over the server's
        // own scheduler, and nothing in this slice mediates that for a child. Task 5 refuses a
        // child declaring one; this refuses the inspection surface that would name a child's
        // project as a schedule source, so the two halves of the surface agree.
        || io.aeyer.plowshare.protocol.ContainedProjects.name(project))
      throw new CallerFault("Choose an ordinary project for schedule inspection");
    members.requireRole(project, account, io.aeyer.plowshare.server.archive.ProjectRole.VIEWER);
  }

  public List<ScheduledWork.File> page(String account, String project, SchedulePage page) {
    Objects.requireNonNull(page);
    if (project != null) requireRead(account, project);
    var sources = store.sourcesForInspection(account, project);
    if (project == null) {
      sources = sources.stream().filter(s -> s.account().equals(account)).toList();
      var candidates =
          sources.stream()
              .map(ScheduleDefinitionStore.Source::project)
              .filter(Objects::nonNull)
              .collect(java.util.stream.Collectors.toSet());
      Set<String> readable =
          candidates.isEmpty() ? Set.of() : members.readableProjects(candidates, account);
      sources =
          sources.stream()
              .filter(s -> s.project() == null || readable.contains(s.project()))
              .toList();
    }
    var selected = new ScheduleDefinitionStore.FileSelection(account, project, sources);
    var result = store.filesPage(selected, page);
    if (project != null) requireRead(account, project);
    else {
      // The source can move after its lightweight admission and before the cache's version
      // capture/load. Authorize the DTOs' actual projects, not their earlier source labels. This
      // disclosure batch is bounded by the page, regardless of the number of registered folders.
      var disclosedProjects =
          result.stream()
              .map(ScheduledWork.File::project)
              .filter(Objects::nonNull)
              .collect(java.util.stream.Collectors.toSet());
      if (!disclosedProjects.isEmpty()
          && !members.readableProjects(disclosedProjects, account).containsAll(disclosedProjects))
        throw new CallerFault("Schedule read authority changed; request the page again");
    }
    return result;
  }

  public void poll() {
    for (var source : store.sources()) reconcile(source);
  }

  public String executionSession(ScheduleDefinitionStore.Source source) {
    return files.executionSession(source);
  }

  public boolean requiresDefinition(String internal, String account) {
    return store.requiresDefinition(internal, account);
  }

  public Optional<ScheduledWork.File> managed(String internal, String account) {
    return store.managed(internal, account);
  }

  public Optional<ScheduleDefinitionStore.Source> sourceOf(String internal, String account) {
    return store.sourceOf(internal, account);
  }

  public boolean pause(String internal, boolean paused, String account) {
    var source = store.sourceOf(internal, account);
    if (source.isEmpty()) source = store.sourceOf(internal);
    if (source.isEmpty()) return false;
    boolean application = files.applicationOwned(source.get());
    if (application) controls.requireApplicationControl(source.get(), account);
    else if (!source.get().account().equals(account)) return false;
    // Operational authority precedes IO/reconciliation so unauthorized control cannot suspend
    // another account's projection. Registration and execution ownership are never transferred.
    requireComplete(reconcile(source.get()));
    var file =
        store
            .managed(internal, source.get().account())
            .orElseThrow(() -> new CallerFault("The schedule file was removed"));
    if (file.definition() == null || !file.status().equals("active"))
      throw new CallerFault("Repair this schedule file before changing it");
    var old = file.definition();
    if (application) {
      // Re-read permissions after source IO; revocation also applies to an existing connection.
      controls.requireApplicationControl(source.get(), account);
      authority.validate(source.get(), old);
      store.pause(source.get(), file.name(), old, paused, Instant.now());
      return true;
    }
    save(
        account,
        new ScheduledWork.Save(
            file.name(),
            source.get().project(),
            source.get().source(),
            new ScheduledWork(
                old.version(),
                old.cron(),
                old.zone(),
                paused,
                old.action(),
                old.target(),
                old.limits()),
            true));
    return true;
  }

  public boolean forget(String internal, String account) {
    var source = store.sourceOf(internal, account);
    if (source.isEmpty()) return false;
    manage(source.get());
    var file = store.managed(internal, account).orElseThrow();
    files.delete(source.get(), file.name());
    store.remove(source.get(), file.name());
    return true;
  }

  private ScheduleDefinitionStore.Source enroll(String account, String project, String source) {
    var pending = pending(account, project, source);
    manage(pending);
    return store.register(account, pending.projectId(), source);
  }

  /** The caller's unregistered view of a folder: validated identity, not yet authorized. */
  private ScheduleDefinitionStore.Source pending(String account, String project, String source) {
    if (project == null && "workspace".equals(source))
      throw new CallerFault("Workspace schedule files require a project");
    Long projectId = project == null ? null : projects.id(project);
    if (project != null && projectId == null)
      throw new CallerFault("Choose a registered project for schedule files");
    return new ScheduleDefinitionStore.Source(0, account, projectId, project, source);
  }

  private void manage(ScheduleDefinitionStore.Source source) {
    if (source.project() == null) {
      if (!members.isServerAdmin(source.account()))
        throw new CallerFault("Global schedules require server administrator authority");
    } else if (!members.mayManage(source.project(), source.account()))
      throw new CallerFault("Managing schedule definitions requires project MANAGER access");
  }

  private sealed interface ScanResult {
    record Complete() implements ScanResult {}

    record Unavailable(String reason) implements ScanResult {}
  }

  private static void requireComplete(ScanResult result) {
    if (result instanceof ScanResult.Unavailable unavailable)
      throw new CallerFault(unavailable.reason());
  }

  private ScanResult reconcile(ScheduleDefinitionStore.Source source) {
    var old = store.files(source);
    try {
      if (source.project() == null
          ? !members.isServerAdmin(source.account())
          : !members.mayWork(source.project(), source.account()))
        throw new CallerFault("The registered schedule owner no longer has execution access");
      var entries = files.read(source);
      Set<String> observed = new HashSet<>();
      for (var entry : entries)
        if (!observed.add(entry.name())) throw new CallerFault("Duplicate schedule file name");
      for (var entry : entries) {
        try {
          var definition = ScheduleDefinitionCodec.read(entry.text());
          authority.validate(source, definition);
          store.apply(source, entry.name(), definition, Instant.now());
        } catch (RuntimeException invalid) {
          store.reject(source, entry.name(), reason(invalid));
        }
      }
      // Only a complete, authorized scan establishes deletion. Offline or truncated scans never do.
      for (var prior : old)
        if (!observed.contains(prior.name())) store.remove(source, prior.name());
      return new ScanResult.Complete();
    } catch (RuntimeException unavailable) {
      for (var prior : old) store.reject(source, prior.name(), reason(unavailable));
      return new ScanResult.Unavailable(reason(unavailable));
    }
  }

  private static String reason(RuntimeException failure) {
    String reason =
        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    return reason.length() > 1000 ? reason.substring(0, 1000) : reason;
  }
}
