package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;

/** Membership gates project logs; a private Personal union still requires its exact owner. */
public final class ScopedRelayInspection implements RelayInspection {
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final RelayLogRepository logs;

  public ScopedRelayInspection(
      ProjectMembers members, ProjectWorkspaces projects, RelayLogRepository logs) {
    this.members = Objects.requireNonNull(members);
    this.projects = Objects.requireNonNull(projects);
    this.logs = Objects.requireNonNull(logs);
  }

  private Relay.Scope require(String account, String project, boolean system) {
    RelayValues.identity(account, "account");
    if (system) {
      if (!members.isServerAdmin(account))
        throw new CallerFault("System Relay logs require server administration access");
      return Relay.SystemScope.SERVER;
    }
    var owner = projects.personalOwner(project);
    if (owner.isPresent() && !owner.get().equals(account) || !members.mayUse(project, account))
      throw new CallerFault("Relay project log is unavailable to this account");
    var id = projects.id(project);
    if (id == null) throw new CallerFault("Relay project log is unavailable to this account");
    return new Relay.ProjectScope(id);
  }

  public RelayLog.Topics topics(String account, RelayLog.TopicsQuery query) {
    var scope = require(account, query.project(), query.system());
    return new RelayLog.Topics(
        new RelayLog.Scope(query.project(), query.system()),
        logs.topics(scope, query.limit() == null ? 100 : query.limit()).stream()
            .map(ScopedRelayInspection::topic)
            .toList());
  }

  public RelayLog.Page log(String account, RelayLog.Query query) {
    var scope = require(account, query.project(), query.system());
    var read =
        logs.read(
            new Relay.TopicKey(scope, query.topic()),
            query.after() == null ? 0 : Long.parseLong(query.after()),
            query.limit() == null ? 100 : query.limit(),
            account);
    var events =
        read.events().stream()
            .map(
                publication -> {
                  var draft = publication.event();
                  var payload = draft.payload();
                  var encoded =
                      new RelayLog.Payload(
                          payload.kind().name(),
                          payload instanceof RelayPayload.Text text ? text.text() : null,
                          payload instanceof RelayPayload.ScheduleDue due ? due.schedule() : null,
                          payload instanceof RelayPayload.ScheduleDue due ? due.emits() : null,
                          payload instanceof RelayPayload.ScheduleDue due ? due.fireAt() : null,
                          payload instanceof RelayPayload.Lifecycle change
                              ? new RelayLog.Lifecycle(
                                  change.source(),
                                  change.subject(),
                                  change.state(),
                                  change.context(),
                                  change.related())
                              : null,
                          payload instanceof RelayPayload.WakeRequested wake
                              ? new RelayLog.Wake(wake.firing(), wake.target(), wake.type().name())
                              : null);
                  return new RelayLog.Event(
                      Long.toString(publication.position()),
                      draft.eventId(),
                      draft.publisher(),
                      draft.occurredAt(),
                      publication.publishedAt(),
                      draft.correlationId(),
                      draft.causationId(),
                      encoded,
                      draft.causation());
                })
            .toList();
    var subscribers =
        read.subscribers().stream()
            .map(
                sub ->
                    new RelayLog.Subscriber(
                        sub.key().subscriber(),
                        Long.toString(sub.seenThrough()),
                        sub.seenAt(),
                        sub.seenThrough() < read.topic().expiredThrough()
                            ? Long.toString(read.topic().expiredThrough())
                            : null,
                        sub.generation() == null ? null : sub.generation().toString()))
            .toList();
    var branches =
        read.branches().stream()
            .map(
                branch ->
                    new RelayLog.Branch(
                        branch.id().toString(),
                        Long.toString(branch.position()),
                        branch.subscriber(),
                        branch.name(),
                        branch.receiver(),
                        branch.state().name(),
                        Long.toString(branch.fence()),
                        branch.updatedAt(),
                        branch.failure(),
                        branch.receipt() == null ? null : branch.receipt().namespace(),
                        branch.receipt() == null ? null : branch.receipt().id(),
                        branch.conversation(),
                        branch.conversationProject(),
                        branch.routingHash(),
                        branch.handlerHash()))
            .toList();
    long next =
        read.events().isEmpty()
            ? Math.max(read.after(), read.topic().expiredThrough())
            : read.events().getLast().position();
    return new RelayLog.Page(
        new RelayLog.Scope(query.project(), query.system()),
        topic(read.topic()),
        Long.toString(read.after()),
        Long.toString(next),
        read.after() < read.topic().expiredThrough()
            ? Long.toString(read.topic().expiredThrough())
            : null,
        events,
        subscribers,
        branches,
        read.recoveries().stream()
            .map(
                recovery ->
                    new RelayLog.Recovery(
                        recovery.subscriber(),
                        Long.toString(recovery.expiredThrough()),
                        Long.toString(recovery.pendingInbox()),
                        recovery.recoveredAt()))
            .toList());
  }

  private static RelayLog.Topic topic(Relay.Topic topic) {
    return new RelayLog.Topic(
        topic.key().name(),
        topic.kind().name(),
        Long.toString(topic.policy().retention().getSeconds()),
        topic.policy().maxRecords() == null ? null : topic.policy().maxRecords().toString(),
        Long.toString(topic.lastPosition()),
        Long.toString(topic.expiredThrough()),
        topic.generation() == null ? null : topic.generation().toString());
  }
}
