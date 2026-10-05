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

  public FileScheduleDefinitions(
      ScheduleDefinitionStore store,
      ScheduleFiles files,
      ProjectStore projects,
      ProjectMembers members,
      Authority authority) {
    this.store = store;
    this.files = files;
    this.projects = projects;
    this.members = members;
    this.authority = authority;
  }

  public ScheduledWork.File save(String account, ScheduledWork.Save request) {
    String project = request.project();
    var source = enroll(account, project, request.source());
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
    return store.files(source).stream()
        .filter(f -> f.name().equals(request.name()))
        .findFirst()
        .orElseThrow();
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
    if (source.isEmpty()) return false;
    requireComplete(reconcile(source.get()));
    var file =
        store
            .managed(internal, account)
            .orElseThrow(() -> new CallerFault("The schedule file was removed"));
    if (file.definition() == null || !file.status().equals("active"))
      throw new CallerFault("Repair this schedule file before changing it");
    var old = file.definition();
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
    if (project == null && source.equals("workspace"))
      throw new CallerFault("Workspace schedule files require a project");
    Long projectId = project == null ? null : projects.id(project);
    if (project != null && projectId == null)
      throw new CallerFault("Choose a registered project for schedule files");
    var pending = new ScheduleDefinitionStore.Source(0, account, projectId, project, source);
    manage(pending);
    return store.register(account, projectId, source);
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
