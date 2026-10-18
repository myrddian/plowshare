package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.information.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;
import java.util.function.*;

/** A live query cannot be reconstructed from retained evidence after its owner ends. */
public final class BoundQueryEmbeddings implements QueryEmbeddings {
  private final QueryEmbeddingRepository records;
  private final InformationAccess access;
  private final UnitOfWork transactions;
  private final SemanticEmbedding foundation;
  private final Supplier<Integer> allowance;

  public BoundQueryEmbeddings(
      QueryEmbeddingRepository records,
      InformationAccess access,
      UnitOfWork transactions,
      SemanticEmbedding foundation,
      Supplier<Integer> allowance) {
    this.records = Objects.requireNonNull(records);
    this.access = Objects.requireNonNull(access);
    this.transactions = Objects.requireNonNull(transactions);
    this.foundation = Objects.requireNonNull(foundation);
    this.allowance = Objects.requireNonNull(allowance);
  }

  public Read open(InformationContext context, Query query, Origin origin) {
    if (context.selection().scope() == InformationContext.Scope.ACCOUNT)
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Account query targets are unsupported");
    access.requireSelection(context);
    var pin = records.pin(context);
    Integer limit = allowance.get();
    var fingerprint =
        EmbeddingEvidence.digest(
            List.of(InformationJson.json(context), InformationJson.json(query)));
    return new Read() {
      private volatile boolean closed;
      private QueryEmbeddingRepository.Source source;

      public Mode mode() {
        return Mode.QUERY;
      }

      public void require() {
        if (closed || Thread.currentThread().isInterrupted())
          throw new IllegalStateException("Query request ended");
        origin.validity().run();
        access.requireSelection(context);
        records.require(context, pin, query.revision());
      }

      public CallAuthorization authorize(EmbeddingRequest request) {
        return authorize(new Task(Mode.QUERY, request.specifier(), request));
      }

      public synchronized CallAuthorization authorize(Task task) {
        if (task.mode() != Mode.QUERY || !task.sources().equals(query.inputs()))
          throw new IllegalStateException("Query input differs from admitted request");
        String hash = EmbeddingEvidence.task(task);
        var candidate = new QueryEmbeddingRepository.Source[] {source};
        var authorization =
            transactions.inTransaction(
                () -> {
                  require();
                  if (candidate[0] == null)
                    candidate[0] =
                        records.open(context, pin, fingerprint, origin.invocation(), limit);
                  var captured =
                      foundation.authorize(
                          new SemanticEmbedding.Operation(
                              SystemOperations.Purpose.SEMANTIC_QUERY,
                              Home.global(),
                              BuiltInIdentities.ADMINISTRATION,
                              fingerprint,
                              this::require),
                          task,
                          () -> records.claim(candidate[0]));
                  records.bind(candidate[0], captured.binding(), hash);
                  return captured;
                });
        source = candidate[0];
        return authorization;
      }

      public <T> T disclose(Supplier<T> read) {
        return transactions.inTransaction(
            () -> {
              records.lockRead(context, pin, query.revision());
              require();
              return read.get();
            });
      }

      public synchronized void close() {
        closed = true;
        if (source != null)
          transactions.inTransaction(
              () -> {
                records.close(source);
                return null;
              });
      }
    };
  }
}
