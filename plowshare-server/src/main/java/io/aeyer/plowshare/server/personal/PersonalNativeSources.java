package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.server.agents.EventRuns;
import io.aeyer.plowshare.server.archive.ArchiveRefusedException;
import io.aeyer.plowshare.server.archive.JobLog;
import io.aeyer.plowshare.server.archive.JobRecord;
import io.aeyer.plowshare.server.board.BoardMessage;
import io.aeyer.plowshare.server.board.BoardRecords;
import io.aeyer.plowshare.server.board.BoardTopic;
import io.aeyer.plowshare.server.events.ApplicationInbox;
import io.aeyer.plowshare.server.events.InboxItem;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.information.InformationReadAccess;
import io.aeyer.plowshare.server.orchestrations.OrchestrationRecord;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore;
import io.aeyer.plowshare.server.personal.PersonalSubscriptionStore.Binding;
import java.time.Instant;
import java.util.UUID;

/** Resolves native notices through the authorities that own their actual records. */
final class PersonalNativeSources {
  static final String APPLICATION_TOPIC = "application.inbox.item.added";
  static final String ATTENTION_TOPIC = "mailbox.application.item.added";
  static final String JOB_TOPIC = "job.ended";
  static final String ORCHESTRATION_TOPIC = "orchestration.ended";
  static final String BOARD_POST_TOPIC = "board.message.posted";

  /** Only references are queued. The bounded body is read at dispatch under live authority. */
  record Reference(String event, String subject, String state, String original, String at) {}

  record InformationReference(String event, UUID revision, long generation, String at) {}

  sealed interface Evidence
      permits ApplicationResult, Ending, BoardPublication, InformationFinalised {}

  record ApplicationResult(
      String kind,
      String subscription,
      String project,
      String copy,
      String event,
      String item,
      String itemKind,
      String ending,
      String report)
      implements Evidence {}

  record Ending(
      String kind,
      String subscription,
      String project,
      String event,
      String id,
      String definition,
      String state,
      String endedAt)
      implements Evidence {}

  record BoardPublication(
      String kind,
      String subscription,
      String project,
      String event,
      String topic,
      String message,
      String title,
      String body,
      String occurredAt)
      implements Evidence {}

  record InformationFinalised(
      String kind,
      String subscription,
      String project,
      String event,
      UUID revision,
      long generation,
      String occurredAt)
      implements Evidence {}

  record Input(EventRuns.SubscriptionSource source, Evidence evidence, Runnable requireRecord) {}

  private final ApplicationInbox applications;
  private final InboxStore inbox;
  private final JobLog jobs;
  private final OrchestrationStore orchestrations;
  private final InformationJobs information;
  private final BoardRecords board;
  private final InformationAccess informationAccess;
  private final InformationReadAccess readable;

  PersonalNativeSources(
      ApplicationInbox applications,
      InboxStore inbox,
      JobLog jobs,
      OrchestrationStore orchestrations,
      InformationJobs information,
      BoardRecords board,
      InformationAccess informationAccess,
      InformationReadAccess readable) {
    this.applications = applications;
    this.inbox = inbox;
    this.jobs = jobs;
    this.orchestrations = orchestrations;
    this.information = information;
    this.board = board;
    this.informationAccess = informationAccess;
    this.readable = readable;
  }

  long applicationProject(String owner, String project) {
    try {
      return applications.subscriptionProject(owner, project);
    } catch (ArchiveRefusedException denied) {
      throw new CallerFault("The Application inbox is unavailable to this account");
    }
  }

  Input read(Binding binding, Reference reference) {
    return switch (binding.spec().sourceType()) {
      case APPLICATION_INBOX, ATTENTION_COPY -> application(binding, reference);
      case BOARD -> board(binding, reference);
      case JOB -> {
        var record = job(binding, reference);
        yield new Input(
            new EventRuns.LogSource(record.conversation(), binding.spec().relayTopic().projectId()),
            new Ending(
                JOB_TOPIC,
                binding.key().name(),
                binding.spec().sourceProject(),
                reference.event(),
                record.id(),
                record.agent(),
                record.ending().name(),
                reference.at()),
            () -> job(binding, reference));
      }
      case ORCHESTRATION -> {
        var record = orchestration(binding, reference);
        yield new Input(
            new EventRuns.LogSource(
                record.conductorConversation(), binding.spec().relayTopic().projectId()),
            new Ending(
                ORCHESTRATION_TOPIC,
                binding.key().name(),
                binding.spec().sourceProject(),
                reference.event(),
                record.id(),
                record.definitionName(),
                record.state().wire(),
                reference.at()),
            () -> orchestration(binding, reference));
      }
      default -> throw new IllegalArgumentException("A built-in subscription source is required");
    };
  }

  Input information(Binding binding, InformationReference reference) {
    String project = binding.spec().sourceProject();
    var selection =
        new InformationContext.Selection(InformationContext.Scope.PROJECT, project, false);
    var context = informationAccess.resolve(binding.owner(), selection);
    readable.requireReadable(context, reference.revision());
    return new Input(
        new EventRuns.RevisionSource(
            binding.spec().relayTopic().projectId(), context, reference.revision()),
        new InformationFinalised(
            "information.report.finalised",
            binding.key().name(),
            project,
            reference.event(),
            reference.revision(),
            reference.generation(),
            reference.at()),
        () -> readable.requireReadable(context, reference.revision()));
  }

  private Input board(Binding binding, Reference reference) {
    String event = binding.spec().relayTopic().name();
    boolean post = BOARD_POST_TOPIC.equals(event);
    var topic = boardTopic(binding, reference, post);
    var message =
        board
            .message(post ? reference.subject() : reference.original())
            .filter(row -> topic.id().equals(row.topic()))
            .filter(
                row ->
                    post
                        ? reference.state().equals(row.kind())
                            && Instant.parse(reference.at()).equals(row.postedAt())
                        : BoardMessage.RESOLUTION.equals(row.kind()))
            .orElseThrow(() -> new NotFoundFault("The selected Board message is unavailable"));
    return new Input(
        new EventRuns.ProjectSource(binding.spec().relayTopic().projectId()),
        new BoardPublication(
            event,
            binding.key().name(),
            binding.spec().sourceProject(),
            reference.event(),
            topic.id(),
            message.id(),
            message.title(),
            message.body(),
            reference.at()),
        () -> boardTopic(binding, reference, post));
  }

  private BoardTopic boardTopic(Binding binding, Reference reference, boolean post) {
    return board
        .topic(post ? reference.original() : reference.subject())
        .filter(topic -> binding.spec().sourceProject().equals(topic.project()))
        .filter(topic -> post || reference.original().equals(topic.resolution()))
        .orElseThrow(() -> new NotFoundFault("The selected Board topic is unavailable"));
  }

  void requireApplicationAlert(Binding binding, Reference reference) {
    var spec = binding.spec();
    try {
      boolean visible =
          spec.attentionProject() != null
              ? inbox.canSeeAttentionResult(
                  binding.owner(),
                  spec.attentionProject(),
                  reference.subject(),
                  reference.original(),
                  reference.state())
              : applications.canSeeSubscriptionItem(
                  binding.owner(),
                  spec.sourceProject(),
                  spec.relayTopic().projectId(),
                  reference.subject(),
                  reference.state());
      if (!visible) throw new NotFoundFault("The selected inbox item is unavailable");
    } catch (ArchiveRefusedException denied) {
      throw new CallerFault("The Application inbox is unavailable to this account");
    }
  }

  private Input application(Binding binding, Reference reference) {
    boolean attention = binding.spec().attentionProject() != null;
    var project = binding.spec().sourceProject();
    long sourceProject =
        attention ? binding.spec().attentionProject() : binding.spec().relayTopic().projectId();
    InboxItem item;
    try {
      item =
          (attention
                  ? inbox.attentionResult(
                      binding.owner(), sourceProject, reference.subject(), reference.original())
                  : applications.subscriptionItem(
                      binding.owner(), project, sourceProject, reference.subject()))
              .filter(row -> row.kind().equals(reference.state()))
              .orElseThrow(() -> new NotFoundFault("The selected inbox item is unavailable"));
    } catch (ArchiveRefusedException denied) {
      throw new CallerFault("The Application inbox is unavailable to this account");
    }
    return new Input(
        attention ? new EventRuns.PersonalSource() : new EventRuns.ProjectSource(sourceProject),
        new ApplicationResult(
            "application.inbox.result",
            binding.key().name(),
            project,
            attention ? "attention" : "project",
            reference.event(),
            item.id(),
            item.kind(),
            item.ending(),
            item.answer()),
        () -> {});
  }

  private JobRecord job(Binding binding, Reference reference) {
    var record =
        jobs.find(reference.subject())
            .filter(
                row ->
                    binding.spec().sourceProject().equals(row.home().project())
                        && row.ended()
                        && row.ending().name().equals(reference.state())
                        && row.endedAt().equals(Instant.parse(reference.at()))
                        && row.conversation() != null)
            .orElseThrow(() -> new NotFoundFault("The selected job ending is unavailable"));
    information.require(record.id(), binding.owner());
    information.requireLog(record.conversation(), binding.owner());
    return record;
  }

  private OrchestrationRecord orchestration(Binding binding, Reference reference) {
    var record =
        orchestrations
            .find(reference.subject())
            .filter(
                row ->
                    binding.owner().equals(row.callerHandle())
                        && binding.spec().sourceProject().equals(row.project())
                        && row.state().terminal()
                        && row.state().wire().equals(reference.state())
                        && Instant.parse(reference.at()).equals(row.endedAt()))
            .orElseThrow(
                () -> new NotFoundFault("The selected orchestration ending is unavailable"));
    information.requireLog(record.id(), binding.owner());
    information.requireLog(record.conductorConversation(), binding.owner());
    return record;
  }
}
