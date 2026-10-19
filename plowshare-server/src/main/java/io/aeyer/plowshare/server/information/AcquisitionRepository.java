package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookRecord;
import java.time.Instant;
import java.util.*;

/**
 * Durable URL receipts and leased checkpoints. The caller supplies authorization and transactions.
 */
public interface AcquisitionRepository {
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Status(
      UUID id,
      String url,
      String sourceName,
      String corpus,
      String state,
      UUID revisionId,
      int attempt,
      String error,
      int allowanceTotal,
      Instant createdAt,
      Gate preGate,
      Gate postGate) {
    public Status {
      Objects.requireNonNull(id);
      Objects.requireNonNull(url);
      Objects.requireNonNull(sourceName);
      Objects.requireNonNull(createdAt);
      if (attempt < 0 || allowanceTotal < 1)
        throw new IllegalArgumentException("invalid acquisition counts");
      if (!Set.of("queued", "running", "failed", "blocked", "succeeded").contains(state))
        throw new IllegalArgumentException("invalid acquisition state");
      if (!Set.of("code", "documents").contains(corpus))
        throw new IllegalArgumentException("invalid acquisition corpus");
    }
  }

  record Ticket(
      Status status,
      String account,
      UUID requestId,
      String project,
      String log,
      String session,
      UUID token,
      Intent intent) {
    public Ticket(
        Status status,
        String account,
        UUID requestId,
        String project,
        String log,
        String session,
        UUID token) {
      this(status, account, requestId, project, log, session, token, null);
    }
  }

  record Intent(
      UUID expectedRevision,
      io.aeyer.plowshare.protocol.InformationContent.Typing typing,
      io.aeyer.plowshare.protocol.InformationContent.Binding binding) {
    public Intent {
      Objects.requireNonNull(typing);
    }
  }

  default Optional<UUID> prior(String account, UUID request, String fingerprint) {
    return Optional.empty();
  }

  default UUID submit(
      InformationContext context,
      UUID request,
      String fingerprint,
      String url,
      String name,
      String session,
      int total,
      String corpus,
      Intent intent) {
    if (intent != null)
      throw new UnsupportedOperationException("typed acquisition persistence unavailable");
    return submit(context, request, fingerprint, url, name, session, total, corpus);
  }

  /** Inserted/reconciled receipt with database defaults; the service still admits its scope. */
  default Ticket submitRetained(
      InformationContext context,
      UUID request,
      String fingerprint,
      String url,
      String name,
      String session,
      int total,
      String corpus,
      Intent intent) {
    return owned(
        context.account(),
        submit(context, request, fingerprint, url, name, session, total, corpus, intent));
  }

  record Retained(UUID resource, String sourceUri) {}

  UUID submit(
      InformationContext context,
      UUID request,
      String fingerprint,
      String url,
      String name,
      String session,
      int total,
      String corpus);

  Ticket owned(String account, UUID id);

  List<Status> list(String account, String project, String corpus, int limit, int offset);

  void retry(UUID ticket, String account);

  /** Claims one ticket with SKIP LOCKED and increments its attempt under the row lock. */
  Optional<Ticket> claim();

  void fence(Ticket ticket);

  void opened(Ticket ticket, String log);

  void preGate(Ticket ticket, Gate gate);

  void postGate(Ticket ticket, Gate gate);

  void admitted(Ticket ticket, UUID revision);

  Retained retained(UUID revision);

  void finish(Ticket ticket, String state, String error);

  void finishRecords(Ticket ticket, List<HookRecord> records);
}
