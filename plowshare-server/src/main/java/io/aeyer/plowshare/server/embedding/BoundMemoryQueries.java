package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.server.access.BuiltInIdentities;
import io.aeyer.plowshare.server.access.ScopedPermissions;
import io.aeyer.plowshare.server.access.SystemOperations;
import io.aeyer.plowshare.server.archive.MemoryQueries;
import io.aeyer.plowshare.server.archive.MemoryReadRepository;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.CallAuthorization;
import io.aeyer.plowshare.server.llm.dispatch.EmbeddingRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Memory reads use the search policy, not a nearby conversation allowance or attribution actor. */
@Component
public final class BoundMemoryQueries implements MemoryQueries {
  private final MemoryReadRepository memories;
  private final QuerySources sources;
  private final UnitOfWork transactions;
  private final SemanticEmbedding foundation;
  private final EmbeddingClient embeddings;
  private final Supplier<DualEmbeddings> dual;
  private final Supplier<Integer> policy;
  private final Clock clock;
  private final ScopedPermissions permissions;

  @org.springframework.beans.factory.annotation.Autowired
  public BoundMemoryQueries(
      MemoryReadRepository memories,
      QuerySources sources,
      UnitOfWork transactions,
      SemanticEmbedding foundation,
      EmbeddingClient embeddings,
      org.springframework.beans.factory.ObjectProvider<DualEmbeddings> dual,
      EmbeddingProperties properties,
      ScopedPermissions permissions) {
    this(
        memories,
        sources,
        transactions,
        foundation,
        embeddings,
        dual::getIfAvailable,
        properties::searchLimit,
        Clock.systemUTC(),
        permissions);
  }

  public BoundMemoryQueries(
      MemoryReadRepository memories,
      QuerySources sources,
      UnitOfWork transactions,
      SemanticEmbedding foundation,
      EmbeddingClient embeddings,
      Supplier<DualEmbeddings> dual,
      Supplier<Integer> policy,
      Clock clock,
      ScopedPermissions permissions) {
    this.memories = Objects.requireNonNull(memories);
    this.sources = Objects.requireNonNull(sources);
    this.transactions = Objects.requireNonNull(transactions);
    this.foundation = Objects.requireNonNull(foundation);
    this.embeddings = Objects.requireNonNull(embeddings);
    this.dual = Objects.requireNonNull(dual);
    this.policy = Objects.requireNonNull(policy);
    this.clock = Objects.requireNonNull(clock);
    this.permissions = Objects.requireNonNull(permissions);
  }

  public Result read(Request request) {
    request.origin().validity().run();
    requireScope(request);
    var selection = memories.pin(request);
    memories.require(request, selection);
    if (request.limit() <= 0) return new Result(List.of(), 0);
    // Capture policy before any dispatch; null explicitly selects unlimited with retained usage.
    Integer ceiling = policy.get();
    try (var permit = new Read(request, selection, ceiling)) {
      var encoders = dual.get();
      if (encoders != null) {
        var bound = encoders.forQuery(permit);
        var query =
            bound.query(
                bound.active(EmbeddingSlot.PROSE),
                request.text(),
                UsageAttribution.system(null, UsageAttribution.Operation.EMBEDDING_QUERY));
        return bound.read(query.profile(), () -> disclose(request, selection, permit, null, query));
      }
      var query =
          embeddings
              .forOperation(permit)
              .embed(
                  request.text(),
                  UsageAttribution.system(null, UsageAttribution.Operation.EMBEDDING_QUERY));
      return disclose(request, selection, permit, query, null);
    }
  }

  private void requireScope(Request request) {
    if (!request.home().isGlobal()) {
      io.aeyer.plowshare.server.personal.PersonalSpaces.requireOwn(
          request.home().project(), request.account());
      if (!permissions.allows(
          new ScopedPermissions.Principal(request.account()),
          new ScopedPermissions.ProjectTarget(request.home().project()),
          ScopedPermissions.Action.READ))
        throw new io.aeyer.plowshare.server.faults.CallerFault("Memory scope is not readable");
    }
  }

  private Result disclose(
      Request request,
      MemoryReadRepository.Selection selection,
      Read permit,
      float[] vector,
      EmbeddingQuery query) {
    return transactions.inTransaction(
        () -> {
          memories.lockRead(request, selection);
          permit.require();
          var hits = new ArrayList<Memory>();
          int missing = 0;
          for (var tier : selection.tiers()) {
            hits.addAll(
                query == null
                    ? memories.searchByVector(vector, tier, request.limit())
                    : memories.searchByVector(query, tier, request.limit()));
            missing +=
                query == null
                    ? memories.countUnsearchable(tier)
                    : memories.countUnsearchable(query.profile(), tier);
          }
          var result = hits.subList(0, Math.min(request.limit(), hits.size()));
          var response =
              request.kind() == Kind.RECALL
                  ? new Result(
                      result.stream()
                          .map(memory -> memories.used(memory, clock.instant()))
                          .toList(),
                      missing)
                  : new Result(result, missing);
          // Cancellation during result assembly rolls back telemetry instead of returning data.
          permit.require();
          return response;
        });
  }

  private final class Read implements EmbeddingPermit, AutoCloseable {
    private final Request request;
    private final MemoryReadRepository.Selection selection;
    private final Integer ceiling;
    private final String fingerprint;
    private volatile boolean closed;
    private QuerySources.Source source;

    Read(Request request, MemoryReadRepository.Selection selection, Integer ceiling) {
      this.request = request;
      this.selection = selection;
      this.ceiling = ceiling;
      fingerprint =
          EmbeddingEvidence.digest(
              List.of(
                  "memory-query",
                  request.kind().name(),
                  selection.pin().account().toString(),
                  selection.pin().scope().toString(),
                  Boolean.toString(request.runBound()),
                  request.text(),
                  Integer.toString(request.limit())));
    }

    public Mode mode() {
      return Mode.QUERY;
    }

    public void require() {
      if (closed || Thread.currentThread().isInterrupted())
        throw new IllegalStateException("Memory query ended");
      request.origin().validity().run();
      requireScope(request);
      memories.require(request, selection);
    }

    public CallAuthorization authorize(EmbeddingRequest request) {
      return authorize(new Task(Mode.QUERY, request.specifier(), request));
    }

    public synchronized CallAuthorization authorize(Task task) {
      if (task.mode() != Mode.QUERY || !task.sources().equals(List.of(request.text())))
        throw new IllegalStateException("Memory query differs from admitted input");
      // Publish the live source only after its claim and binding commit. Rollback must not
      // leave close() attempting to close a source that was never retained.
      var candidate = new QuerySources.Source[] {source};
      var result =
          transactions.inTransaction(
              () -> {
                require();
                if (candidate[0] == null)
                  candidate[0] =
                      sources.open(
                          selection.pin(), fingerprint, request.origin().invocation(), ceiling);
                var authorization =
                    foundation.authorize(
                        new SemanticEmbedding.Operation(
                            SystemOperations.Purpose.SEMANTIC_QUERY,
                            Home.global(),
                            BuiltInIdentities.ADMINISTRATION,
                            fingerprint,
                            this::require),
                        task,
                        () -> sources.claim(candidate[0]));
                sources.bind(candidate[0], authorization.binding(), EmbeddingEvidence.task(task));
                return authorization;
              });
      source = candidate[0];
      return result;
    }

    public synchronized void close() {
      closed = true;
      if (source != null)
        transactions.inTransaction(
            () -> {
              sources.close(source);
              return null;
            });
    }
  }
}
