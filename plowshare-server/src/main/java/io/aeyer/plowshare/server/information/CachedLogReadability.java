package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.cache.ItemProjectionCache;
import io.aeyer.plowshare.server.cache.ProjectionItemVersions;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.cache.VersionedProjectionCache;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The targeted log check ({@code information_log_readable}) from shared, item-cached log-tree
 * facts, the slice 1 knowledge facts and the in-memory authority snapshot (design slice 2).
 *
 * <h2>What is cached</h2>
 *
 * <p>One {@link LogTreeFact} per root log under the READABILITY item {@code conversation:<root>}
 * (V257, V258), loaded by one indexed statement through this cache's FETCH slots. It carries no
 * account and no decision, so every account asking about any log of the tree shares one fill, and a
 * new conversation, orchestration, job input or quarantine invalidates only the trees whose roots
 * the triggers name. The retained inputs' revisions and resources are the slice 1 facts ({@link
 * KnowledgeFactSource}); accounts, grants and scopes come from the authority snapshot.
 *
 * <h2>Which trees a log needs</h2>
 *
 * <p>A log's ancestry lies in the trees of its roots. The roots are resolved with one statement
 * ({@link LogTreeFactRepository#roots}, the triggers' own walk) and remembered per log as a hint in
 * a bounded map. A hint is never trusted: the evaluation walks the log's ancestry over the loaded
 * trees, and a log that none of them holds (the hint is out of date, for example after the tree was
 * joined under another root) sends it back to a fresh resolution once, then to SQL.
 *
 * <h2>Consistency</h2>
 *
 * <p>Trees and knowledge facts are read without a check each ({@link
 * ItemProjectionCache#readUnconfirmed}); every item the evaluation read is then confirmed with one
 * {@link ProjectionItemVersions#staleItems} statement. Any write to a tree's ancestry edges,
 * retained inputs or quarantine moves the counter of a root the tree was loaded under: the triggers
 * name every root of each changed log after the change and, since V258, the child of every new
 * edge, which is the old root when a tree is joined under another. An empty result therefore proves
 * the verdict reflects every data-side write committed before the confirmation. The authority half
 * keeps the snapshot's notification-trusted contract, as in slice 1 and as the per-account deny set
 * did: a membership or grant change may take up to the staleness bound (2 s).
 *
 * <h2>When SQL answers instead</h2>
 *
 * <ul>
 *   <li>the caller holds a transaction that may contain its own writes;
 *   <li>no authority snapshot is available;
 *   <li>the roots cannot be addressed (an ancestry past 256 logs, a cycle without a root, an id
 *       outside the item grammar) or there are more than {@link #MAX_TREES} of them;
 *   <li>a cache cannot serve (listener not ready, ineligible caller, refused load), a tree is
 *       {@link LogTreeFact.Oversized}, or a retained input needs a fact the slice 1 rule cannot
 *       serve;
 *   <li>the trees still do not hold the log's ancestry after a fresh resolution;
 *   <li>the confirmation reports a stale item, which is invalidated alone.
 * </ul>
 */
public final class CachedLogReadability implements LogReadabilityCheck {
  /** FETCH admission principal: the tree cache's own slots, never a per-account budget. */
  static final String FETCH = "log-tree-facts";

  /** Most trees one evaluation reads; a log with more roots is answered by SQL. */
  static final int MAX_TREES = 16;

  /** Longest log id remembered as a hint; longer ids resolve their roots on every check. */
  private static final int MAX_HINTED_LENGTH = 256;

  private final ItemProjectionCache<LogTreeFact> trees;
  private final LogTreeFactRepository repository;
  private final KnowledgeFactSource facts;
  private final ProjectionItemVersions versions;
  private final AuthoritySnapshots authority;
  private final ReadAdmission admission;
  private final Map<String, List<String>> hints;

  /**
   * @param maxHints most logs whose resolved roots are remembered (least recently used first out)
   */
  CachedLogReadability(
      ItemProjectionCache<LogTreeFact> trees,
      LogTreeFactRepository repository,
      KnowledgeFactSource facts,
      ProjectionItemVersions versions,
      AuthoritySnapshots authority,
      ReadAdmission admission,
      int maxHints) {
    this.trees = Objects.requireNonNull(trees);
    this.repository = Objects.requireNonNull(repository);
    this.facts = Objects.requireNonNull(facts);
    this.versions = Objects.requireNonNull(versions);
    this.authority = Objects.requireNonNull(authority);
    this.admission = Objects.requireNonNull(admission);
    if (maxHints < 1) throw new IllegalArgumentException("Log root hint bound out of range");
    this.hints =
        Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
              @Override
              protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                return size() > maxHints;
              }
            });
  }

  @Override
  public Optional<Boolean> readable(String account, String log) {
    Objects.requireNonNull(account);
    Objects.requireNonNull(log);
    if (!VersionedProjectionCache.callerHasNoOwnWrites()) return Optional.empty();
    var snapshot = authority.current();
    if (snapshot.isEmpty()) return Optional.empty();
    var hinted = hints.get(log);
    var roots = hinted != null ? Optional.of(hinted) : resolve(log);
    if (roots.isEmpty()) return Optional.empty();
    var attempt = evaluate(account, log, roots.get(), snapshot.get());
    if (attempt.incomplete() && hinted != null) {
      // The remembered roots no longer cover the log: resolve once more, never loop.
      hints.remove(log);
      roots = resolve(log);
      if (roots.isEmpty()) return Optional.empty();
      attempt = evaluate(account, log, roots.get(), snapshot.get());
    }
    return attempt.verdict();
  }

  /** The verdict to return, or whether the trees did not hold the log's ancestry. */
  private record Attempt(Optional<Boolean> verdict, boolean incomplete) {
    static final Attempt SQL = new Attempt(Optional.empty(), false);
    static final Attempt INCOMPLETE = new Attempt(Optional.empty(), true);
  }

  private Optional<List<String>> resolve(String log) {
    return admission.read(FETCH, ReadAdmission.Work.FETCH, () -> repository.roots(log));
  }

  private Attempt evaluate(
      String account, String log, List<String> roots, AuthoritySnapshot snapshot) {
    if (roots.isEmpty() || roots.size() > MAX_TREES) return Attempt.SQL;
    var loaded = new ArrayList<LogTreeFact.Tree>();
    var tokens = new ArrayList<ProjectionItemVersions.ItemToken>();
    for (String root : roots) {
      String address = "conversation:" + root;
      if (!ProjectionVersions.Item.addressable(address)) return Attempt.SQL;
      var item = new ProjectionVersions.Item(ProjectionVersions.Domain.READABILITY, address);
      var result =
          trees.readUnconfirmed(
              new SnapshotCache.Key("log-tree", root),
              Set.of(item),
              () -> admission.read(FETCH, ReadAdmission.Work.FETCH, () -> repository.tree(root)),
              LogTreeFact::weight);
      if (result.isEmpty()) return Attempt.SQL;
      if (!(result.get().value() instanceof LogTreeFact.Tree tree)) return Attempt.SQL;
      loaded.add(tree);
      tokens.add(result.get().items());
    }
    var reads = facts.reads();
    var retained = KnowledgeReadability.retainedInputs(account, snapshot, reads);
    var decided = new HashMap<LogTreeFact.JobInput, Optional<Boolean>>();
    var outcome =
        LogReadability.evaluate(
            account, log, loaded, input -> decided.computeIfAbsent(input, retained::readable));
    return switch (outcome) {
      case LogReadability.Outcome.Incomplete incomplete -> Attempt.INCOMPLETE;
      case LogReadability.Outcome.Unservable unservable -> Attempt.SQL;
      case LogReadability.Outcome.Verdict verdict -> confirm(log, roots, tokens, reads, verdict);
    };
  }

  /** One statement confirms every tree and fact the verdict was derived from. */
  private Attempt confirm(
      String log,
      List<String> roots,
      List<ProjectionItemVersions.ItemToken> tokens,
      KnowledgeFactSource.Reads reads,
      LogReadability.Outcome.Verdict verdict) {
    var all = new ArrayList<>(tokens);
    all.addAll(reads.tokens());
    long items =
        all.stream().flatMap(token -> token.counters().keySet().stream()).distinct().count();
    if (items > ProjectionItemVersions.MAX_CONFIRMED_ITEMS) return Attempt.SQL;
    var stale = versions.staleItems(all);
    if (!stale.isEmpty()) {
      // Drop exactly the outdated trees and facts; the next check reloads them alone.
      for (var item : stale) {
        trees.invalidate(item);
        facts.invalidate(item);
      }
      return Attempt.SQL;
    }
    if (log.length() <= MAX_HINTED_LENGTH) hints.put(log, List.copyOf(roots));
    return new Attempt(Optional.of(verdict.readable()), false);
  }
}
