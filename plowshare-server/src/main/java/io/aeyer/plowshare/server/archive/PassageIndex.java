package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.documents.Chunker;
import io.aeyer.plowshare.server.documents.Chunking;
import io.aeyer.plowshare.server.embedding.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.util.*;

/** Committed-source queue, shared by retained entries and durable digest summaries. */
public final class PassageIndex implements UsageAware {
  private DualEmbeddings dualEmbeddings;
  private QueryEmbeddings queries;
  private io.aeyer.plowshare.server.information.InformationAccess access;
  private OperationEmbeddings operations;
  private java.util.function.Supplier<io.aeyer.plowshare.server.agents.Budget> maintenancePolicy;

  public void useOperations(
      QueryEmbeddings queries,
      io.aeyer.plowshare.server.information.InformationAccess access,
      OperationEmbeddings operations,
      java.util.function.Supplier<io.aeyer.plowshare.server.agents.Budget> maintenancePolicy) {
    this.queries = Objects.requireNonNull(queries);
    this.access = Objects.requireNonNull(access);
    this.operations = Objects.requireNonNull(operations);
    this.maintenancePolicy = Objects.requireNonNull(maintenancePolicy);
  }

  public void useDualEmbeddings(DualEmbeddings embeddings) {
    dualEmbeddings = java.util.Objects.requireNonNull(embeddings);
  }

  private UsageOwners usageOwners = UsageOwners.NONE;

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  public record Coverage(
      int eligible, int indexed, int passages, int pending, int failed, int stale) {
    public Coverage(int eligible, int indexed, int passages, int pending, int failed) {
      this(eligible, indexed, passages, pending, failed, 0);
    }

    public boolean complete() {
      return eligible == indexed && stale == 0;
    }
  }

  public record Source(String type, String id, String hash, String text, int next) {}

  public record Match(String id, int position, String text, double similarity, String revision) {}

  private final PassageRepository repository;
  private final EmbeddingClient embeddings;
  private final Chunking bounds;
  private final String generation;
  private final int width;
  private PassageRepository.ReadScope scope = PassageRepository.ReadScope.UNRESTRICTED;

  public PassageIndex(
      PassageRepository repository,
      EmbeddingClient embeddings,
      Chunking bounds,
      String generation,
      int width) {
    if (width != 768)
      throw new IllegalArgumentException("Retrieval passages require the schema's 768 dimensions");
    this.repository = repository;
    this.embeddings = embeddings;
    this.bounds = bounds;
    this.generation = generation;
    this.width = width;
  }

  public PassageIndex forAccount(String account) {
    PassageIndex copy = new PassageIndex(repository, embeddings, bounds, generation, width);
    copy.scope = new PassageRepository.ReadScope(true, account);
    copy.usageOwners = usageOwners;
    copy.dualEmbeddings = dualEmbeddings;
    copy.queries = queries;
    copy.access = access;
    copy.operations = operations;
    copy.maintenancePolicy = maintenancePolicy;
    return copy;
  }

  /**
   * Digest navigation filters protected inputs before ranking/counting; Global excludes log trees.
   */
  public PassageIndex forDigestKnowledge(boolean global) {
    var copy = forAccount(scope.account());
    copy.scope = new PassageRepository.ReadScope(true, scope.account(), global);
    return copy;
  }

  public String searchDescription() {
    if (dualEmbeddings == null) return "Exact scoped cosine passage ranking";
    var profile = dualEmbeddings.active(EmbeddingSlot.PROSE);
    return "Scoped prose passage ranking: "
        + profile.search().mode().configurationName()
        + ", "
        + profile.search().distance().name().toLowerCase(java.util.Locale.ROOT)
        + (profile.search().mode() == EmbeddingSearchPolicy.Mode.EXACT
            ? ""
            : "; approximate candidates reranked at full precision");
  }

  public String generation() {
    return generation;
  }

  /** A captured query can safely be reused across home tiers, never across activation versions. */
  public record Query(float[] legacy, EmbeddingQuery typed, QueryEmbeddings.Read admission)
      implements AutoCloseable {
    public Query(float[] legacy, EmbeddingQuery typed) {
      this(legacy, typed, null);
    }

    public void close() {
      if (admission != null) admission.close();
    }
  }

  /** Existing authenticated conversation search shares the normal read-operation admission. */
  public Query capture(String text, UsageAttribution owner, Home home) {
    if (queries == null) return capture(text, owner);
    if (!scope.constrained() || scope.account() == null)
      throw new IllegalStateException("Passage query requires its authenticated reader");
    var selection =
        home.isGlobal()
            ? io.aeyer.plowshare.server.information.InformationContext.Selection.shared()
            : io.aeyer.plowshare.server.information.InformationAccess.projectSelection(
                scope.account(), home.project());
    var context = access.resolve(scope.account(), selection);
    var read =
        queries.open(
            context,
            new QueryEmbeddings.Query(QueryEmbeddings.Kind.SEARCH, text, null, 201, "passages"),
            QueryEmbeddings.Origin.request(
                () -> {
                  if (Thread.currentThread().isInterrupted())
                    throw new IllegalStateException("Passage query ended");
                }));
    try {
      var captured = capture(text, owner, read);
      return new Query(captured.legacy(), captured.typed(), read);
    } catch (RuntimeException failed) {
      read.close();
      throw failed;
    }
  }

  /** Trusted existing operation owns this query's source and spending policy. */
  public Query capture(String text, UsageAttribution owner, EmbeddingPermit permit) {
    permit.require();
    if (dualEmbeddings == null)
      return new Query(checked(embeddings.forOperation(permit).embed(text, owner)), null);
    var bound = dualEmbeddings.forQuery(permit);
    return new Query(null, bound.query(bound.active(EmbeddingSlot.PROSE), text, owner));
  }

  public Query capture(String text, UsageAttribution owner) {
    if (dualEmbeddings == null) return new Query(query(text, owner), null);
    return new Query(
        null, dualEmbeddings.query(dualEmbeddings.active(EmbeddingSlot.PROSE), text, owner));
  }

  public List<Match> rank(Home home, String type, Query query, int most) {
    if (query.admission() != null)
      return query
          .admission()
          .disclose(() -> rank(home, type, new Query(query.legacy(), query.typed()), most));
    if (query.typed() == null) return rank(home, type, query.legacy(), most);
    return dualEmbeddings.read(
        query.typed().profile(),
        () -> repository.rank(home, type, query.typed(), most, generation, scope));
  }

  public float[] query(String text) {
    return query(
        text, usageOwners.in(Home.global(), null, UsageAttribution.Operation.EMBEDDING_QUERY));
  }

  public float[] query(String text, UsageAttribution owner) {
    return checked(
        EmbeddingClient.owned(
            embeddings,
            text,
            owner.forOperation(UsageAttribution.Operation.EMBEDDING_QUERY, owner.agentName())));
  }

  private float[] checked(float[] vector) {
    if (vector == null || vector.length != width)
      throw new EmbeddingException("Incompatible retrieval embedding width");
    double norm = 0;
    for (float v : vector) {
      if (!Float.isFinite(v)) throw new EmbeddingException("Non-finite embedding");
      norm += v * v;
    }
    if (norm == 0) throw new EmbeddingException("Zero retrieval embedding");
    return vector;
  }

  /** At most eight sources per pass; successes survive restart, failures back off. */
  public int repair() {
    List<Source> pending = repository.pending(generation);
    var allowance = maintenancePolicy == null ? null : maintenancePolicy.get();
    int written = 0;
    for (Source source : pending) {
      if (source.text() == null || source.text().isBlank()) continue;
      boolean repairInFlight = false;
      try {
        var chunks = passages(source.text());
        if (dualEmbeddings != null) {
          int start = source.next(), end = Math.min(start + 16, chunks.size());
          if (repository.publishText(
              source, chunks.subList(start, end), end == chunks.size(), generation)) written++;
          continue;
        }
        // Reuse existing digest vectors only when their embedding generation is known.
        if (source.type().equals("digest")
            && source.next() == 0
            && source.text().length() <= EntryStore.MOST_CHARACTERS_PER_SNIPPET
            && bounds.tokenizer().count(source.text()).tokens() <= bounds.maxTokens()) {
          var existing = repository.digestVector(source.id(), generation);
          if (existing.isPresent()) {
            if (publish(source, List.of(source.text()), List.of(checked(existing.orElseThrow()))))
              written++;
            continue;
          }
        }
        int start = source.next(), end = Math.min(start + 16, chunks.size());
        var texts = chunks.subList(start, end);
        UsageAttribution owner = repairOwner(source);
        if (operations == null) {
          var batch = EmbeddingClient.owned(embeddings, texts, owner);
          if (batch.size() != texts.size())
            throw new EmbeddingException("Embedding batch count mismatch");
          if (publish(
              source, texts, batch.stream().map(this::checked).toList(), end == chunks.size()))
            written++;
        } else {
          if (allowance.exhausted()) break;
          var home =
              repository
                  .sourceHome(source)
                  .orElseThrow(() -> new IllegalStateException("Passage source home unavailable"));
          try (var permit =
              operations.maintenanceBatch(
                  home, texts, allowance, () -> repository.requireSource(source, generation))) {
            if (!repository.beginRepair(source, generation)) continue;
            repairInFlight = true;
            var batch = embeddings.forOperation(permit).embedAll(texts, owner);
            if (batch.size() != texts.size())
              throw new EmbeddingException("Embedding batch count mismatch");
            if (repository.publish(
                source,
                texts,
                batch.stream().map(this::checked).toList(),
                end == chunks.size(),
                generation,
                permit::require)) written++;
          }
        }

      } catch (RuntimeException failure) {
        // A source changed/ejected during dispatch is not resurrected, even as a failure.
        if (!repairInFlight) repository.failed(source, failure.getClass().getSimpleName());
      }
    }
    return written;
  }

  private UsageAttribution repairOwner(Source source) {
    if (source.type().equals("entry")) {
      var owner =
          repository
              .logOwner(source.id())
              .orElseThrow(
                  () -> new IllegalStateException("Retrieval source has no accounting owner"));
      return usageOwners.conversation(
          owner.conversation(), owner.turn(), UsageAttribution.Operation.EMBEDDING_REPAIR);
    }
    Home home =
        repository
            .digestHome(source.id())
            .orElseThrow(
                () -> new IllegalStateException("Retrieval source has no accounting owner"));
    return usageOwners.in(home, null, UsageAttribution.Operation.EMBEDDING_REPAIR);
  }

  /** Keep the entire matched passage visible inside the existing snippet contract. */
  private List<String> passages(String text) {
    List<String> result = new ArrayList<>();
    for (var chunk : Chunker.chunk(text, bounds)) {
      String held = chunk.text();
      for (int start = 0; start < held.length(); ) {
        int end = Math.min(start + EntryStore.MOST_CHARACTERS_PER_SNIPPET, held.length());
        if (end < held.length() && Character.isHighSurrogate(held.charAt(end - 1))) end--;
        String piece = held.substring(start, end);
        for (var bounded : Chunker.chunk(piece, bounds)) result.add(bounded.text());
        start = end;
      }
    }
    return List.copyOf(result);
  }

  boolean publish(Source expected, List<String> texts, List<float[]> vectors) {
    return publish(expected, texts, vectors, true);
  }

  boolean publish(Source expected, List<String> texts, List<float[]> vectors, boolean complete) {
    return repository.publish(expected, texts, vectors, complete, generation);
  }

  /**
   * Query-time home joins remain authoritative after a home move. Exact top-k, no ANN post-filter.
   */
  public List<Match> rank(Home home, String type, float[] query, int most) {
    return repository.rank(home, type, checked(query), most, generation, scope);
  }

  public Coverage coverage(Home home, String type) {
    if (dualEmbeddings != null) {
      EmbeddingProfile profile;
      try {
        profile = dualEmbeddings.active(EmbeddingSlot.PROSE);
      } catch (EmbeddingException unavailable) {
        return repository.coverage(home, type, generation, scope, Optional.empty());
      }
      return dualEmbeddings.read(
          profile, () -> repository.coverage(home, type, generation, scope, Optional.of(profile)));
    }
    return repository.coverage(home, type, generation, scope);
  }

  /** Read the current source metadata, never trusting cached passage content after retention. */
  public LogSearch.Hit hit(Home home, String id, String revision, double rank, String snippet) {
    return repository.hit(home, id, revision, rank, snippet, scope);
  }

  public String revision(Home home, String id) {
    return repository.revision(home, id, scope);
  }
}
