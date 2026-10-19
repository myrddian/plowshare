package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.scribe.MemoryJudgements;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.*;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** One synchronous filing operation. SYSTEM execution and recipient authority remain separate. */
@Component
public final class BoundMemoryWrites implements MemoryWrites {
  private static final Logger log = LoggerFactory.getLogger(BoundMemoryWrites.class);
  private final MemoryFiling archive;
  private final MemoryJudgements scribe;
  private final MemoryWriteRepository memories;
  private final QuerySources sources;
  private final EmbeddingWorkRepository outputs;
  private final UnitOfWork transactions;
  private final SemanticEmbedding foundation;
  private final SystemOperations system;
  private final EmbeddingClient embeddings;
  private final Supplier<DualEmbeddings> dual;
  private final Supplier<Integer> policy;
  private final ScopedPermissions permissions;
  private final ScopedAuthority authority;
  private final IdentityRepository identities;

  @org.springframework.beans.factory.annotation.Autowired
  public BoundMemoryWrites(
      MemoryFiling archive,
      MemoryJudgements scribe,
      MemoryWriteRepository memories,
      QuerySources sources,
      EmbeddingWorkRepository outputs,
      UnitOfWork transactions,
      SemanticEmbedding foundation,
      SystemOperations system,
      EmbeddingClient embeddings,
      org.springframework.beans.factory.ObjectProvider<DualEmbeddings> dual,
      ArchiveProperties properties,
      ScopedPermissions permissions,
      ScopedAuthority authority,
      IdentityRepository identities) {
    this(
        archive,
        scribe,
        memories,
        sources,
        outputs,
        transactions,
        foundation,
        system,
        embeddings,
        dual::getIfAvailable,
        properties::writeLimit,
        permissions,
        authority,
        identities);
  }

  public BoundMemoryWrites(
      MemoryFiling archive,
      MemoryJudgements scribe,
      MemoryWriteRepository memories,
      QuerySources sources,
      EmbeddingWorkRepository outputs,
      UnitOfWork transactions,
      SemanticEmbedding foundation,
      SystemOperations system,
      EmbeddingClient embeddings,
      Supplier<DualEmbeddings> dual,
      Supplier<Integer> policy,
      ScopedPermissions permissions,
      ScopedAuthority authority,
      IdentityRepository identities) {
    this.archive = Objects.requireNonNull(archive);
    this.scribe = Objects.requireNonNull(scribe);
    this.memories = Objects.requireNonNull(memories);
    this.sources = Objects.requireNonNull(sources);
    this.outputs = Objects.requireNonNull(outputs);
    this.transactions = Objects.requireNonNull(transactions);
    this.foundation = Objects.requireNonNull(foundation);
    this.system = Objects.requireNonNull(system);
    this.embeddings = Objects.requireNonNull(embeddings);
    this.dual = Objects.requireNonNull(dual);
    this.policy = Objects.requireNonNull(policy);
    this.permissions = Objects.requireNonNull(permissions);
    this.authority = Objects.requireNonNull(authority);
    this.identities = Objects.requireNonNull(identities);
  }

  @Override
  public WriteResult write(Request request) {
    Validation.check(request.proposal(), archive.maxBodyChars());
    request.origin().alive().run();
    var pin = memories.pin(request);
    Integer ceiling = policy.get();
    try (var write = new Write(request, pin, ceiling, dual.get())) {
      // Even disabled/no-registry operations retain invocation evidence before any content effect.
      write.open();
      var judged =
          Integer.valueOf(0).equals(ceiling)
              ? new Scribe.Judgement(
                  new Verdict(
                      VerdictKind.NEW,
                      null,
                      "filed flat: memory write inference is disabled by policy"),
                  null)
              : scribe.judge(
                  request.proposal(),
                  request.home(),
                  UsageAttribution.system(
                      request.home().project(), UsageAttribution.Operation.REVIEW),
                  write);
      var result =
          archive.applyAdmitted(request.proposal(), judged.verdict(), request.home(), write);
      // A post-commit recipient check cannot unwind the content transaction or authorize indexing.
      write.processing = true;
      request.origin().alive().run();
      if (request.origin().learning() == null) write.requireRecipient();
      return result;
    }
  }

  private static String content(Memory memory) {
    return EmbeddingEvidence.digest(
        List.of(
            memory.id(),
            memory.home().toString(),
            memory.summary(),
            memory.scope(),
            memory.body(),
            memory.state().name(),
            Objects.toString(memory.supersedes(), ""),
            Objects.toString(memory.supersededBy(), ""),
            Objects.toString(memory.invalidation(), "")));
  }

  private final class Write implements Scribe.Consult, Archive.WriteBinding, AutoCloseable {
    private final Request request;
    private final QuerySources.Pin pin;
    private final Integer ceiling;
    private final DualEmbeddings lanes;
    private final String fingerprint;
    private final Map<String, String> candidates = new HashMap<>();
    private final Set<String> attempted = new HashSet<>();
    private final Set<String> uncertain = new HashSet<>();
    private String pendingBatch;
    private QuerySources.Source source;
    private volatile EmbeddingWorkRepository.Source output;
    private Archive.Precomputed completed;
    private boolean queryAttempted;
    private volatile boolean processing;
    private volatile boolean closed;
    private int spent;

    Write(Request request, QuerySources.Pin pin, Integer ceiling, DualEmbeddings lanes) {
      this.request = request;
      this.pin = pin;
      this.ceiling = ceiling;
      this.lanes = lanes;
      fingerprint =
          EmbeddingEvidence.digest(
              List.of(
                  "memory-write",
                  pin.account().toString(),
                  pin.scope().toString(),
                  request.proposal().toString()));
    }

    void open() {
      source =
          transactions.inTransaction(
              () -> {
                memories.lock(request, pin, false);
                require();
                var invocation = request.origin().invocation();
                return sources.openWrite(
                    pin,
                    fingerprint,
                    invocation == null ? null : "memory-write:" + invocation,
                    ceiling);
              });
    }

    void requireRecipient() {
      request.origin().authority().run();
      if (!request.home().isGlobal()) {
        io.aeyer.plowshare.server.personal.PersonalSpaces.requireOwn(
            request.home().project(), request.account());
        var principal = new ScopedPermissions.Principal(request.account());
        var target = new ScopedPermissions.ProjectTarget(request.home().project());
        if (!permissions.allows(principal, target, ScopedPermissions.Action.READ)
            || !permissions.allows(principal, target, ScopedPermissions.Action.WRITE))
          throw new CallerFault("Memory scope is not writable");
      }
      memories.require(request, pin, false);
    }

    public void require() {
      require(processing);
    }

    private void require(boolean indexing) {
      if (!indexing && processing) throw new IllegalStateException("Memory judgement phase ended");
      if (closed || Thread.currentThread().isInterrupted())
        throw new IllegalStateException("Memory write ended");
      request.origin().alive().run();
      if (!indexing && request.origin().learning() == null) requireRecipient();
      memories.require(request, pin, indexing);
      if (!indexing) {
        // Revalidate retained prompt inputs before queued chat dispatch as well as mutation.
        // SYSTEM cannot continue a consult over withdrawn/private or changed candidates.
        for (var candidate : candidates.entrySet())
          if (!candidate.getValue().equals(content(memories.current(candidate.getKey()))))
            throw new CallerFault("Scribe candidate changed or was withdrawn");
      }
      var scope = identities.scope(new AuthorityIds.ScopeId(pin.scope()));
      if (!authority.allows(
          GrantRecipient.account(BuiltInIdentities.SYSTEM), scope, AuthorityAction.WRITE))
        throw new CallerFault("SYSTEM memory operation authority unavailable");
      if (indexing && output != null) requireOutput();
    }

    private void requireOutput() {
      var current =
          outputs
              .source(output.key())
              .orElseThrow(() -> new IllegalStateException("Memory output withdrawn"));
      var memory = memories.current(output.key().id());
      if (!current.equals(output)
          || !memory.home().equals(request.home())
          || !(memory.state() == MemoryState.ACTIVE || memory.state() == MemoryState.COLD))
        throw new IllegalStateException("Memory output changed");
    }

    public Archive.Precomputed embedding(String text) {
      require();
      if (queryAttempted) throw new IllegalStateException("Candidate inference already attempted");
      String expected =
          request.proposal().summary().strip() + "\n" + request.proposal().scope().strip();
      if (!text.equals(expected)) throw new IllegalStateException("Candidate input changed");
      queryAttempted = true;
      var permit = permit(EmbeddingPermit.Mode.QUERY);
      if (lanes != null) {
        var bound = lanes.forQuery(permit);
        var query =
            bound.query(
                bound.active(EmbeddingSlot.PROSE),
                text,
                usage(UsageAttribution.Operation.EMBEDDING_QUERY));
        completed = new Archive.Precomputed(null, text, query);
      } else {
        var vector =
            embeddings
                .forOperation(permit)
                .embed(text, usage(UsageAttribution.Operation.EMBEDDING_QUERY));
        completed = new Archive.Precomputed(vector.clone(), text);
      }
      uncertain.remove(pendingBatch);
      return completed;
    }

    public List<Memory> candidates(Archive.Precomputed query, int limit) {
      if (query != completed || completed == null || limit < 1 || limit > 5)
        throw new IllegalStateException("Candidate evidence differs");
      Supplier<List<Memory>> read =
          () ->
              transactions.inTransaction(
                  () -> {
                    memories.lock(request, pin, false);
                    require();
                    var found =
                        query.query() == null
                            ? memories.searchByVector(query.vector(), request.home(), limit)
                            : memories.searchByVector(query.query(), request.home(), limit);
                    var result = new ArrayList<Memory>();
                    for (var memory : found) {
                      if (!memory.home().equals(request.home()))
                        throw new IllegalStateException("Candidate escaped home");
                      candidates.put(memory.id(), content(memory));
                      result.add(memories.used(memory, Instant.now()));
                    }
                    require();
                    return List.copyOf(result);
                  });
      return query.query() == null ? read.get() : lanes.read(query.query().profile(), read);
    }

    private Budget.Claim claim() {
      if (ceiling != null && spent >= ceiling)
        throw new InferenceUnavailable("memory write inference allowance exhausted");
      return sources.claim(source);
    }

    public ChatRequest authorize(AgentDefinition definition, ChatRequest chat) {
      String hash =
          EmbeddingEvidence.digest(
              List.of("scribe-chat", definition.model(), chat.messages().toString()));
      if (attempted.contains(hash)) throw new IllegalStateException("Scribe attempt cannot replay");
      var result =
          transactions.inTransaction(
              () -> {
                require(false);
                var work =
                    system.admit(
                        SystemOperations.Purpose.SCRIBE,
                        request.home(),
                        new AuthorityIds.ScopeId(pin.scope()),
                        definition,
                        hash,
                        () -> require(false));
                var claim = claim();
                var authorization = work.claimed(chat, claim);
                sources.bind(source, authorization.binding(), hash);
                return authorization;
              });
      spent = result.binding().ordinal();
      attempted.add(hash);
      return chat.withAuthorization(result);
    }

    private EmbeddingPermit permit(EmbeddingPermit.Mode mode) {
      return new EmbeddingPermit() {
        public Mode mode() {
          return mode;
        }

        public void require() {
          Write.this.require(mode == Mode.DOCUMENT);
        }

        public CallAuthorization authorize(EmbeddingRequest request) {
          return authorize(new Task(mode, request.specifier(), request));
        }

        public CallAuthorization authorize(Task task) {
          if (task.mode() != mode) throw new IllegalStateException("Wrong memory embedding mode");
          return authorizeTask(task);
        }
      };
    }

    private synchronized CallAuthorization authorizeTask(EmbeddingPermit.Task task) {
      boolean indexing = task.mode() == EmbeddingPermit.Mode.DOCUMENT;
      require(indexing);
      String text =
          task.mode() == EmbeddingPermit.Mode.QUERY
              ? request.proposal().summary().strip() + "\n" + request.proposal().scope().strip()
              : Objects.requireNonNull(output, "Committed output required").text();
      if (!task.sources().equals(List.of(text)))
        throw new IllegalStateException("Memory embedding input changed");
      // QUERY and DOCUMENT may have identical final inputs in a deployment. A failed
      // submission cannot be replayed under a different task prefix or profile slot.
      String effective =
          EmbeddingEvidence.digest(
              List.of(task.request().specifier(), task.request().input().toString()));
      if (uncertain.contains(effective))
        throw new IllegalStateException("Equivalent embedding batch has an uncertain result");
      String hash = EmbeddingEvidence.digest(List.of("memory-write", EmbeddingEvidence.task(task)));
      if (attempted.contains(hash))
        throw new IllegalStateException("Embedding attempt cannot replay");
      var result =
          transactions.inTransaction(
              () -> {
                require(indexing);
                var resultAuthorization =
                    foundation.authorize(
                        new SemanticEmbedding.Operation(
                            task.mode() == EmbeddingPermit.Mode.QUERY
                                ? SystemOperations.Purpose.SEMANTIC_QUERY
                                : SystemOperations.Purpose.MEMORY_WRITE,
                            request.home(),
                            new AuthorityIds.ScopeId(pin.scope()),
                            fingerprint,
                            () -> require(indexing)),
                        task,
                        this::claim);
                sources.bind(source, resultAuthorization.binding(), hash);
                return resultAuthorization;
              });
      spent = result.binding().ordinal();
      attempted.add(hash);
      pendingBatch = effective;
      uncertain.add(effective);
      return result;
    }

    public void mutation(Verdict verdict) {
      memories.lock(request, pin, false);
      require();
      if (verdict.kind() != VerdictKind.NEW) {
        String expected = candidates.get(verdict.targetId());
        if (expected == null || !expected.equals(content(memories.current(verdict.targetId()))))
          throw new CallerFault("Scribe target changed or was not admitted");
      }
    }

    public void provenance(String id) {
      if (request.origin().learning() != null) memories.provenance(id, request.origin().learning());
    }

    public void embed(Memory memory) {
      // Archive has committed and released its content transaction before invoking this method.
      processing = true;
      try {
        if (Integer.valueOf(0).equals(ceiling)) return;
        output =
            outputs
                .source(
                    EmbeddingWorkRepository.Key.of(
                        EmbeddingWorkRepository.Store.MEMORIES, memory.id()))
                .orElseThrow(() -> new IllegalStateException("Missing committed memory output"));
        require();
        if (lanes != null) {
          var delegate = permit(EmbeddingPermit.Mode.DOCUMENT);
          var publication =
              new EmbeddingAuthorization() {
                public void require() {
                  Write.this.require();
                }

                public void sources(List<EmbeddingWorkRepository.Key> keys) {
                  if (!keys.equals(List.of(output.key())))
                    throw new IllegalStateException("Write cannot repair other sources");
                  require();
                }

                public CallAuthorization authorize(EmbeddingRequest request) {
                  return delegate.authorize(request);
                }

                public CallAuthorization authorize(EmbeddingPermit.Task task) {
                  return delegate.authorize(task);
                }

                public void publicationFence() {
                  memories.lock(request, pin, true);
                  require();
                }

                public void published() {
                  require();
                  uncertain.remove(pendingBatch);
                }
              };
          lanes
              .forWrite(publication)
              .repair(output.key(), usage(UsageAttribution.Operation.EMBEDDING_WRITE));
        } else {
          float[] vector;
          if (completed != null
              && completed.query() == null
              && completed.text().equals(output.text())) vector = completed.vector();
          else {
            // An uncertain legacy query used the identical encoder/input: never retry it as
            // indexing.
            if (queryAttempted)
              throw new EmbeddingException(
                  "Candidate embedding unavailable; equivalent batch cannot replay");
            vector =
                embeddings
                    .forOperation(permit(EmbeddingPermit.Mode.DOCUMENT))
                    .embed(output.text(), usage(UsageAttribution.Operation.EMBEDDING_WRITE));
          }
          var captured = vector;
          transactions.inTransaction(
              () -> {
                memories.publishLegacy(request, pin, output, captured, this::require);
                return null;
              });
        }
      } catch (RuntimeException failure) {
        // Content is already durable. Never retry inference or expose private payloads in reasons.
        log.warn(
            "Memory {} retained without complete write-time embeddings ({})",
            memory.id(),
            failure.getClass().getSimpleName());
      }
    }

    private UsageAttribution usage(UsageAttribution.Operation operation) {
      return UsageAttribution.system(request.home().project(), operation);
    }

    public void close() {
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
