package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.events.ApplicationInboxStore.Notice;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.orchestrations.OrchestrationRecord;
import io.aeyer.plowshare.server.orchestrations.OrchestrationState;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Application output is shared with current project readers. Personal copies go only to current
 * human Managers. This publishes output, never private log access or permission to answer a
 * question.
 */
public final class ApplicationInboxes implements ApplicationInbox {
  private Duration questionRetry = EventsProperties.DEFAULT_QUESTION_ALERT_RETRY;

  /** Use the same configured human-question retry interval as the personal Inbox. */
  public void useQuestionRetry(Duration retry) {
    if (retry == null || retry.isZero() || retry.isNegative())
      throw new IllegalArgumentException("Positive question retry interval required");
    this.questionRetry = retry;
  }

  private record Destination(
      long id, String project, Optional<ApplicationInboxSettings> settings) {}

  private final ApplicationPolicy policy;
  private final ProjectMembers members;
  private final ApplicationInboxStore store;
  private final AccountPushes pushes;
  private final InboxStore personal;
  private final Supplier<Instant> clock;

  public ApplicationInboxes(
      ApplicationPolicy policy,
      ProjectMembers members,
      ApplicationInboxStore store,
      AccountPushes pushes,
      InboxStore personal,
      Supplier<Instant> clock) {
    this.policy = policy;
    this.members = members;
    this.store = store;
    this.pushes = pushes;
    this.personal = personal;
    this.clock = clock;
  }

  private Optional<Destination> destination(String project) {
    if (project == null) return Optional.empty();
    var source = policy.source(project);
    if (source.boundary().kind() == ApplicationPolicy.Kind.EXTERNAL) return Optional.empty();
    if (source.boundary().kind() != ApplicationPolicy.Kind.APPLICATION
        || source.workspace().isEmpty())
      throw new CallerFault("Application inbox source is unavailable");
    // Application ownership must not depend on upgrading an installed manifest. The optional
    // policy selects successful results that need attention; it never selects the inbox owner.
    var settings = ApplicationInboxSettings.read(source.workspace().orElseThrow().workspace());
    return Optional.of(
        new Destination(
            store
                .project(project)
                .orElseThrow(() -> new CallerFault("Application inbox project is unavailable")),
            project,
            settings));
  }

  private Destination authorized(String account, String project, ProjectRole role) {
    try {
      ScheduledWork.identity(project, "application project");
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Choose an ordinary Application project name");
    }
    members.requireRole(project, account, role);
    return destination(project)
        .orElseThrow(() -> new CallerFault("Choose an Application project for its inbox"));
  }

  public InboxStore.Page page(
      String account, String project, boolean unread, int offset, int limit) {
    var destination = authorized(account, project, ProjectRole.VIEWER);
    var result = store.page(destination.id(), unread, offset, limit);
    members.requireRole(project, account, ProjectRole.VIEWER);
    return result;
  }

  public Acknowledged read(String account, String project, List<String> items) {
    var destination = authorized(account, project, ProjectRole.MANAGER);
    int marked = store.read(destination.id(), items, clock.get());
    // The new count is a fresh lightweight projection after the acknowledgement, using the
    // same authorized destination rather than resolving source and membership a second time.
    return new Acknowledged(marked, store.unread(destination.id()));
  }

  /** Public producer path: one request UUID, no caller-selected account recipients. */
  public void publish(String account, String project, UUID request, String kind, String text) {
    if (request == null
        || kind == null
        || !List.of("finding", "failure", "attention").contains(kind))
      throw new CallerFault("Choose a request UUID and finding, failure or attention kind");
    var destination = authorized(account, project, ProjectRole.CONTRIBUTOR);
    append(
        destination,
        "publication:" + account + ":" + request,
        new Notice(account, kind, null, null, text, null, true));
  }

  public boolean published(String account, String project, UUID request) {
    if (request == null) throw new CallerFault("A publication request UUID is required");
    return store.published(
        authorized(account, project, ProjectRole.CONTRIBUTOR).id(), account, request);
  }

  private void append(Destination destination, String key, Notice notice) {
    List<String> recipients =
        notice.attention() ? members.managers(destination.project()) : List.of();
    // Repository transaction commits both ownership and personal copies. Pushes are hints after
    // commit.
    var inserted = store.append(destination.id(), key, notice, recipients, clock.get());
    if (notice.about() != null && notice.kind().equals("approval")) {
      for (String manager : recipients) {
        if (store.claimQuestionAlert(destination.id(), key, manager, clock.get(), questionRetry))
          pushes.push(manager, Inbox.changed(personal.unread(manager)));
      }
    } else {
      for (String manager : inserted) pushes.push(manager, Inbox.changed(personal.unread(manager)));
    }
  }

  @Override
  public boolean scheduled(
      TriggerRecord trigger, FiringRecord firing, String conversation, Outcome outcome) {
    var destination = destination(trigger.project());
    if (destination.isEmpty()) return false;
    // Scheduler acceptance and ordinary successful output stay with the application.
    // Finding workflows publish their completion separately; failures always escalate.
    boolean attention = outcome.ending() != Outcome.Ending.ANSWERED;
    append(
        destination.get(),
        "firing:" + firing.id(),
        new Notice(
            trigger.definedBy(),
            conversation == null ? "failure" : "run",
            conversation,
            outcome.ending().name(),
            outcome.text(),
            null,
            attention));
    return true;
  }

  @Override
  public boolean orchestration(OrchestrationRecord run, String text, String about) {
    var project = store.backgroundProject(run.conductorConversation());
    if (project.isEmpty()) return false;
    var destination = destination(project.get());
    if (destination.isEmpty()) return false;
    boolean attention =
        about != null
            || run.state() != OrchestrationState.FINISHED
            || destination
                .get()
                .settings()
                .map(settings -> settings.attentionOrchestrations().contains(run.definitionName()))
                // Older Applications have no policy. Keep model workflow results visible while
                // treating successful native-only bookkeeping as routine. Inspect the pinned
                // declaration, never model prose, a workflow name or a later edited definition.
                .orElseGet(() -> !ScriptProgram.nativeOnly(run.definitionSource()));
    append(
        destination.get(),
        about == null ? "orchestration:" + run.id() + ":" + run.endedAt() : about,
        new Notice(
            run.callerHandle(),
            "orchestration",
            run.conductorConversation(),
            about == null ? run.state().wire() : null,
            about == null
                ? text
                : "The orchestration '"
                    + run.definitionName()
                    + "' (id "
                    + run.id()
                    + ") needs your attention.\n\n"
                    + text,
            about,
            attention));
    return true;
  }

  /**
   * Managers may decide published background questions without inheriting the executor identity.
   */
  public boolean manages(OrchestrationRecord run, String account) {
    return store
        .backgroundProject(run.conductorConversation())
        .filter(project -> destination(project).isPresent() && members.mayManage(project, account))
        .isPresent();
  }

  public boolean managesQuestion(String conversation, String source, String account) {
    return store
        .backgroundProject(source)
        .filter(project -> destination(project).isPresent() && members.mayManage(project, account))
        .isPresent();
  }

  @Override
  public boolean question(
      String conversation, String source, String author, String kind, String text, String about) {
    var project = store.backgroundProject(source);
    if (project.isEmpty()) return false;
    var destination = destination(project.get());
    if (destination.isEmpty()) return false;
    append(destination.get(), about, new Notice(author, kind, source, null, text, about, true));
    return true;
  }
}
