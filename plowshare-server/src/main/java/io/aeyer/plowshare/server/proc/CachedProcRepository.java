package io.aeyer.plowshare.server.proc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import java.util.List;
import java.util.Objects;

/**
 * Shares immutable queue snapshots across feeds/sockets. The key includes the actual principal and
 * complete typed selection. Current durable authority, corpus, transcript, search and
 * processing-queue versions fence every hit. Runtime pool occupancy is attached by ProcAudit after
 * this read. Report admission is inside the elected loader, so followers never retain a report slot
 * or a database lease. Uncached/recovery reads retain the same budget and repository authority
 * checks.
 */
public final class CachedProcRepository implements ProcRepository {
  private final ProcRepository source;
  private final ProjectionCache<ProcViews.Queues> queues;
  private final ReadAdmission admission;
  private final ObjectMapper json;

  public CachedProcRepository(
      ProcRepository source,
      ProjectionCache<ProcViews.Queues> queues,
      ReadAdmission admission,
      ObjectMapper json) {
    this.source = Objects.requireNonNull(source);
    this.queues = Objects.requireNonNull(queues);
    this.admission = Objects.requireNonNull(admission);
    this.json = json.copy().findAndRegisterModules();
  }

  public ProcViews.Queues queues(String account, ProcQuery query) {
    if (account == null || account.isBlank())
      throw new io.aeyer.plowshare.server.faults.CallerFault("A signed-in account is required");
    Objects.requireNonNull(query);
    var key =
        new SnapshotCache.Key("queues", ProjectionCache.fingerprint(account, canonical(query)));
    return queues.read(
        key,
        true,
        () ->
            admission.read(account, ReadAdmission.Work.REPORT, () -> source.queues(account, query)),
        value -> 4096L + 4L * canonical(value).length());
  }

  public ProcViews.Conversations conversations(String account, ProcQuery query, Live live) {
    return admission.read(
        account, ReadAdmission.Work.REPORT, () -> source.conversations(account, query, live));
  }

  public ProcViews.Calls calls(String account, ProcQuery query) {
    return admission.read(account, ReadAdmission.Work.REPORT, () -> source.calls(account, query));
  }

  public List<ProcViews.Tool> tools(String account, ProcQuery query) {
    return admission.read(account, ReadAdmission.Work.REPORT, () -> source.tools(account, query));
  }

  private String canonical(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException invalid) {
      throw new IllegalArgumentException("Invalid PROC projection", invalid);
    }
  }
}
