package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;
import java.util.function.Supplier;

/** Two independent model lanes through the existing dispatcher and durable inference accounting. */
public final class DispatchingDualEmbeddings implements DualEmbeddings, EmbeddingMaintenance {
  private final EmbeddingWorkRepository repository;
  private final EmbeddingDispatch dispatcher;
  private final EmbeddingTokenizers tokenizers;
  private final EmbeddingAuthorization preparation;
  private final EmbeddingPermit permit;
  private OperationEmbeddings operations;
  private Supplier<io.aeyer.plowshare.server.agents.Budget> maintenancePolicy;

  /** Only the maintenance composition selects an independent operation spending policy. */
  public void useOperations(
      OperationEmbeddings operations, Supplier<io.aeyer.plowshare.server.agents.Budget> policy) {
    this.operations = Objects.requireNonNull(operations);
    this.maintenancePolicy = Objects.requireNonNull(policy);
  }

  public DispatchingDualEmbeddings(
      EmbeddingWorkRepository repository,
      EmbeddingDispatch dispatcher,
      EmbeddingTokenizers tokenizers) {
    this(repository, dispatcher, tokenizers, null);
  }

  private DispatchingDualEmbeddings(
      EmbeddingWorkRepository repository,
      EmbeddingDispatch dispatcher,
      EmbeddingTokenizers tokenizers,
      EmbeddingPermit permit) {
    this.permit = permit;
    this.preparation = permit instanceof EmbeddingAuthorization p ? p : null;
    this.repository = Objects.requireNonNull(repository);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.tokenizers = Objects.requireNonNull(tokenizers);
  }

  @Override
  public DualEmbeddings forPreparation(EmbeddingAuthorization permit) {
    return new DispatchingDualEmbeddings(
        repository, dispatcher, tokenizers, Objects.requireNonNull(permit));
  }

  @Override
  public DualEmbeddings forWrite(EmbeddingAuthorization permit) {
    return new DispatchingDualEmbeddings(
        repository, dispatcher, tokenizers, Objects.requireNonNull(permit));
  }

  @Override
  public DualEmbeddings forQuery(EmbeddingPermit permit) {
    if (permit.mode() != EmbeddingPermit.Mode.QUERY)
      throw new IllegalArgumentException("Query permit required");
    return new DispatchingDualEmbeddings(repository, dispatcher, tokenizers, permit);
  }

  @Override
  public String fingerprint() {
    return "dual-embedding-v1:"
        + repository.target(EmbeddingSlot.CODE).orElseThrow().space().id()
        + ":"
        + repository.target(EmbeddingSlot.PROSE).orElseThrow().space().id();
  }

  @Override
  public EmbeddingProfile active(EmbeddingSlot slot) {
    return repository
        .active(slot)
        .orElseThrow(
            () ->
                new EmbeddingException(
                    "the "
                        + slot.stored()
                        + " embedding slot is rebuilding and has no active space yet"));
  }

  @Override
  public EmbeddingQuery query(EmbeddingProfile profile, String text, UsageAttribution owner) {
    return queries(profile, List.of(text), owner).getFirst();
  }

  @Override
  public List<EmbeddingQuery> queries(
      EmbeddingProfile profile, List<String> texts, UsageAttribution owner) {
    if (preparation != null)
      throw new IllegalStateException("Document preparation cannot authorize search queries");
    return vectors(
            profile,
            texts,
            true,
            owner.forOperation(UsageAttribution.Operation.EMBEDDING_QUERY, owner.agentName()))
        .stream()
        .map(v -> new EmbeddingQuery(profile, v))
        .toList();
  }

  private List<float[]> vectors(
      EmbeddingProfile profile, List<String> texts, boolean query, UsageAttribution owner) {
    return vectors(profile, texts, query, owner, () -> {});
  }

  private List<float[]> vectors(
      EmbeddingProfile profile,
      List<String> texts,
      boolean query,
      UsageAttribution owner,
      Runnable submitted) {
    Objects.requireNonNull(owner);
    Objects.requireNonNull(texts);
    if (texts.isEmpty()) return List.of();
    if (texts.size() > 100)
      throw new IllegalArgumentException("embedding batch exceeds 100 inputs");
    var definition = profile.space().definition();
    int ceiling = repository.inputLimit(profile.space());
    String prefix = query ? definition.queryPrefix() : definition.documentPrefix();
    var inputs = new ArrayList<String>();
    for (String text : texts) {
      Objects.requireNonNull(text, "embedding input");
      if (text.indexOf('\0') >= 0)
        throw new IllegalArgumentException("embedding input contains NUL");
      String transformed = prefix + text;
      var count = tokenizers.model(definition).count(transformed);
      if (!count.isMeasured())
        throw new EmbeddingException(
            "embedding limits require the encoder's exact tokenizer; estimates are refused");
      if (count.tokens() > ceiling)
        throw new EmbeddingException(
            "embedding input exceeds the registered model allowance after task preprocessing; input was refused");
      inputs.add(transformed);
    }
    List<float[]> output;
    try {
      var request = EmbeddingRequest.of(definition.modelId(), inputs).withAttribution(owner);
      if (permit != null)
        request =
            request.withAuthorization(
                permit.authorize(
                    new EmbeddingPermit.Task(
                        query ? EmbeddingPermit.Mode.QUERY : EmbeddingPermit.Mode.DOCUMENT,
                        profile.toString(),
                        texts,
                        request)));
      if (query && permit != null) {
        // A queued query cannot follow an activation to a different encoder. Keep the original
        // exact-input/claim evidence while adding this read-profile validity fence.
        request =
            request.withAuthorization(
                request
                    .authorization()
                    .and(
                        () -> {
                          if (!profile.equals(repository.active(profile.slot()).orElse(null)))
                            throw new EmbeddingException(
                                "embedding configuration changed during this query; submit a new search");
                        }));
      }
      submitted.run();
      output = dispatcher.embed(request).vectors();
      if (permit != null) permit.require();
    } catch (LlmException failed) {
      throw new EmbeddingException(failed.getMessage(), failed);
    }
    if (output.size() != inputs.size())
      throw new EmbeddingException("embedding batch count differs from input count");
    var result = new ArrayList<float[]>();
    for (float[] vector : output) {
      if (vector == null || vector.length != definition.dimensions())
        throw new EmbeddingException("embedding output width differs from registered model");
      float[] normalized = vector.clone();
      double magnitude = 0;
      for (float v : normalized) {
        if (!Float.isFinite(v)) throw new EmbeddingException("non-finite embedding output");
        magnitude += (double) v * v;
      }
      if (magnitude == 0) throw new EmbeddingException("zero embedding output");
      if (definition.normalization() == EmbeddingSpace.Normalization.L2) {
        double norm = Math.sqrt(magnitude);
        for (int i = 0; i < normalized.length; i++) normalized[i] = (float) (normalized[i] / norm);
      }
      result.add(EmbeddingQuery.validate(profile, normalized));
    }
    return List.copyOf(result);
  }

  @Override
  public <T> T read(EmbeddingProfile profile, Supplier<T> work) {
    return repository.read(profile, work);
  }

  @Override
  public boolean repair(EmbeddingWorkRepository.Key key, UsageAttribution owner) {
    return repairAll(List.of(key), owner).completeSources() == 1;
  }

  @Override
  public RepairResult repairAll(List<EmbeddingWorkRepository.Key> keys, UsageAttribution owner) {
    if (permit != null && preparation == null)
      throw new IllegalStateException("Query cannot publish embeddings");
    Objects.requireNonNull(owner);
    if (keys == null || keys.size() > 100 || new HashSet<>(keys).size() != keys.size())
      throw new IllegalArgumentException("invalid embedding repair batch");
    if (preparation != null) preparation.sources(keys);
    var sources = keys.stream().map(repository::source).flatMap(Optional::stream).toList();
    if (sources.size() != keys.size()) return new RepairResult(0, 0);
    var completed = new HashSet<>(keys);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    EmbeddingException firstFailure = null;
    // Each slot is a separate request. A failure in code still permits the prose checkpoint.
    for (EmbeddingSlot slot : EmbeddingSlot.values()) {
      for (var target : writeProfiles(slot)) {
        var missing = sources.stream().filter(source -> !repository.ready(target, source)).toList();
        if (missing.isEmpty()) continue;
        try {
          var output =
              vectors(
                  target,
                  missing.stream().map(EmbeddingWorkRepository.Source::text).toList(),
                  false,
                  owner,
                  calls::incrementAndGet);
          for (int i = 0; i < missing.size(); i++) {
            if (preparation != null) preparation.require();
            if (!(preparation == null
                ? repository.publish(target, missing.get(i), output.get(i))
                : repository.publish(
                    target, missing.get(i), output.get(i), preparation::publicationFence))) {
              if (preparation != null)
                throw new IllegalStateException("Preparation output changed");
              completed.remove(missing.get(i).key());
            }
          }
          if (preparation != null) preparation.published();
        } catch (EmbeddingException failed) {
          for (var source : missing)
            repository.failed(target, source, failed.getClass().getSimpleName());
          if (firstFailure == null) firstFailure = failed;
        }
      }
    }
    if (firstFailure != null) throw new EmbeddingRepairException(firstFailure, calls.get());
    return new RepairResult(completed.size(), calls.get());
  }

  private List<EmbeddingProfile> writeProfiles(EmbeddingSlot slot) {
    var target = repository.target(slot).orElseThrow();
    var active = repository.active(slot);
    if (active.isEmpty()) return List.of(target);
    if (active.get().space().equals(target.space())) return List.of(active.get());
    // New/edited sources also receive the serving generation while a replacement catches up.
    return List.of(active.get(), target);
  }

  boolean repair(
      EmbeddingProfile target, EmbeddingWorkRepository.Source source, UsageAttribution owner) {
    if (repository.ready(target, source)) return true;
    var vector = vectors(target, List.of(source.text()), false, owner).getFirst();
    return preparation == null
        ? repository.publish(target, source, vector)
        : repository.publish(target, source, vector, preparation::publicationFence);
  }

  private boolean repairRetained(
      EmbeddingProfile target, EmbeddingWorkRepository.Source source, UsageAttribution owner) {
    if (repository.ready(target, source)) return true;
    boolean[] retained = {false};
    try {
      var vector =
          vectors(
                  target,
                  List.of(source.text()),
                  false,
                  owner,
                  () -> {
                    if (!repository.beginRepair(target, source))
                      throw new EmbeddingWorkRepository.UnreconciledRepair(null);
                    retained[0] = true;
                  })
              .getFirst();
      return repository.publish(target, source, vector, preparation::publicationFence);
    } catch (RuntimeException failed) {
      if (retained[0]) throw new EmbeddingWorkRepository.UnreconciledRepair(failed);
      throw failed;
    }
  }

  /** One bounded pass; staged vectors and retry timestamps are the persisted rebuild checkpoint. */
  public int tick() {
    if (permit != null)
      throw new IllegalStateException("Bound embedding operation cannot authorize maintenance");
    int published = 0;
    var budget = operations == null ? null : maintenancePolicy.get();
    if (Thread.currentThread().isInterrupted() || budget != null && budget.exhausted())
      return published;
    if (!repository.discoveryDue()) return published;
    for (EmbeddingSlot slot : EmbeddingSlot.values()) {
      var profiles = writeProfiles(slot);
      for (EmbeddingProfile target : profiles) {
        for (EmbeddingWorkRepository.Store store : EmbeddingWorkRepository.Store.values()) {
          // Discovery itself consumes database capacity. Exhausted/cancelled passes must not
          // scan every store just to discover that no provider submission can be admitted.
          if (Thread.currentThread().isInterrupted() || budget != null && budget.exhausted())
            return published;
          for (var source : repository.claimPending(target, store, 4)) {
            if (Thread.currentThread().isInterrupted()) return published;
            if (budget != null && budget.exhausted()) break;
            try {
              var owner =
                  UsageAttribution.system(
                      source.project(), UsageAttribution.Operation.EMBEDDING_REPAIR);
              boolean repaired;
              if (operations == null) {
                repaired = repair(target, source, owner);
              } else {
                var home =
                    source.project() == null
                        ? io.aeyer.plowshare.protocol.Home.global()
                        : io.aeyer.plowshare.protocol.Home.of(source.project());
                try (var binding =
                    operations.publication(home, List.of(source), budget, () -> {})) {
                  repaired =
                      new DispatchingDualEmbeddings(repository, dispatcher, tokenizers, binding)
                          .repairRetained(target, source, owner);
                }
              }
              if (repaired) published++;
            } catch (EmbeddingException failed) {
              if (!(failed instanceof EmbeddingWorkRepository.UnreconciledRepair))
                repository.failed(target, source, failed.getClass().getSimpleName());
            }
          }
        }
      }
      if (Thread.currentThread().isInterrupted()) return published;
      var target = repository.target(slot).orElseThrow();
      // Known queued/retrying work or an unfinished initial discovery cannot be full coverage.
      // Avoid recompiling/scanning the corpus to prove that on every repair batch. The eventual
      // activation still locks sources and establishes coverage authoritatively.
      if (repository.activationCandidate(target)) repository.activate(target);
    }
    return published;
  }
}
