package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/** Explicit bounded passes share distributed subscription ownership with automatic workers. */
public final class ProjectRelayProcessing implements RelayProcessing {
  private final ProjectWorkspaces projects;
  private final ProjectMembers members;
  private final RelaySubscriptionWork work;

  public ProjectRelayProcessing(
      ProjectWorkspaces projects, ProjectMembers members, RelaySubscriptionWork work) {
    this.projects = Objects.requireNonNull(projects);
    this.members = Objects.requireNonNull(members);
    this.work = Objects.requireNonNull(work);
  }

  @Override
  public RelayLog.Processed process(String account, RelayLog.Process query) {
    RelayValues.identity(account, "account");
    if (!members.mayWork(query.project(), account))
      throw new CallerFault("Relay processing requires project contributor access");
    var id = projects.id(query.project());
    if (id == null || id < 1) throw new CallerFault("Relay project is unavailable");
    var access = new RelayProjectFiles.Access(account, query.project(), id);
    var subscriptions = work.subscriptions(access);
    int admitted = 0, dispatched = 0, limit = query.limit() == null ? 32 : query.limit();
    var gaps = new ArrayList<RelayLog.Gap>();
    String worker = "request:" + UUID.randomUUID();
    for (var key : subscriptions) {
      var result = work.process(access, key, worker, admitted < limit ? 1 : 0, limit - dispatched);
      admitted += result.admitted();
      dispatched += result.dispatched();
      if (result.gap() != null)
        gaps.add(
            new RelayLog.Gap(
                key.topic().name(),
                key.subscriber(),
                Long.toString(result.gap().throughInclusive())));
    }
    return new RelayLog.Processed(query.project(), admitted, dispatched, gaps);
  }
}
