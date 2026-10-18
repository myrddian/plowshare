package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Reuses SYSTEM dispatch and retained claims; it never chooses or replaces the owning budget. */
@Component
public final class BoundOperationEmbeddings implements OperationEmbeddings {
  private final SemanticEmbedding foundation;
  private final EmbeddingWorkRepository outputs;
  private final UnitOfWork transactions;
  private final IdentityRepository identities;

  public BoundOperationEmbeddings(
      SemanticEmbedding foundation,
      EmbeddingWorkRepository outputs,
      UnitOfWork transactions,
      IdentityRepository identities) {
    this.foundation = Objects.requireNonNull(foundation);
    this.outputs = Objects.requireNonNull(outputs);
    this.transactions = Objects.requireNonNull(transactions);
    this.identities = Objects.requireNonNull(identities);
  }

  public Query query(Home home, String text, Budget budget, Runnable validity) {
    if (text == null || text.isBlank()) throw new IllegalArgumentException("Query input required");
    return new QueryBinding(home, text, budget, validity);
  }

  public Batch maintenanceBatch(Home home, List<String> texts, Budget budget, Runnable validity) {
    if (texts == null || texts.isEmpty() || texts.size() > 16)
      throw new IllegalArgumentException("Bounded passage batch required");
    return new BatchBinding(home, texts, budget, validity);
  }

  public Publication publication(
      Home home, List<EmbeddingWorkRepository.Source> sources, Budget budget, Runnable validity) {
    if (sources == null || sources.isEmpty() || sources.size() > 100)
      throw new IllegalArgumentException("Bounded committed sources required");
    for (var source : sources)
      if (!Objects.equals(home.project(), source.project()))
        throw new IllegalArgumentException("Publication differs from its operation home");
    return new PublicationBinding(
        home,
        sources.stream().map(EmbeddingWorkRepository.Source::text).toList(),
        List.copyOf(sources),
        budget,
        validity);
  }

  private final class QueryBinding extends Binding implements Query {
    QueryBinding(Home home, String text, Budget budget, Runnable validity) {
      super(home, List.of(text), List.of(), budget, validity, Mode.QUERY);
    }
  }

  private final class BatchBinding extends Binding implements Batch {
    BatchBinding(Home home, List<String> inputs, Budget budget, Runnable validity) {
      super(home, inputs, List.of(), budget, validity, Mode.DOCUMENT);
    }
  }

  private final class PublicationBinding extends Binding implements Publication {
    PublicationBinding(
        Home home,
        List<String> inputs,
        List<EmbeddingWorkRepository.Source> sources,
        Budget budget,
        Runnable validity) {
      super(home, inputs, sources, budget, validity, Mode.DOCUMENT);
    }
  }

  private class Binding implements EmbeddingPermit, AutoCloseable {
    private final Home home;
    private final List<String> inputs;
    private final List<EmbeddingWorkRepository.Source> sources;
    private final Budget budget;
    private final Runnable validity;
    private final Mode mode;
    private final Set<String> attempted = new HashSet<>();
    private volatile boolean closed;
    private final List<CallAuthorization> authorizations = new ArrayList<>();

    Binding(
        Home home,
        List<String> inputs,
        List<EmbeddingWorkRepository.Source> sources,
        Budget budget,
        Runnable validity,
        Mode mode) {
      this.mode = mode;
      this.home = Objects.requireNonNull(home);
      this.inputs = List.copyOf(inputs);
      this.sources = sources;
      this.budget = Objects.requireNonNull(budget);
      this.validity = Objects.requireNonNull(validity);
      require();
    }

    public Mode mode() {
      return mode;
    }

    public synchronized void require() {
      live();
      authorizations.forEach(CallAuthorization::require);
    }

    private void live() {
      if (closed || Thread.currentThread().isInterrupted())
        throw new IllegalStateException("Embedding operation ended");
      validity.run();
      for (var source : sources)
        if (!outputs.source(source.key()).filter(source::equals).isPresent())
          throw new IllegalStateException("Embedding operation source changed or was withdrawn");
    }

    public void sources(List<EmbeddingWorkRepository.Key> selected) {
      require();
      if (mode() != Mode.DOCUMENT
          || selected.isEmpty()
          || new HashSet<>(selected).size() != selected.size()
          || !sources.stream()
              .map(EmbeddingWorkRepository.Source::key)
              .toList()
              .containsAll(selected))
        throw new IllegalStateException("Embedding outputs exceed the operation");
    }

    public CallAuthorization authorize(EmbeddingRequest request) {
      return authorize(new Task(mode(), request.specifier(), request));
    }

    public synchronized CallAuthorization authorize(Task task) {
      require();
      if (task.mode() != mode()
          || task.sources().isEmpty()
          || (sources.isEmpty()
              ? !inputs.equals(task.sources())
              : !inputs.containsAll(task.sources())))
        throw new IllegalStateException("Embedding input exceeds the operation");
      String fingerprint = EmbeddingEvidence.task(task);
      if (!attempted.add(fingerprint))
        throw new IllegalStateException(
            "Embedding attempt already admitted; reconcile without replay");
      return transactions.inTransaction(
          () -> {
            require();
            var scope =
                home.isGlobal()
                    ? BuiltInIdentities.GLOBAL_KNOWLEDGE
                    : identities.project(home.project()).id();
            var authorization =
                foundation.authorize(
                    new SemanticEmbedding.Operation(
                        mode() == Mode.QUERY
                            ? SystemOperations.Purpose.SEMANTIC_QUERY
                            : SystemOperations.Purpose.EMBEDDING_REPAIR,
                        home,
                        scope,
                        EmbeddingEvidence.digest(
                            List.of(
                                fingerprint,
                                home.toString(),
                                sources.toString(),
                                budget.identity().toString())),
                        this::live),
                    task,
                    () ->
                        budget
                            .tryClaim()
                            .orElseThrow(
                                () ->
                                    new IllegalStateException(
                                        "Operation embedding inference is disabled or exhausted")));
            authorizations.add(authorization);
            return authorization;
          });
    }

    public void published() {
      require();
    }

    public void close() {
      closed = true;
    }
  }
}
