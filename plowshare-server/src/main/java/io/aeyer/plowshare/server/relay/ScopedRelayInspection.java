package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Membership gates project logs; a private Personal union still requires its exact owner. */
public final class ScopedRelayInspection implements RelayInspection {
  private static final Logger log = LoggerFactory.getLogger(ScopedRelayInspection.class);
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
    var key = new Relay.TopicKey(scope, query.topic());
    long after = query.after() == null ? 0 : Long.parseLong(query.after());
    int limit = query.limit() == null ? 100 : query.limit();
    RelayLogRepository.Snapshot read;
    try {
      read = logs.read(key, after, limit, account);
    } catch (RuntimeException failure) {
      // Keep failed selections visible at the normal production log level, even when Faults maps
      // an expected miss at DEBUG. Only validated selectors are logged, after scope authorization;
      // payloads, account identities and exception messages may carry private data. Rethrow the
      // original failure so the shared mapper still owns its status and unexpected stack trace.
      log.warn(
          "Relay inspection failed: operation=relay.log scope={} project={} topic={} after={} limit={} failure={}",
          RelayScopeCodec.write(scope),
          query.project(),
          key.name(),
          after,
          limit,
          failure.getClass().getSimpleName());
      throw failure;
    }
    var events = RelayPortEvents.bounded(read.events());
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
        events.isEmpty()
            ? Math.max(read.after(), read.topic().expiredThrough())
            : Long.parseLong(events.getLast().position());
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
