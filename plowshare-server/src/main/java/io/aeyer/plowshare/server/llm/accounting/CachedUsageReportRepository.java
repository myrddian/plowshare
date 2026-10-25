package io.aeyer.plowshare.server.llm.accounting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Usage.*;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import java.util.Objects;

/**
 * One versioned durable report serves equivalent requests and subscriptions for the same effective
 * principal/selection. Runtime journal/recorder health is added by UsageQueryService after reuse.
 * No subscription cursor, acknowledgement or delivery state is shared. Every cache hit still
 * validates authority and observation versions, including late commits and clock-only expiry.
 */
public final class CachedUsageReportRepository implements UsageReportRepository {
  private final UsageReportRepository source;
  private final ProjectionCache<Report> reports;
  private final ReadAdmission admission;
  private final ObjectMapper json;

  public CachedUsageReportRepository(
      UsageReportRepository source,
      ProjectionCache<Report> reports,
      ReadAdmission admission,
      ObjectMapper json) {
    this.source = Objects.requireNonNull(source);
    this.reports = Objects.requireNonNull(reports);
    this.admission = Objects.requireNonNull(admission);
    this.json = json.copy().findAndRegisterModules();
  }

  public Report report(String account, Resolved query) {
    if (account == null || account.isBlank())
      throw new io.aeyer.plowshare.server.faults.CallerFault("A signed-in account is required");
    Objects.requireNonNull(query);
    var key =
        new SnapshotCache.Key("report", ProjectionCache.fingerprint(account, canonical(query)));
    return reports.read(
        key,
        true,
        () ->
            admission.read(account, ReadAdmission.Work.REPORT, () -> source.report(account, query)),
        value -> 4096L + 4L * canonical(value).length());
  }

  public Audit calls(String account, Resolved query) {
    return admission.read(account, ReadAdmission.Work.REPORT, () -> source.calls(account, query));
  }

  public AttemptPage attempts(String account, Resolved query, String call, String cursor) {
    return admission.read(
        account, ReadAdmission.Work.REPORT, () -> source.attempts(account, query, call, cursor));
  }

  public void requireConversation(String account, String conversation) {
    source.requireConversation(account, conversation);
  }

  public UsageAttribution countOwner(String account, String conversation) {
    return source.countOwner(account, conversation);
  }

  private String canonical(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException invalid) {
      throw new IllegalArgumentException("Invalid usage projection", invalid);
    }
  }
}
