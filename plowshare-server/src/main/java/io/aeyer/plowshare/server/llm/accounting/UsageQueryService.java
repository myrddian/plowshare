package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.protocol.Usage.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Validates report selection and combines a durable read snapshot with current capture health. */
@Service
public class UsageQueryService implements UsageReports {
  public static final Set<String> REPORTS =
      Set.of(
          "usage.conversation",
          "usage.project",
          "usage.agent",
          "usage.run",
          "usage.orchestration",
          "usage.models",
          "usage.pools");

  private org.springframework.beans.factory.ObjectProvider<AccountingJournal> journals;
  private org.springframework.beans.factory.ObjectProvider<AccountingRecorder> recorders;
  private org.springframework.beans.factory.ObjectProvider<AccountingProjector> projectors;

  @org.springframework.beans.factory.annotation.Autowired
  public void observe(
      org.springframework.beans.factory.ObjectProvider<AccountingJournal> j,
      org.springframework.beans.factory.ObjectProvider<AccountingRecorder> r,
      org.springframework.beans.factory.ObjectProvider<AccountingProjector> p) {
    journals = j;
    recorders = r;
    projectors = p;
  }

  private final UsageReportRepository repository;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public UsageQueryService(UsageReportRepository repository) {
    this(repository, Clock.systemUTC());
  }

  public UsageQueryService(UsageReportRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository);
    this.clock = Objects.requireNonNull(clock);
  }

  public Resolved resolve(String type, Filter f) {
    return UsageFilters.resolve(type, f, clock);
  }

  public Report report(String account, Resolved resolved) {
    var result = repository.report(account, resolved);
    return new Report(
        result.filters(),
        result.totals(),
        result.groups(),
        result.cursor(),
        health(result.health()));
  }

  public Audit calls(String account, Resolved resolved) {
    var result = repository.calls(account, resolved);
    return new Audit(result.filters(), result.calls(), result.cursor(), health(result.health()));
  }

  public AttemptPage attempts(String account, Resolved resolved, String call, String cursor) {
    var result = repository.attempts(account, resolved, call, cursor);
    return new AttemptPage(
        result.filters(),
        result.call(),
        result.attempts(),
        result.cursor(),
        health(result.health()));
  }

  public void requireConversation(String account, String conversation) {
    repository.requireConversation(account, conversation);
  }

  public UsageAttribution countOwner(String account, String conversation) {
    return repository.countOwner(account, conversation);
  }

  private Health health(Health persisted) {
    AccountingJournal journal = journals == null ? null : journals.getIfAvailable();
    AccountingRecorder recorder = recorders == null ? null : recorders.getIfAvailable();
    AccountingProjector projector = projectors == null ? null : projectors.getIfAvailable();
    var j = journal == null ? null : journal.health();
    var r = recorder == null ? null : recorder.health();
    return new Health(
        persisted.trackingStartedAt(),
        persisted.lastProjectedAt(),
        persisted.watermark(),
        clock.instant(),
        "not_imported",
        journal != null,
        j == null ? null : Long.toString(j.pendingEvents()),
        j == null ? null : Long.toString(j.bytes()),
        j == null ? null : Long.toString(j.maxBytes()),
        j == null ? null : j.problem().name(),
        j == null
            ? null
            : Long.toString(
                j.oldestPendingAt() == null
                    ? 0
                    : Math.max(
                        0, Duration.between(j.oldestPendingAt(), clock.instant()).toMillis())),
        j == null ? null : j.oldestPendingAt(),
        r == null ? null : r.bufferedTerminalEvents(),
        r == null ? null : Long.toString(r.lostTerminalEvents()),
        r == null ? null : r.projectionConflict(),
        projector == null ? null : projector.health().problem().name());
  }
}
