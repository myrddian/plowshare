package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.embedding.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;

/** No model call occurs inside a transaction. A pending claim fences ambiguous batch replay. */
public final class BoundPreparationEmbeddings implements PreparationEmbeddings {
  private final InformationProcessingRepository records;
  private final UnitOfWork transactions;
  private final SemanticEmbedding embedding;

  public BoundPreparationEmbeddings(
      InformationProcessingRepository records,
      UnitOfWork transactions,
      SemanticEmbedding embedding) {
    this.records = Objects.requireNonNull(records);
    this.transactions = Objects.requireNonNull(transactions);
    this.embedding = Objects.requireNonNull(embedding);
  }

  public Budget budget(InformationLifecycle.Lease lease) {
    var source = records.budgetSource(lease.revision());
    var expected = new java.util.concurrent.atomic.AtomicInteger(source.spent());
    return Budget.retained(
        source.id(),
        source.limit(),
        source.spent(),
        () ->
            transactions.inTransaction(
                () -> {
                  records.requireLease(lease);
                  records.spend(lease, expected.get());
                  expected.incrementAndGet();
                  return null;
                }));
  }

  public EmbeddingAuthorization forSources(
      InformationLifecycle.Lease lease, List<EmbeddingWorkRepository.Key> requested) {
    var targets = List.copyOf(requested);
    if (targets.isEmpty() || new HashSet<>(targets).size() != targets.size())
      throw new IllegalArgumentException("Distinct embedding targets required");
    Runnable fence =
        () ->
            transactions.inTransaction(
                () -> {
                  records.requireLease(lease);
                  records.requireEmbeddingTargets(lease, targets);
                  return null;
                });
    return new EmbeddingAuthorization() {
      private final List<InferenceBinding> pending = new ArrayList<>();

      public void require() {
        fence.run();
      }

      public void sources(List<EmbeddingWorkRepository.Key> keys) {
        if (!new HashSet<>(targets).equals(new HashSet<>(keys)))
          throw new IllegalStateException(
              "Embedding output targets differ from the admitted batch");
        require();
      }

      public CallAuthorization authorize(EmbeddingRequest request) {
        return authorize(new Task(Mode.DOCUMENT, request.specifier(), request));
      }

      public CallAuthorization authorize(Task task) {
        return transactions.inTransaction(
            () -> {
              fence.run();
              records.requireEmbeddingClear(lease, targets);
              String project = records.projectName(lease.project());
              var home = project == null ? Home.global() : Home.of(project);
              String binding =
                  InformationJson.json(
                      List.of(lease.revision(), lease.generation(), lease.stage(), targets));
              var captured =
                  embedding.authorize(
                      new SemanticEmbedding.Operation(
                          SystemOperations.Purpose.DOCUMENT_PREPARATION,
                          home,
                          records.home(lease.revision()),
                          binding,
                          fence),
                      task,
                      () -> records.claimEmbedding(lease));
              records.bindEmbedding(lease, captured.binding(), targets);
              pending.add(captured.binding());
              return captured;
            });
      }

      public void published() {
        transactions.inTransaction(
            () -> {
              fence.run();
              for (var binding : pending) records.embeddingPublished(lease, binding);
              return null;
            });
        pending.clear();
      }
    };
  }
}
