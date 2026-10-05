package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryIds;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.protocol.Verdict;
import io.aeyer.plowshare.protocol.VerdictKind;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.server.embedding.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The archive's semantics: verdicts, the two tiers, and everything that decides where a memory may
 * be returned from.
 *
 * <p>Ported from Excalibur's {@code archive/archive.py}. Deterministic and model-free — every rule
 * below is ordinary code, which is why most of the test suite and most of the bugs live here. The
 * MCP surface is a client of this class, never a peer of it.
 *
 * <h2>Verdicts judge shape, never truth</h2>
 *
 * <p>A verdict says whether a proposal is new, more detail on something held, or a replacement. It
 * never says whether a claim is <em>right</em>: see {@link io.aeyer.plowshare.protocol.VerdictKind}
 * for the measurement that settled that. Newest wins, nothing is refused for being wrong, and
 * wrongness arrives afterwards through {@link #invalidate}, from a caller with the context to know.
 *
 * <h2>Two tiers, and shadowing is not supersession</h2>
 *
 * <p>This half is <b>not</b> a port — Excalibur has one pool. A memory has exactly one home, a
 * project or global, so there are no copies and no sync. A project memory that contradicts a global
 * one <em>shadows</em> it: the global record stays {@code active} and gets no tombstone, because it
 * is not wrong, it is simply not the answer for this project. Supersession across tiers is
 * therefore refused outright (see {@link #requireTarget}) — one project retiring a global memory
 * would take it away from every other project, which is exactly the confusion the two mechanisms
 * exist to keep apart.
 *
 * <h2>Atomicity, and where the model call is not</h2>
 *
 * <p>A supersession is two saves — the new record and the retired one — and they must not
 * half-apply. Excalibur bought that with an exclusive lock file and one server per archive; here it
 * is a database transaction. Nothing in this class takes a lock of its own: the <em>mechanism</em>
 * arrives as a {@link UnitOfWork}, so this class stays framework-free, but <b>the boundary is drawn
 * here and not by the caller</b>, and that is a correction. It was an annotation on every {@code
 * MemoryController} method, which wrapped the whole endpoint — the call to the embedding model
 * included — and cost two live bugs: an unexpected exception out of the model client rolled a
 * committed write back, and a stalled model pinned a pooled database connection for as long as it
 * stalled. Only this class knows which statements belong together, so only this class can put the
 * model call outside them. {@link UnitOfWork} carries the full account.
 *
 * <p>The rule that falls out of it, for anything added here later: <b>a model call never happens
 * inside {@code inTransaction}.</b> On the write path the embedding is deferred until after the
 * commit; on the recall path the question is embedded before the unit of work opens.
 *
 * <h2>Recall is a query, and the librarian is not ported</h2>
 *
 * <p>Excalibur recalls by spawning a small local model with a turn budget and a tool set and
 * letting it grep the index and choose. That agent has an iteration cap, a truncation mode and a
 * degraded mode, and on 2026-08-24 the same twenty-four questions scored 2/24 on one model and
 * 14/24 on another — one truncated run returning the model's own internal deliberation as the
 * answer. {@link #recall} is a vector query: no loop, no cap, nothing to leak. The rules that used
 * to live in a prompt — retired records excluded, project ahead of global — are applied by ordinary
 * code afterwards. There is no {@code Librarian} class here and there is not meant to be one; the
 * only thing a model still does is turn text into a vector.
 */
public class Archive implements UsageAware {
  private DualEmbeddings dualEmbeddings;

  public void useDualEmbeddings(DualEmbeddings embeddings) {
    dualEmbeddings = java.util.Objects.requireNonNull(embeddings);
  }

  private UsageOwners usageOwners = UsageOwners.NONE;

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  public boolean accountingEnabled() {
    return usageOwners != UsageOwners.NONE;
  }

  public UsageAttribution usage(
      io.aeyer.plowshare.protocol.Home home, String handle, UsageAttribution.Operation operation) {
    return usageOwners.in(home, handle, operation);
  }

  private static final Logger log = LoggerFactory.getLogger(Archive.class);

  private final MemoryStore store;
  private final ReasonLog reasons;
  private final EmbeddingClient embeddings;
  private final UnitOfWork unitOfWork;
  private final int maxBodyChars;
  private final int indexThreshold;
  private final double halfLifeDays;
  private final Supplier<Instant> now;
  private final Supplier<String> idFactory;

  /**
   * Production wiring: the real clock, real ids, and a real transaction.
   *
   * <p>{@code unitOfWork} has no default here on purpose. The version of this constructor without
   * one let the archive be wired into a server with no rollback boundary at all and nothing to say
   * so, which is exactly the state the five deleted {@code @Transactional} annotations turned out
   * to be in: removing all five left 170/170 green, because the one controller test builds no
   * Spring context and applies no proxy.
   */
  public Archive(
      MemoryStore store,
      ReasonLog reasons,
      EmbeddingClient embeddings,
      UnitOfWork unitOfWork,
      int maxBodyChars,
      int indexThreshold,
      double halfLifeDays) {
    this(
        store,
        reasons,
        embeddings,
        unitOfWork,
        maxBodyChars,
        indexThreshold,
        halfLifeDays,
        Instant::now,
        null);
  }

  /**
   * Test wiring: an injected clock and id factory, and {@link UnitOfWork#NONE}.
   *
   * <p>The tests that use this assert on the archive's rules against a real Postgres in autocommit,
   * where a rollback boundary changes nothing they look at. Atomicity itself is asserted where the
   * real transaction manager is, in {@code TransactionBoundaryTest}.
   */
  public Archive(
      MemoryStore store,
      ReasonLog reasons,
      EmbeddingClient embeddings,
      int maxBodyChars,
      int indexThreshold,
      double halfLifeDays,
      Supplier<Instant> now,
      Supplier<String> idFactory) {
    this(
        store,
        reasons,
        embeddings,
        UnitOfWork.NONE,
        maxBodyChars,
        indexThreshold,
        halfLifeDays,
        now,
        idFactory);
  }

  /**
   * @param store where the rows live
   * @param reasons where the account of each write goes. Written inside the same unit of work as
   *     the memory, so there is no committed write with no account of itself and no account of a
   *     write that rolled back.
   * @param embeddings what turns text into a vector, on the write path and on recall. An interface
   *     rather than the HTTP class so a test can stub it: the rules below are what the tests are
   *     for, and a live model would make them measure the model instead. Excalibur's golden-set
   *     eval scored anywhere from 2/4 to 4/4 on identical code.
   * @param unitOfWork how the multi-statement operations below are made atomic. Never {@code null};
   *     {@link UnitOfWork#NONE} says "no transaction" out loud rather than by omission.
   * @param maxBodyChars the body length limit enforced by {@link Validation}
   * @param indexThreshold how many {@code active} memories one tier may hold before the
   *     lowest-scoring unpinned ones are demoted. Excalibur calls this {@code toc_threshold}; it is
   *     the librarian's attention budget, and it is applied <em>per tier</em> here because the
   *     index is per tier — a busy project must not be able to push global memories out of an index
   *     it does not read.
   * @param halfLifeDays decay half-life, handed to {@link Scoring}
   * @param now the clock, injected so a test can move time. Excalibur injects the same way, and a
   *     demotion pass that read the clock twice could score two memories against two different
   *     instants.
   * @param idFactory id minting, or {@code null} for the default. Injected so a test can assert on
   *     ordering it chose rather than on random hex.
   */
  public Archive(
      MemoryStore store,
      ReasonLog reasons,
      EmbeddingClient embeddings,
      UnitOfWork unitOfWork,
      int maxBodyChars,
      int indexThreshold,
      double halfLifeDays,
      Supplier<Instant> now,
      Supplier<String> idFactory) {
    this.store = store;
    this.reasons = reasons;
    this.embeddings = embeddings;
    this.unitOfWork = unitOfWork;
    this.maxBodyChars = maxBodyChars;
    this.indexThreshold = indexThreshold;
    this.halfLifeDays = halfLifeDays;
    this.now = now;
    this.idFactory = idFactory != null ? idFactory : () -> MemoryIds.newId(now.get());
  }

  public int maxBodyChars() {
    return maxBodyChars;
  }

  // --- reads ---------------------------------------------------------------

  /**
   * What one tier holds, as index lines: {@code active} records only.
   *
   * <p>The index is what an agent is shown before it asks for anything, so a retired record here is
   * a question answered with a memory the archive no longer stands behind. On 2026-08-18 exactly
   * that was measured in Excalibur: asked a question whose only match was an invalidated memory,
   * the librarian returned that known-false memory 5 runs out of 5.
   */
  public List<TocEntry> index(Home home) {
    return store.index(home);
  }

  /**
   * Fetch by id without touching counters — for internal and agent use.
   *
   * <p>Resolves a memory in any state, tombstones included. That is not an oversight: a tombstone
   * must stay readable, or the reason a fact stopped being true becomes unreachable and the next
   * agent to meet the same evidence writes the stale fact straight back in.
   *
   * @throws ArchiveException if no memory was ever written under this id
   */
  public Memory get(String id) {
    return store.load(id).orElseThrow(() -> new ArchiveException("no memory with id " + id));
  }

  /**
   * Fetch verbatim and count the use.
   *
   * <p>Counters move here and nowhere else on the read path: {@link #index} returns no bodies, so
   * surveying the archive is not using it. The count and the stamp are the only evidence {@link
   * Scoring} has that a memory is still earning its place.
   *
   * <p><b>This writes.</b> Every id passed here is saved back in a new state, which is why {@link
   * MemoryStore#save} must never list the embedding column — the archive would lose the vector of
   * every memory anyone actually recalled — and why reading a tombstone leaves it retired: the
   * state is untouched, so nothing returns to the search path because somebody looked at it.
   *
   * @throws ArchiveException if any id was never written; nothing is saved for the ids before it
   *     either. That used to depend on the caller having opened a transaction, which nothing made
   *     it do; the batch is now a {@link UnitOfWork} of its own, so a batch naming one unknown id
   *     counts no uses at all rather than counting the ids that happened to sort before it.
   */
  public List<Memory> read(List<String> ids) {
    return unitOfWork.inTransaction(
        () -> {
          // One instant for the whole batch: two memories read together were
          // used together, and stamping them a millisecond apart would let
          // decay rank them against each other for no reason.
          Instant at = now.get();
          // Every id resolved before anything is saved, so a batch naming one
          // unknown id counts no uses at all rather than counting the ids that
          // happened to sort before it. Outside a transaction that difference
          // is the whole of it — and UnitOfWork.NONE is exactly what the
          // archive is wired with in ArchiveTest, which is where
          // a_batch_naming_one_unknown_id_counts_no_uses_at_all holds this
          // loop shape down. Folding the save into this loop leaves that test
          // red and every other test in the suite green.
          List<Memory> found = new ArrayList<>(ids.size());
          for (String id : ids) {
            found.add(get(id));
          }
          return countUses(found, at);
        });
  }

  /**
   * Recall by meaning: the memories nearest this question, in this home.
   *
   * <p><b>This is the method the port exists for.</b> Excalibur asks a small local model to do this
   * job, and the model is the whole failure surface — turn budgets, blind greps, a prompt one model
   * follows and another ignores, 2/24 against 14/24 on the same corpus on the same day. Here the
   * question becomes a vector and the answer is {@code ORDER BY embedding <=> ?}. The three rules
   * that mattered are enforced by the query and by the ten lines below, mechanically:
   *
   * <ol>
   *   <li>Retired records never appear — {@link MemoryStore#searchByVector} filters on state, so
   *       there is no instruction left to skip.
   *   <li>{@code cold} records still appear: unused is not untrue, and a demoted memory that could
   *       not be found again would make demotion a deletion.
   *   <li>Project shadows global.
   * </ol>
   *
   * <h3>Shadowing, and what it deliberately does not do</h3>
   *
   * <p>Two searches — this project's tier, then global — concatenated project first and cut to
   * {@code limit}. That is all. <b>No attempt is made to decide whether a project result and a
   * global result are about the same subject</b>, and the simplification is deliberate rather than
   * unfinished: judging "these two memories are about the same thing" is exactly the judgement that
   * defeated Excalibur's scribe four times over, and putting a model back in the recall path to
   * make it would undo the point of this task. Project-ahead-of-global plus the limit gets the
   * observable behaviour — ask a project a question and its own answer comes first — with nothing
   * that can be wrong in an interesting way. Nothing is retired or hidden by this: a shadowed
   * global memory stays {@code active}, keeps its place in the global index, and is still the
   * answer for every other project.
   *
   * <p>A global {@code home} searches the global tier alone. There is no project to shadow with,
   * and searching every project's tier would hand one project's local facts to a caller that asked
   * what holds everywhere.
   *
   * <p><b>This counts a use</b>, through the same path as {@link #read}, because it returns bodies
   * — and returning bodies is what "a use" means here, which is why {@link #index} does not count
   * one. Excalibur does the same thing one layer up: its librarian ends {@code recall} by calling
   * {@code archive.read(ids)}. Without this, {@link Scoring} would never receive a signal from the
   * one operation the archive exists to serve, and decay would rank memories purely by age.
   *
   * <h3>What the search could not see, and why it is returned</h3>
   *
   * <p>{@link MemoryStore#searchByVector} filters {@code embedding IS NOT NULL}, so a memory
   * written while the embedding endpoint was down is unreachable here however the question is
   * phrased. That is the right behaviour — it has no vector to be compared with — but on its own it
   * is <b>invisible</b>, and invisible is the failure this project exists to avoid: an archive
   * holding exactly one memory, unembedded, answered "nothing is close to that question", and an
   * agent told that reasonably tries another question, forever. So the count travels back with the
   * hits. A caller can then tell "nothing matched" from "some of the archive could not be
   * searched", which is the whole of the difference between a question worth rephrasing and an
   * archive worth repairing.
   *
   * <p>Counted inside the same unit of work as the searches, so the number reported is the number
   * that search actually skipped, rather than an answer to a second, later question. It covers
   * every tier this call searched: a project recall counts the project's tier and global, because
   * both were drawn on.
   *
   * @param question what is being asked, in prose
   * @param home the tier to answer from; a project also draws on global
   * @param limit how many memories to return at most
   * @throws EmbeddingException if the question could not be embedded. Not swallowed, unlike on the
   *     write path: a write with no vector still keeps the memory, but a recall with no query
   *     vector has nothing to rank by, and quietly returning the arbitrary rows some fallback
   *     ordering produced would be indistinguishable from a working search.
   */
  public Recall recall(String question, Home home, int limit) {
    return recall(
        question,
        home,
        limit,
        usageOwners.in(home, null, UsageAttribution.Operation.EMBEDDING_QUERY));
  }

  public Recall recall(String question, Home home, int limit, UsageAttribution owner) {
    if (limit <= 0) {
      // Ahead of the embedding, and that is the whole of what this clause
      // buys: a caller paginating to the end must not pay a model call to
      // be told what it already knows. The overload below repeats the
      // check because it is a public entry point of its own, and there it
      // costs nothing at all.
      return new Recall(List.of(), 0);
    }
    return recall(embedding(question, owner), home, limit);
  }

  /**
   * The same search, against a vector somebody has already paid for.
   *
   * <p>Exists so that a caller which had to embed something on its way here does not pay for the
   * same numbers twice. {@code Scribe} is that caller: it retrieves candidates with the <em>same
   * text</em> the memory it is judging will be embedded for, so its query vector is the memory's
   * vector, and handing it on is the difference between one model call per write and two. See
   * {@link #applyVerdict(MemoryProposal, Verdict, Home, Precomputed)} for the guard that makes
   * handing it on safe.
   *
   * @param query the vector and the text it came from. The text is not used here — a search is a
   *     search — and is carried because the pair is one fact and splitting it is what lets a vector
   *     drift away from what it means.
   */
  public Recall recall(Precomputed query, Home home, int limit) {
    if (limit <= 0) {
      return new Recall(List.of(), 0);
    }
    if (dualEmbeddings != null) {
      if (query.query() == null)
        throw new IllegalArgumentException("dual retrieval needs a captured embedding space");
      return dualEmbeddings.read(
          query.query().profile(),
          () -> {
            Found found = search(query.query(), home, limit);
            return new Recall(countUses(found.memories(), now.get()), found.unsearchable());
          });
    }
    return unitOfWork.inTransaction(
        () -> {
          Found found = search(query.vector(), home, limit);
          return new Recall(countUses(found.memories(), now.get()), found.unsearchable());
        });
  }

  /**
   * A vector, and the exact text it was computed from.
   *
   * <p>The two travel together because <b>the pair is what makes reusing a vector safe</b>. A
   * vector on its own is a number a caller asserts is right; with the text beside it the archive
   * can check, which is what {@link #applyVerdict(MemoryProposal, Verdict, Home, Precomputed)} does
   * before it stores one.
   *
   * <p>In dual mode the captured query also carries the exact prose space and source-independent
   * read policy. It is reused only for reads: writes repair both slots from committed source text
   * with their document prefixes, which may differ from this query's prefix.
   *
   * <p>The legacy array is not copied on the way in or out. Its one producer is {@link
   * #embedding(String)}, which builds it and hands it over, and no caller keeps a reference to
   * mutate — a defensive copy per write would be a cost with no failure behind it, which is the
   * justification this project declines elsewhere.
   *
   * <p>{@code float[]} makes this record's generated {@code equals} identity- based on the vector.
   * Nothing compares two of these, and nothing should: the question worth asking is whether the
   * <em>text</em> matches, and that is asked directly.
   */
  public record Precomputed(float[] vector, String text, EmbeddingQuery query) {
    public Precomputed(float[] vector, String text) {
      this(vector, text, null);
    }
  }

  /**
   * Turn text into a vector, once, keeping the text beside it.
   *
   * <p>In legacy mode this is the single place the write path's model call is made from — {@link
   * #recall(String, Home, int)} goes through here and so does {@code Scribe}, which is what lets
   * the scribe hand the archive back a vector the archive would otherwise have computed again.
   *
   * @throws io.aeyer.plowshare.server.llm.EmbeddingException if the endpoint could not answer. Not
   *     swallowed: this is the read path's rule, and the write path's own swallow lives in {@link
   *     #embed(Memory, Precomputed)} where the memory is already committed.
   */
  public Precomputed embedding(String text) {
    return embedding(
        text, usageOwners.in(Home.global(), null, UsageAttribution.Operation.EMBEDDING_QUERY));
  }

  public Precomputed embedding(String text, UsageAttribution owner) {
    // Embedded before any unit of work opens, and the order is the point:
    // this is a call to a model that can take a connect-plus-read timeout
    // to answer, and inside a transaction it would hold a pooled database
    // connection for all of it. Ten stalled recalls against Hikari's
    // default pool of ten stop the server for everyone, reads included.
    // Nothing has been read from the database yet, so there is nothing for
    // this call to be consistent with.
    if (dualEmbeddings != null) {
      var query = dualEmbeddings.query(dualEmbeddings.active(EmbeddingSlot.PROSE), text, owner);
      return new Precomputed(query.values(), text, query);
    }
    return new Precomputed(
        EmbeddingClient.owned(
            embeddings,
            text,
            owner.forOperation(UsageAttribution.Operation.EMBEDDING_QUERY, owner.agentName())),
        text);
  }

  /**
   * The same search, as index lines, <b>without counting a use</b>.
   *
   * <h3>Why this exists, which is a number</h3>
   *
   * <p>{@link #recall} counts a use on every memory it returns, deliberately and for a documented
   * reason: it hands back bodies. {@code Curator}'s triage runs one near-neighbour query per
   * candidate against the global tier, so a nightly pass over a 200-memory project put up to {@code
   * NEIGHBOURS} use counters up per candidate — a thousand global memories marked as used by a pass
   * that read none of them. Those counters feed {@link Scoring}, which feeds demotion, so the pass
   * was quietly reordering the tier every project reads. Recorded by Task 9 and owed to Task 10.
   *
   * <p><b>It returns {@link TocEntry} rather than {@code Memory}, and that is the same decision
   * rather than a second one.</b> "Returning bodies is what a use means here" is {@code recall}'s
   * own rule; a method that returned bodies without counting would contradict it, and the next
   * reader would have to work out which sentence was true. So this returns exactly what triage puts
   * in a prompt — id, summary, scope, no bodies — which is also what {@code promotion_judge} is
   * given a {@code memory_read} in order to go and fetch for itself.
   *
   * <p>{@code unsearchable} is carried for the same reason {@link Recall} carries it: an unembedded
   * global memory is one this search cannot see however the question is phrased, and a caller
   * ruling something out by "global does not already hold it" is entitled to know the search was
   * partial. Every entry's own {@code unsearchable} flag is {@code false} by construction — {@link
   * MemoryStore#searchByVector} filters {@code embedding IS NOT NULL}, so a row that has no vector
   * is not among these.
   *
   * @throws EmbeddingException if the question could not be embedded, on {@link #recall}'s
   *     reasoning exactly: there is nothing to rank by, and an arbitrary ordering would be
   *     indistinguishable from a working search
   */
  public Survey survey(String question, Home home, int limit) {
    return survey(
        question,
        home,
        limit,
        usageOwners.in(home, null, UsageAttribution.Operation.EMBEDDING_QUERY));
  }

  public Survey survey(String question, Home home, int limit, UsageAttribution owner) {
    if (limit <= 0) {
      return new Survey(List.of(), 0);
    }
    if (dualEmbeddings != null) {
      EmbeddingQuery query = embedding(question, owner).query();
      return dualEmbeddings.read(
          query.profile(),
          () -> {
            Found found = search(query, home, limit);
            return new Survey(
                found.memories().stream()
                    .map(m -> new TocEntry(m.id(), m.summary(), m.scope(), false))
                    .toList(),
                found.unsearchable());
          });
    }
    float[] query =
        EmbeddingClient.owned(
            embeddings,
            question,
            owner.forOperation(UsageAttribution.Operation.EMBEDDING_QUERY, owner.agentName()));

    return unitOfWork.inTransaction(
        () -> {
          Found found = search(query, home, limit);
          List<TocEntry> lines = new ArrayList<>(found.memories().size());
          for (Memory memory : found.memories()) {
            lines.add(new TocEntry(memory.id(), memory.summary(), memory.scope(), false));
          }
          return new Survey(List.copyOf(lines), found.unsearchable());
        });
  }

  /**
   * What a survey found, and how much of the archive it could not look at.
   *
   * @param found the hits as index lines, nearest first, project tier ahead of global
   * @param unsearchable {@link Recall#unsearchable} exactly, and it means the same thing: a
   *     non-zero value says this answer is partial rather than complete
   */
  public record Survey(List<TocEntry> found, int unsearchable) {}

  /** What one search returned, before either caller decides what to do with it. */
  private record Found(List<Memory> memories, int unsearchable) {}

  /**
   * The tier logic, shared so the two callers cannot drift.
   *
   * <p>Runs inside the caller's unit of work — every statement here is a read and they have to
   * agree with each other, which is the same reason {@code unsearchable} is counted alongside the
   * searches rather than afterwards.
   */
  private Found search(EmbeddingQuery query, Home home, int limit) {
    var hits = new ArrayList<>(store.searchByVector(query, home, limit));
    int missing = store.countUnsearchable(query.profile(), home);
    if (!home.isGlobal()) {
      hits.addAll(store.searchByVector(query, Home.global(), limit));
      missing += store.countUnsearchable(query.profile(), Home.global());
    }
    return new Found(List.copyOf(hits.subList(0, Math.min(limit, hits.size()))), missing);
  }

  private Found search(float[] query, Home home, int limit) {
    if (home.isGlobal()) {
      return new Found(
          store.searchByVector(query, Home.global(), limit),
          store.countUnsearchable(Home.global()));
    }
    // Each tier asked for the full limit, not a share of it: a project with
    // two memories must still be able to fill the rest of the answer from
    // global, and a fixed split would leave the caller short whenever one
    // tier is thin.
    List<Memory> hits = new ArrayList<>(store.searchByVector(query, home, limit));
    hits.addAll(store.searchByVector(query, Home.global(), limit));
    // Truncated because each tier was asked for the whole limit, so two full
    // tiers hand back 2 x limit rows. `limit` comes straight from the model,
    // under a schema that promises "at most" — a project recall that quietly
    // returned twice what was asked for would break that promise on exactly
    // the tier combination the archive is normally used with.
    return new Found(
        hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : hits,
        store.countUnsearchable(home) + store.countUnsearchable(Home.global()));
  }

  /**
   * What a recall found, and how much of the archive it could not look at.
   *
   * @param memories the hits, nearest first, project tier ahead of global
   * @param unsearchable how many live memories in the tiers this recall drew on have no embedding
   *     and were therefore skipped by the vector query. Zero is the ordinary case and means the
   *     answer is complete: an empty {@code memories} with a zero here really does mean nothing in
   *     the archive is close to the question. A non-zero value means the answer is partial, and
   *     says so rather than leaving the caller to conclude the archive is empty.
   */
  public record Recall(List<Memory> memories, int unsearchable) {}

  /**
   * Give a vector to every live memory in one tier that has none, and report what happened to each.
   *
   * <p>The repair path for the state {@link Recall#unsearchable} makes visible. A memory written
   * while the embedding endpoint was down is complete except for its vector, and once the endpoint
   * is back the vector is one call away — but nothing was asking for it, so the memory stayed
   * unreachable by recall for as long as the archive lived.
   *
   * <p><b>No unit of work, deliberately.</b> This is n model calls, and the rule this class keeps
   * is that a model call never happens inside {@code inTransaction} — n of them inside one would
   * hold a pooled connection for the length of the whole pass. It is safe without one because each
   * {@link MemoryStore#saveEmbedding} is a single statement that touches a single row and no other
   * column: memories repaired before a failure stay repaired, which is strictly better than where
   * they were, and a second run picks up whatever is left. There is nothing here that can
   * half-apply.
   *
   * <p>Not exposed as an MCP tool. Re-embedding is maintenance an operator does after fixing an
   * endpoint, not a judgement an agent should be making in the middle of its own work.
   */
  public Repair reembed(Home home) {
    return reembed(home, usageOwners.in(home, null, UsageAttribution.Operation.EMBEDDING_REPAIR));
  }

  public Repair reembed(Home home, UsageAttribution owner) {
    List<Memory> owed = store.unsearchable(home);
    List<String> repaired = new ArrayList<>(owed.size());
    List<String> failed = new ArrayList<>();
    for (Memory memory : owed) {
      if (embed(
          memory,
          null,
          owner.forOperation(UsageAttribution.Operation.EMBEDDING_REPAIR, owner.agentName()))) {
        repaired.add(memory.id());
      } else {
        failed.add(memory.id());
      }
    }
    return new Repair(List.copyOf(repaired), List.copyOf(failed));
  }

  /**
   * The outcome of a re-embed pass, memory by memory.
   *
   * <p>Both lists, rather than a count of successes: an operator running this because the endpoint
   * was down needs to know whether it is back, and "7 repaired" alone cannot say whether the other
   * 3 failed or were never there.
   *
   * @param repaired ids that now have a vector and are reachable by recall
   * @param failed ids the embedding endpoint still would not answer for. They are unchanged — still
   *     stored, still active, still readable by id.
   */
  public record Repair(List<String> repaired, List<String> failed) {}

  /**
   * Stamp a use on each of these memories and save them back.
   *
   * <p>The one place counters move, so "what counts as a use" is a question with a single answer in
   * the code. It is safe to save here only because {@link MemoryStore#save} does not list the
   * {@code embedding} column: if it did, every memory recall returned would have its vector erased,
   * and the archive would get less searchable the more it was used.
   */
  private List<Memory> countUses(List<Memory> memories, Instant at) {
    List<Memory> used = new ArrayList<>(memories.size());
    for (Memory memory : memories) {
      Memory stamped = Lifecycle.recordUse(memory, at);
      store.save(stamped);
      used.add(stamped);
    }
    return List.copyOf(used);
  }

  // --- writes --------------------------------------------------------------

  /**
   * File a proposal according to a verdict, in one tier.
   *
   * <p>Validates first, so a malformed proposal cannot retire a target on its way to being
   * rejected. Then mints, writes, applies the lifecycle transition to the target if the verdict
   * names one, and finally demotes whatever the tier no longer has room for. All of that is one
   * transaction.
   *
   * <p><b>The embedding is not.</b> It runs after the commit, on a record that is already safe on
   * disk — see {@link #embed(Memory)} for the whole of why. Nothing between here and the commit
   * calls a model.
   *
   * <h3>{@code verdict.reason()} is kept, and that is what {@link ReasonLog} is</h3>
   *
   * <p>The spec asks that every scribe fallback say which one it was "so a year later <b>the
   * archive</b> can distinguish 'no scribe judged this' from 'the scribe was busy'". The reason
   * used to reach {@link WriteResult} below and stop there — as far as the HTTP response and the
   * client's renderer, which serves whoever made the write and nobody reading the archive
   * afterwards. It is now also a row, written inside the unit of work below so that no committed
   * write lacks its account and no rolled-back one leaves one behind. {@code
   * V5__memory_reasons.sql} carries why it is a table rather than a column, and the three questions
   * its shape had to settle.
   *
   * <p>The target recorded is the one this write <em>acted on</em>, which for {@link
   * io.aeyer.plowshare.protocol.VerdictKind#NEW} is nothing. A caller that filled the field in
   * anyway had it ignored by {@link #create}, and recording it would put a foreign key on a value
   * the write never used — failing a write the archive was otherwise happy with, over a field that
   * names nothing.
   *
   * @throws ValidationException if the proposal is malformed
   * @throws ArchiveException if the verdict names a target that does not exist, or one in another
   *     tier
   */
  public WriteResult applyVerdict(MemoryProposal proposal, Verdict verdict, Home home) {
    return applyVerdict(proposal, verdict, home, null);
  }

  /**
   * The same, reusing a vector the caller already paid a model call for.
   *
   * <p>{@code Scribe} retrieves its candidates with the same text this write's memory will be
   * embedded for, so its query vector <em>is</em> the memory's vector. Handing it on is the
   * difference between one model call per write and two — and 3a measured that the second one is
   * paid on <b>every</b> write past {@code Scribe.judged}'s three guards, not only the writes a
   * model rules on, because the candidate query happens before the empty-candidate short circuit.
   *
   * <h3>The saving is not free, and this is what it costs</h3>
   *
   * <p>The identity it rests on — the scribe's query text and {@link #embeddedText(Memory)} — is
   * <b>incidental</b>: two expressions in two classes that happen to agree. Today a drift only
   * makes search worse, since the two vectors are computed separately and each is right for its own
   * use. <b>Once one is reused for the other it becomes a wrong vector against a right memory</b>,
   * which is silent index corruption: the row is perfect, the search misses it, and nothing
   * anywhere goes red.
   *
   * <p>So the vector arrives <em>with the text it came from</em> and is compared against {@link
   * #embeddedText(Memory)} before anything is stored. A mismatch is an {@link
   * IllegalArgumentException} and the whole write is refused — <b>refused, not stored without the
   * vector</b>, because a caller handing over a vector from other text is this server being wrong,
   * and the failure has to be one somebody fixes rather than one the archive absorbs one memory at
   * a time.
   *
   * <p><b>Checked inside the unit of work, and that placement is the mechanism.</b> The text a
   * memory will be embedded for is not known until the memory exists, and {@link #embed(Memory,
   * Precomputed)} may not throw — {@code PromotionQueue} compensates on anything that escapes
   * {@code promote} after its commit. Checking here means the refusal rolls the write back rather
   * than landing after it: nothing is half-done, and the post-commit path stays unable to fail.
   *
   * <p>The drift this guards against was <b>real when it was written</b>, not hypothetical: {@code
   * newMemory} strips the summary and the scope, so {@code embeddedText} is the stripped text,
   * while {@code Scribe.question} joined the raw ones. A proposal with whitespace at either end
   * therefore produced two different strings. {@code Scribe.question} strips now, and {@code
   * a_write_whose_summary_is_padded_is_still_filed} is what fails if it stops.
   *
   * @param precomputed a vector and the text it was computed from, or {@code null} to let this
   *     method embed. {@code null} is the ordinary case for every caller but the write path — and
   *     it is also what the scribe hands back when it never got as far as embedding anything.
   * @throws IllegalArgumentException if {@code precomputed}'s text is not the text this write's
   *     memory is embedded for
   */
  public WriteResult applyVerdict(
      MemoryProposal proposal, Verdict verdict, Home home, Precomputed precomputed) {
    return applyVerdict(proposal, verdict, home, precomputed, id -> {});
  }

  /** Attach validated source pointers in the same transaction as the judged memory. */
  public WriteResult applyVerdict(
      MemoryProposal proposal,
      Verdict verdict,
      Home home,
      Precomputed precomputed,
      java.util.function.Consumer<String> provenance) {
    return applyVerdict(
        proposal,
        verdict,
        home,
        precomputed,
        provenance,
        usageOwners.in(home, null, UsageAttribution.Operation.EMBEDDING_WRITE));
  }

  public WriteResult applyVerdict(
      MemoryProposal proposal,
      Verdict verdict,
      Home home,
      Precomputed precomputed,
      java.util.function.Consumer<String> provenance,
      UsageAttribution owner) {
    // Outside the transaction: a malformed proposal is refused before a
    // connection is taken at all, and this reads nothing.
    Validation.check(proposal, maxBodyChars);

    Committed committed =
        unitOfWork.inTransaction(
            () -> {
              // A switch expression over the enum, not an if-chain with a
              // fallback: adding a fourth verdict kind would fail this to compile
              // rather than fall through to a runtime "unknown verdict" nobody
              // sees until a caller sends one.
              Filed filed =
                  switch (verdict.kind()) {
                    case NEW -> create(proposal, home);
                    case MERGED_INTO -> merge(proposal, verdict, home);
                    case SUPERSEDES -> supersede(proposal, verdict, home);
                  };
              requireComputedFromTheMemorysOwnText(filed.toEmbed(), precomputed);
              reasons.record(
                  new ReasonLog.Entry(
                      filed.memoryId(),
                      now.get(),
                      verdict.kind(),
                      verdict.kind() == VerdictKind.NEW ? null : verdict.targetId(),
                      verdict.reason()));
              provenance.accept(filed.memoryId());
              return new Committed(
                  new WriteResult(
                      verdict.kind(),
                      filed.memoryId(),
                      verdict.reason(),
                      verdict.targetId(),
                      demoteOverThreshold(home)),
                  filed.toEmbed());
            });

    // The commit has happened and the connection is back in the pool. Only
    // now is the model called, and only now is the sentence in embed()'s
    // javadoc — "the row is already committed by the time this runs" —
    // actually true. It was not, while the boundary was an annotation
    // around this whole method.
    if (committed.toEmbed() != null) {
      embed(
          committed.toEmbed(),
          precomputed,
          owner.forOperation(UsageAttribution.Operation.EMBEDDING_WRITE, owner.agentName()));
    }
    return committed.result();
  }

  /**
   * Refuse a vector that was computed from something other than this memory.
   *
   * <p>A no-op when there is no vector to check, and <b>a no-op when there is nothing to embed</b>:
   * a merge changes neither summary nor scope, so it stores no vector and a precomputed one is
   * simply unused. Refusing there would fire on a path where nothing could go wrong, which is a
   * guard that costs a write and buys nothing.
   */
  private static void requireComputedFromTheMemorysOwnText(
      Memory toEmbed, Precomputed precomputed) {
    if (toEmbed == null || precomputed == null) {
      return;
    }
    String owed = embeddedText(toEmbed);
    if (!owed.equals(precomputed.text())) {
      // The two texts are not in the message. They are a memory's summary
      // and scope -- the caller's own prose -- and this exception reaches
      // an HTTP response and a log; naming the mismatch is what an
      // operator needs, and quoting both sides of it is a second copy of
      // the write in a place nobody expects one.
      //
      // The offset is here because the lengths alone degenerate: an
      // earlier version read "37 characters of text that are not what this
      // memory is embedded for (37 characters)" whenever the two differed
      // only in content, which is the likeliest drift of all -- a strip, a
      // separator, a case fold. An offset never coincides.
      throw new IllegalArgumentException(
          "the vector offered for memory "
              + toEmbed.id()
              + " was not computed from the"
              + " text this memory is embedded for: the two are "
              + precomputed.text().length()
              + " and "
              + owed.length()
              + " characters and first differ at character "
              + firstDifference(owed, precomputed.text())
              + ". Storing it would put a wrong vector against a right memory,"
              + " which no search would ever report. Nothing was written.");
    }
  }

  /**
   * Where two texts first differ, or their common length if one is a prefix of the other.
   *
   * <p>The one thing about a mismatch that can be said without quoting either side, and the only
   * one that does not degenerate when the two are the same length. An operator with an offset can
   * find the difference in a second; one told "37 and 37 characters" has been told nothing.
   */
  private static int firstDifference(String owed, String offered) {
    int shared = Math.min(owed.length(), offered.length());
    for (int at = 0; at < shared; at++) {
      if (owed.charAt(at) != offered.charAt(at)) {
        return at;
      }
    }
    return shared;
  }

  /**
   * What one verdict arm did to the rows: the id the caller will be told, and the record that still
   * has no vector.
   *
   * <p>{@code toEmbed} is {@code null} when the verdict changed no embedded text — a merge appends
   * to a body and leaves summary and scope, which are what gets embedded, exactly as they were.
   */
  private record Filed(String memoryId, Memory toEmbed) {}

  /** One committed write: the caller's result, and the embedding still owed. */
  private record Committed(WriteResult result, Memory toEmbed) {}

  /**
   * Record that a memory stopped being true, keeping the memory.
   *
   * <p>The reason is the whole point. An invalidated record is kept, never deleted, precisely so
   * the next agent that rediscovers the stale fact from old code finds the account of why it
   * stopped holding instead of writing it back in.
   *
   * <p>Does not run a demotion pass, unlike {@link #applyVerdict}: this removes a record from the
   * index rather than adding one, so the tier can only have got emptier.
   *
   * @throws ArchiveException if no memory was ever written under this id
   */
  public Memory invalidate(String id, String reason, String by) {
    return unitOfWork.inTransaction(
        () -> {
          Memory dead = Lifecycle.invalidate(get(id), now.get(), by, reason);
          store.save(dead);
          return dead;
        });
  }

  /**
   * Move a memory to the global tier: a new global record, and the project record retired with a
   * forward link to it.
   *
   * <h2>Why this is not a verdict, and must not become one</h2>
   *
   * <p>Promotion looks like a supersession from the outside, and that resemblance is a trap this
   * design has already fallen into once — an early draft proposed promotion-as-supersession and was
   * wrong. {@link #requireTarget} refuses a cross-tier target outright, and that refusal stays
   * exactly as it is: its rule is about a <em>verdict a caller supplies</em>, which the archive has
   * no way to check the meaning of. Let a caller name a global memory as the target of a project
   * write and one project can retire a fact every other project depends on.
   *
   * <p>This is not that. It is an operation the archive performs knowing what it means, over a
   * memory it has resolved itself, in a direction it fixes: project to global, never the other way,
   * and with no tier argument for a caller to get wrong. So it sits <b>beside</b> that rule rather
   * than through it, and does its two saves directly rather than routing them through {@link
   * #applyVerdict}.
   *
   * <p>A new record and not a moved one. Rewriting the row's {@code project} column to NULL would
   * be one statement and would leave every id anyone already holds pointing at a memory that
   * silently changed tiers, with nothing anywhere recording that it happened. The project keeps its
   * tombstone, pointing forward at what the archive now answers with.
   *
   * <p><b>The embedding is after the commit</b>, exactly as {@link #applyVerdict} does it and for
   * the account in {@link #embed(Memory)}: this writes a new record, so it carries the same hazard,
   * and a promoted memory with no vector is a global memory recall cannot find — the state {@link
   * Recall#unsearchable} exists to make visible.
   *
   * <p>Runs a demotion pass over <em>global</em> and nowhere else. Global gained an active record,
   * so its index may now be over threshold; the project lost one, so, as in {@link #invalidate},
   * that tier can only have got emptier.
   *
   * <p><b>Whatever fell out of the global index is reported, and it did not used to be.</b> {@code
   * WriteResult.demoted} carries those ids "surfaced rather than silent" and the client's {@code
   * memory_write} renders them to the agent; this returned a bare {@link Memory} and could not, so
   * a promotion that pushed a memory every project reads out of the global index left the row
   * {@code cold} and said nothing to anybody. {@link Promotion} is that channel.
   *
   * <p>Not a {@link WriteResult}, and not an added {@code VerdictKind}. A promotion is <em>not a
   * verdict</em> — that is the whole of the section above, and the confusion this design records
   * having fallen into once — so handing back a record whose first component is a {@code
   * VerdictKind} would reintroduce it in the type system, where the next reader would find it
   * before they found the prose. {@code targetId} has nothing to hold here either.
   *
   * @param id the memory to promote, in any project tier and in any state the archive still stands
   *     behind
   * @param reason why it belongs everywhere, recorded in the new record's provenance. Optional,
   *     matching {@link Validation}'s deliberate silence about {@code formedWhere} — a promotion
   *     with no reason given is a promotion with less context, not a malformed one.
   * @param by who promoted it. Required: every other record reaches the store through {@code
   *     Validation.check}, which insists on {@code formedBy}, and this path mints a record without
   *     a proposal. Without the check, promotion is the one way into the archive with nobody's name
   *     attached — and a {@code null} would arrive as a {@code formed_by} NOT NULL violation from a
   *     stack many layers from whoever dropped the field.
   * @return the new global record and what making room for it cost. The record is as it was
   *     committed — the demotion pass below cannot touch it, which is what makes "as it was
   *     committed" a true sentence rather than "as it was built". It briefly was the second: an
   *     unexempted pass could file the row {@code cold} while this returned the object still
   *     stamped {@code active}. It has no vector at the moment it is built; the embedding column is
   *     not a field of {@link Memory}, so the returned record is unchanged by the call that fills
   *     it in.
   * @throws ValidationException if {@code by} is missing or blank
   * @throws ArchiveException if no memory was ever written under this id, if it is already global,
   *     or if it is a tombstone
   */
  public Promotion promote(String id, String reason, String by) {
    // Outside the transaction, matching applyVerdict's Validation.check: an
    // unnamed promoter is refused before a connection is taken at all, and
    // this reads nothing.
    //
    // The placement is tested, and it took a correction to say so. This
    // comment used to claim it "cannot be tested here", which was false in
    // the direction this branch spends most of its time catching in the
    // other: PromotionTest's TrackingUnitOfWork counts how many units of
    // work an operation opens, so
    // a_refused_argument_takes_no_unit_of_work_at_all asserts the count is
    // zero and fails on a build with this block moved inside
    // inTransaction. The instrument was twenty lines away and written in
    // the same commit.
    //
    // What genuinely cannot be seen here is the *cost*: a pooled Hikari
    // connection taken to refuse an argument, which needs the real
    // TransactionTemplate and is TransactionBoundaryTest's territory. So
    // this guard's position is covered; only the price of getting it wrong
    // is asserted elsewhere.
    if (by == null || by.isBlank()) {
      throw new ValidationException("field 'by' is required and must not be empty");
    }

    Promotion promotion =
        unitOfWork.inTransaction(
            () -> {
              Memory origin = get(id);

              // A switch expression over the enum rather than a test for the two
              // retired states: a fifth state would fail this to compile, where an
              // `!= SUPERSEDED && != INVALIDATED` would quietly let it through and
              // promote something nobody has decided the meaning of yet.
              boolean stillStandsBehind =
                  switch (origin.state()) {
                    // COLD is unused, not untrue. Refusing it would make demotion a
                    // kind of deletion after all.
                    //
                    // The path that reaches it is a proposal outliving its
                    // memory's place in the index, and this comment used to name a
                    // different one: "a rare-but-critical memory — the exact record
                    // a curator is looking for — is the one most likely to have gone
                    // cold". Task 9 shipped the curator and that path does not
                    // exist. Curator.untouched lists candidates from index(), which
                    // is `state = 'active'` only, so no cold memory is ever put to
                    // the judge — the spec says "list the project's active
                    // memories" and it does. What is real, and is why this clause
                    // stays: a curator files a proposal while a memory is active, a
                    // demotion pass files it cold days later, and a person approves
                    // it next week. Refusing there would answer a human's decision
                    // with a message about an index threshold they never saw.
                    //
                    // Whether a pass should reach cold memories at all is a live
                    // question and Task 10's: it needs an Archive read that lists
                    // the live set rather than the index, which nothing exposes.
                    case ACTIVE, COLD -> true;
                    case SUPERSEDED, INVALIDATED -> false;
                  };
              if (!stillStandsBehind) {
                // Checked before the tier, so a retired global memory is
                // described by what is actually wrong with it rather than by
                // where it happens to live. The curator proposes and a human
                // approves later, so a memory retired in between is an ordinary
                // interleaving rather than a caller's mistake, and the message
                // has to say which it was.
                //
                // That is a message, not a concurrency guarantee: two
                // promotions of one id can both pass the checks here and file
                // two global records. Unguarded on purpose — supersede() has
                // the same exposure, and the spec puts staleness in the
                // proposal queue, where a unique index on pending rows holds it
                // across two transactions and a Java check cannot.
                throw new ArchiveRefusedException(
                    "memory "
                        + origin.id()
                        + " is "
                        + origin.state().wireName()
                        + " and cannot be promoted; the archive no"
                        + " longer stands behind it");
              }
              if (origin.home().isGlobal()) {
                throw new ArchiveRefusedException(
                    "memory "
                        + origin.id()
                        + " is already in"
                        + " the global tier; promoting it again would file a second record"
                        + " for one claim, with the live one a copy of the retired one");
              }

              Memory record =
                  new Memory(
                      idFactory.get(),
                      // Verbatim from the origin, which has already been stripped
                      // by newMemory on its way in. Promotion decides which tier
                      // answers with a claim; it never edits the claim.
                      origin.summary(),
                      origin.scope(),
                      new Provenance(now.get(), by, promotedFrom(origin, reason)),
                      MemoryState.ACTIVE,
                      // A fresh use history, and both fields matter. Scoring decays
                      // from lastUsed when there is one, so carrying the origin's
                      // forward would put a memory promoted for having proved
                      // itself over months into the global index already decayed,
                      // and it would be the first record demoted back out of the
                      // tier it had just been promoted into. Pinning is a per-tier
                      // decision about one index, so it does not travel either.
                      false,
                      0,
                      null,
                      origin.body(),
                      origin.id(),
                      null,
                      null,
                      Home.global());
              store.save(record);
              // The same pair of saves, in the same order, and for the same reason
              // as supersede(): the new record live with the origin still active
              // would leave one claim answering from two tiers, with the project's
              // copy shadowing the global one it was promoted into.
              store.save(Lifecycle.supersede(origin, record.id()));
              // Exempt from its own pass, and this is not a nicety. A record
              // formed now scores exactly 1.0 — Scoring floors uses at one and,
              // with lastUsed null, decays from formed.at — so any global record
              // read twice and read recently outscores it, and an unexempted pass
              // picks the newcomer as its lowest-scoring candidate and files it
              // cold in the transaction that promoted it. Global is the tier every
              // project reads, so "the other records here have been read twice" is
              // its ordinary state: the more useful the tier, the more reliably a
              // promotion into it would undo itself. Measured before it was fixed
              // — the row read `cold` while this method returned the record it
              // built, still stamped `active`.
              //
              // applyVerdict deliberately does *not* do this, and the difference
              // is the arithmetic rather than the reporting: there the new record
              // is written into a tier alongside its own peers and has no reason
              // to be the lowest-scoring thing in it, while a promotion arrives at
              // exactly 1.0 in the tier every project reads.
              //
              // The reporting channel is no longer the difference, and this
              // comment used to say it was — "this method has nowhere to say it",
              // written when promote returned a bare Memory. Promotion.demoted is
              // the line below. The exemption is unchanged and still necessary;
              // only the reason given for the contrast was stale.
              return new Promotion(record, demoteOverThreshold(Home.global(), record.id()));
            });

    // The commit has happened and the connection is back in the pool. Only
    // now is the model called — see applyVerdict, which this deliberately
    // mirrors, and embed() for what putting this call inside the transaction
    // cost the last time it was there.
    embed(promotion.promoted());
    return promotion;
  }

  /**
   * A promotion: the global record it wrote, and what it cost the index.
   *
   * <p><b>Two components and not three.</b> The origin id is not one of them, because {@code
   * promoted.supersedes()} already is it — set by the same constructor call, and the link a chain
   * walker follows. A separate {@code originId} would be a second spelling of one fact with nothing
   * keeping them equal, and nothing to say which was right when they disagreed.
   *
   * @param promoted the new global record, as it was committed
   * @param demoted ids this promotion's own index pass filed {@code cold} to make room,
   *     lowest-scoring first, and never the promoted record itself — see {@link
   *     #demoteOverThreshold(Home, String)}. Empty when the tier had room, never {@code null}: a
   *     caller rendering "and this cost" has one shape to handle, which is the rule {@link
   *     WriteResult#demoted()} already keeps. Demotion is not deletion — every id here is still
   *     readable and still on the search path.
   */
  public record Promotion(Memory promoted, List<String> demoted) {

    public Promotion {
      // Copied, and the reason is not the one WriteResult gives. Nothing
      // mutates this list after it is handed over — demoteOverThreshold
      // has finished with it — so there is no live view to protect a
      // caller from. What the copy buys is that the list is the same kind
      // of thing every time: that method returns a mutable ArrayList when
      // it demoted something and an immutable List.of() when it did not,
      // and a caller that could edit one and not the other would find out
      // which by throwing.
      demoted = List.copyOf(demoted);
    }
  }

  /**
   * The provenance prose on a promoted record: where the claim came from, and why it was thought to
   * hold everywhere.
   *
   * <p>{@link Memory} has no field for a promotion, and this is the only place the reason is
   * recorded — {@code supersedes} carries the link a walker follows, and this carries the account a
   * reader needs. A global memory is read by every project, so "why is this one global?" is a
   * question somebody asks about it years later.
   *
   * <p>A blank reason produces the origin clause alone rather than a sentence ending in a dangling
   * colon, which would read as a reason that was lost rather than one that was never given.
   */
  private static String promotedFrom(Memory origin, String reason) {
    String from = "promoted from " + describe(origin.home()) + " memory " + origin.id();
    return reason == null || reason.isBlank() ? from : from + ": " + reason.strip();
  }

  // --- internals -----------------------------------------------------------

  private Filed create(MemoryProposal proposal, Home home) {
    Memory memory = newMemory(proposal, home, null);
    store.save(memory);
    return new Filed(memory.id(), memory);
  }

  /**
   * Give a memory its vector, or leave it without one.
   *
   * <p><b>What gets embedded is {@code summary + "\n" + scope}, and not the body.</b> The scope is
   * the sentence that says <em>when this memory applies</em> — "Working on auth, service-to-service
   * calls, or anything in payments" — which is precisely the thing a question should match against;
   * the summary is the claim itself, stated to stand alone. Bodies are long, varied, and full of
   * incidental specifics that drag the vector away from what the memory is for, so a body-derived
   * vector matches on whatever the body happened to mention. Recording that here because it looks
   * arbitrary from a distance and is expensive to re-derive.
   *
   * <p><b>Called after the commit, never inside it.</b> {@link #applyVerdict} closes its {@link
   * UnitOfWork} before calling this, so the row really is on disk by the time the model is asked
   * for anything, and no failure here of any kind can take the memory back. That sentence used to
   * be written here as a statement of fact while being false: the boundary had moved out to a
   * {@code @Transactional} on every {@code MemoryController} method, which wrapped this call too,
   * and the narrow catch below was then the only thing standing between a write and oblivion. It
   * was not enough. An operator setting {@code LLM_BASE_URL=localhost:1234/v1} — no {@code http://}
   * — makes OkHttp throw {@code IllegalArgumentException} rather than {@code EmbeddingException},
   * which sailed past this catch, rolled the transaction back, and discarded every memory anyone
   * wrote while reporting {@code 400 bad_request} to the agent, as though the proposal had been the
   * problem. Two hundred writes could be lost that way without one error in the log.
   *
   * <p><b>The expected failure is still swallowed on purpose: never lose a write.</b> A memory
   * written while the embedding endpoint is down keeps a NULL embedding — it stays {@code active},
   * stays readable by id, stays in the index, and is skipped only by <em>vector</em> search, where
   * it has nothing to be compared with. The column is nullable in V1__memories.sql for exactly
   * this.
   *
   * <h3>Nothing escapes this, and that is a correction</h3>
   *
   * <p>This used to catch {@link EmbeddingException} and nothing else, on the reasoning that a
   * catch wide enough to hide a bug in this class would hide it silently, one write at a time,
   * while an escaping bug "fails the request loudly, where somebody sees it — and it leaves the
   * memory exactly where it was written". <b>That reasoning had a hidden premise: that no caller
   * does anything on the way out but pass the exception up.</b> It was true while {@link
   * #applyVerdict} was the only caller. {@link PromotionQueue#approve} is a caller that
   * <em>compensates</em> — it settles a proposal, promotes, and releases the claim if the promotion
   * throws — so an exception raised <em>after</em> the commit tells it to undo something that
   * already happened. Measured: a {@code DataAccessException} out of {@link
   * MemoryStore#saveEmbedding}'s bare {@code jdbc.update} left a global record on disk, the origin
   * a tombstone, and the proposal back in {@code pending} where every future approval of it is
   * refused forever, because {@code promote} then refuses the superseded origin.
   *
   * <p>The fix belongs here rather than in that catch. A caller cannot tell an exception raised
   * before the commit from one raised after it — the post-commit set is exactly the open set of
   * "anything unexpected" — so narrowing the catch cannot be made sound. Making this unable to
   * throw can, and it is what the {@code @return} below already promised: the boolean exists
   * precisely so a failure can be reported without one.
   *
   * <p><b>The bug-hiding concern is answered by the log, not by the stack.</b> An {@link
   * EmbeddingException} is expected and gets a warning; anything else is a bug or a dead database
   * and gets {@code log.error} <em>with the throwable</em>, so the stack is on disk where somebody
   * reads it. What it no longer does is fail an operation that succeeded. After the commit there is
   * no true failure left to report to the caller: the write happened, and the one thing that did
   * not is a vector, which {@link Recall#unsearchable}, {@link TocEntry#unsearchable} and {@link
   * #reembed} already surface — and they persist, which an exception does not.
   *
   * <p>Note that {@code saveEmbedding} was never covered by the old catch in the first place. Half
   * of what escaped here had nothing to do with hiding a bug in this class; it was the database.
   *
   * <p><b>The warning below is no longer the only trace.</b> It used to be, and that was the whole
   * of finding A: a memory with a NULL embedding is skipped by {@link MemoryStore#searchByVector},
   * so the archive could hold exactly one memory and answer "nothing is close to that question",
   * with nothing on any endpoint, tool or rendered field to say otherwise. The state is now
   * reported by {@link Recall#unsearchable} on every recall, marked per line by {@link
   * TocEntry#unsearchable} in the index, and repaired by {@link #reembed}.
   *
   * @return true if the memory now has a vector, false if the endpoint would not give one. The
   *     write is kept either way; the boolean exists so {@link #reembed} can tell an operator which
   *     memories are still owed one rather than making them re-read the log.
   */
  private boolean embed(Memory memory) {
    return embed(
        memory,
        null,
        usageOwners.in(memory.home(), null, UsageAttribution.Operation.EMBEDDING_REPAIR));
  }

  /**
   * @param precomputed a vector already computed from this memory's own embedded text, or {@code
   *     null} to ask the endpoint. Never checked here: {@link
   *     #requireComputedFromTheMemorysOwnText} did that before the commit, because this method may
   *     not throw and a check that can only refuse after the row is on disk refuses nothing.
   */
  private boolean embed(Memory memory, Precomputed precomputed) {
    return embed(
        memory,
        precomputed,
        usageOwners.in(memory.home(), null, UsageAttribution.Operation.EMBEDDING_WRITE));
  }

  private boolean embed(Memory memory, Precomputed precomputed, UsageAttribution owner) {
    try {
      if (dualEmbeddings != null)
        return dualEmbeddings.repair(
            EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, memory.id()),
            owner);

      store.saveEmbedding(
          memory.id(),
          precomputed != null
              ? precomputed.vector()
              : EmbeddingClient.owned(embeddings, embeddedText(memory), owner));
      return true;
    } catch (EmbeddingException expected) {
      log.warn(
          "memory {} was stored without an embedding and will not be reachable by"
              + " vector recall until it is re-embedded: {}",
          memory.id(),
          expected.getMessage());
      return false;
    } catch (RuntimeException unexpected) {
      // Separated from the clause above so the two are distinguishable in
      // the log, which is now the only place they are distinguishable at
      // all: an endpoint that is down is routine and gets a warning, while
      // a dead database or a bug in this class is neither and gets the
      // stack. a_write_survives_an_embedding_failure_the_archive_does_not_expect
      // asserts on the level and on the throwable, because a claim about
      // logging with nothing behind it is exactly what this branch keeps
      // finding.
      log.error(
          "memory {} was stored, but embedding it failed in a way the archive does"
              + " not expect; the memory is kept and unembedded",
          memory.id(),
          unexpected);
      return false;
    }
  }

  /**
   * @see #embed(Memory) for why this is summary and scope and not the body.
   */
  static String embeddedText(Memory memory) {
    return memory.summary() + "\n" + memory.scope();
  }

  /**
   * Append to the record the verdict judged to be the same memory, and create nothing.
   *
   * <p>The returned id is the <em>target's</em>. A merge that minted a second id would leave two
   * records for one claim with nothing to choose between them, which is the state the verdict
   * exists to prevent.
   */
  private Filed merge(MemoryProposal proposal, Verdict verdict, Home home) {
    Memory merged =
        Lifecycle.mergeBody(
            requireTarget(verdict, home), proposal.body(), now.get(), proposal.formedBy());
    store.save(merged);
    // Not re-embedded, and that is not an omission. A merge appends to the
    // body and leaves summary and scope exactly as they were, so the text
    // that gets embedded has not changed and the stored vector is still the
    // right one; asking the endpoint for it again would be a round trip
    // spent to compute the same numbers. The one case this leaves behind is
    // a target whose embedding is NULL because the endpoint was down when it
    // was created — that memory is repaired by reembed(Home), which is a
    // maintenance pass and not this write's business.
    return new Filed(merged.id(), null);
  }

  /**
   * Newest wins: write the new record, and point the old one forward at it.
   *
   * <p>The old record keeps its body. It is filed out of the search path, not emptied — the text is
   * the evidence of what was once believed, and the link is what lets anyone following the chain
   * see what replaced it.
   */
  private Filed supersede(MemoryProposal proposal, Verdict verdict, Home home) {
    Memory target = requireTarget(verdict, home);
    Memory memory = newMemory(proposal, home, target.id());
    store.save(memory);
    // These two saves are the reason there is a transaction at all: the new
    // record live with the old one never marked retired is a supersession
    // quietly filed as a fresh memory, and the archive looks fine until the
    // stale memory answers a question. The embedding used to sit between
    // them, which put a model call — and a held connection — in the middle
    // of the one pair of statements that must not come apart.
    //
    // The retired record keeps its embedding. Nothing searches it — the
    // state filter sees to that — and clearing it would only mean a
    // re-embed if the supersession is ever itself undone.
    store.save(Lifecycle.supersede(target, memory.id()));
    return new Filed(memory.id(), memory);
  }

  private Memory newMemory(MemoryProposal proposal, Home home, String supersedes) {
    return new Memory(
        idFactory.get(),
        // Stripped, matching Excalibur: the summary is one line of an
        // index rendered as text, and stray whitespace there is a
        // ragged index nobody can see the cause of.
        proposal.summary().strip(),
        proposal.scope().strip(),
        new Provenance(now.get(), proposal.formedBy(), proposal.formedWhere()),
        MemoryState.ACTIVE,
        false,
        0,
        null,
        proposal.body().strip(),
        supersedes,
        null,
        null,
        home);
  }

  /**
   * Resolve the memory a verdict names, or refuse.
   *
   * <p>Three refusals, and each one is a caller that believed something specific about the archive
   * and was wrong:
   *
   * <ul>
   *   <li><b>No target id.</b> A merge or supersession with nothing to merge into is not a write
   *       with a missing field, it is a verdict that contradicts itself.
   *   <li><b>Unknown target.</b> Refused rather than silently downgraded to a new memory: a
   *       supersession quietly filed as a fresh record leaves the stale memory live, and the
   *       archive looks fine until that stale memory answers a question.
   *   <li><b>A target in the other tier.</b> Project shadows global, and shadowing is not
   *       supersession — the global record stays correct for every other project. Letting one
   *       project retire or rewrite a global memory would make one project's local situation
   *       everyone's, which is the misreading of the two-tier model that costs the most to undo. A
   *       project that genuinely contradicts a global fact writes {@code NEW} in its own tier;
   *       promotion the other way is the curator's.
   * </ul>
   *
   * <p>A <em>retired</em> target is fine, and deliberately so: the second change to a fact names
   * the record the first change retired, and supersession chains depend on it resolving.
   */
  private Memory requireTarget(Verdict verdict, Home home) {
    String kind = verdict.kind().wireName();
    if (verdict.targetId() == null || verdict.targetId().isBlank()) {
      throw new ArchiveRefusedException("verdict '" + kind + "' requires a target id");
    }
    Memory target =
        store
            .load(verdict.targetId())
            .orElseThrow(
                () ->
                    new ArchiveException(
                        "verdict '" + kind + "' names unknown target " + verdict.targetId()));
    if (!target.home().equals(home)) {
      throw new ArchiveRefusedException(
          "verdict '"
              + kind
              + "' names target "
              + target.id()
              + " in "
              + describe(target.home())
              + ", but this write is in "
              + describe(home)
              + "; a memory in another tier is shadowed, never superseded");
    }
    return target;
  }

  private static String describe(Home home) {
    return home.isGlobal() ? "the global tier" : "project '" + home.project() + "'";
  }

  /**
   * Demote the lowest-scoring unpinned records until this tier's index is lean again, and report
   * what fell out.
   *
   * <p>Demotion is not deletion. A {@code cold} record keeps its body, its use count and its
   * reachability by id and by search — it has only left the index. That is what makes it safe to
   * keep the index aggressively small: a wrong eviction costs a slower recall, never a lost memory.
   *
   * <p>One tier at a time, because the index is per tier. A store-wide pass would let a project
   * that writes heavily demote the global memories every other project depends on.
   */
  private List<String> demoteOverThreshold(Home home) {
    return demoteOverThreshold(home, null);
  }

  /**
   * @param exempt an id this pass may not demote, or {@code null}. For a caller that has just
   *     written a record into this tier and must not have it taken straight back out — see {@link
   *     #promote}, where leaving it eligible made a promotion undo itself. It is still
   *     <em>counted</em> toward the tier's size, because it does occupy a place in the index; it is
   *     only barred from being the record that leaves. A tier whose only unpinned candidate is the
   *     exempt record therefore demotes nothing and stays one over threshold, exactly as a tier of
   *     nothing but pinned records does.
   */
  private List<String> demoteOverThreshold(Home home, String exempt) {
    List<Memory> active =
        store.loadAll(home).stream().filter(m -> m.state() == MemoryState.ACTIVE).toList();

    // Counted over every active record including the pinned ones, matching
    // Excalibur: pinning protects a record from being demoted, it does not
    // buy the tier a larger index. An archive of nothing but pinned records
    // simply demotes nothing.
    int surplus = active.size() - indexThreshold;
    if (surplus <= 0) {
      return List.of();
    }

    Instant at = now.get();
    List<Memory> candidates =
        active.stream()
            .filter(m -> !m.pinned())
            .filter(m -> !m.id().equals(exempt))
            // Id breaks score ties, and ids sort by creation time: with two
            // equally-decayed records the older one goes first. An unstable
            // tie-break would make which memory left the index depend on
            // row order.
            .sorted(
                Comparator.comparingDouble((Memory m) -> Scoring.score(m, at, halfLifeDays))
                    .thenComparing(Memory::id))
            .toList();

    List<String> demoted = new ArrayList<>();
    for (Memory memory : candidates.subList(0, Math.min(surplus, candidates.size()))) {
      store.save(Lifecycle.demote(memory));
      demoted.add(memory.id());
    }
    return demoted;
  }
}
