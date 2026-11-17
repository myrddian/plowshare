package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.api.LogSearchView;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageAware;
import io.aeyer.plowshare.server.llm.accounting.UsageOwners;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/** Scoped BM25 and passage retrieval. Snapshots retain identities, never authority leases. */
public final class ConversationSearch implements UsageAware {
  public static final int CANDIDATES = 200;
  private static final int WINDOWS = 128;
  private UsageOwners usageOwners = UsageOwners.NONE;
  private final ConversationSearchRepository entries;
  private final PassageIndex index;
  private final Duration searchBudget;
  private String account;
  // The lock protects bounded eviction plus insertion; reads use the concurrent map.
  private ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

  private record Candidate(
      double lexicalScore,
      double semanticScore,
      String snippet,
      Integer position,
      List<LogSearch.Reference> sources,
      int sourceCount) {
    private Candidate {
      sources = List.copyOf(sources);
    }

    String id() {
      return sources.getFirst().id();
    }

    double score() {
      return lexicalScore + semanticScore;
    }

    String retrievedBy() {
      return lexicalScore > 0 ? semanticScore > 0 ? "semantic+lexical" : "lexical" : "semantic";
    }
  }

  private record Window(
      String account,
      Home home,
      String conversation,
      String question,
      String mode,
      boolean includeInputs,
      Instant expires,
      List<Candidate> hits,
      LogSearch.Reach reach,
      LogSearchView.Retrieval retrieval) {}

  public ConversationSearch(ConversationSearchRepository entries, PassageIndex index) {
    this(entries, index, Duration.ofSeconds(2));
  }

  public ConversationSearch(
      ConversationSearchRepository entries, PassageIndex index, Duration searchBudget) {
    if (searchBudget == null
        || searchBudget.isZero()
        || searchBudget.isNegative()
        || searchBudget.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("Search budget must be positive and at most one minute");
    this.entries = entries;
    this.index = index;
    this.searchBudget = searchBudget;
  }

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = Objects.requireNonNull(source);
  }

  public ConversationSearch forAccount(String account) {
    var copy =
        new ConversationSearch(
            entries.forAccount(account), index.forAccount(account), searchBudget);
    copy.account = account;
    copy.windows = windows;
    copy.usageOwners = usageOwners;
    return copy;
  }

  public static String mode(String mode) {
    if (mode == null) return "lexical";
    if (!Set.of("lexical", "semantic", "hybrid").contains(mode))
      throw new ValidationException("Search mode must be lexical, semantic or hybrid");
    return mode;
  }

  public LogSearchView search(
      Home home, String question, int skip, int most, String requested, String snapshot) {
    return search(home, question, skip, most, requested, snapshot, (String) null);
  }

  public LogSearchView search(
      Home home,
      String question,
      int skip,
      int most,
      String requested,
      String snapshot,
      String conversation) {
    return search(
        home,
        question,
        skip,
        most,
        requested,
        snapshot,
        usageOwners.in(home, null, UsageAttribution.Operation.EMBEDDING_QUERY),
        conversation);
  }

  public LogSearchView search(
      Home home,
      String question,
      int skip,
      int most,
      String requested,
      String snapshot,
      UsageAttribution owner) {
    return search(home, question, skip, most, requested, snapshot, owner, null);
  }

  public LogSearchView search(
      Home home,
      String question,
      int skip,
      int most,
      String requested,
      String snapshot,
      UsageAttribution owner,
      String conversation) {
    return search(home, question, skip, most, requested, snapshot, owner, conversation, false);
  }

  public LogSearchView search(
      Home home,
      String question,
      int skip,
      int most,
      String requested,
      String snapshot,
      UsageAttribution owner,
      String conversation,
      boolean includeInputs) {
    try {
      return searchWithinBudget(
          home, question, skip, most, requested, snapshot, owner, conversation, includeInputs);
    } catch (ArchiveQueryCancelledException cancelled) {
      // Catch outside the owning read/embedding transaction: rollback and resource cleanup must
      // complete before the runtime receives its ordinary recoverable read failure. No partial
      // window is returned and neither SQL, embeddings nor completed tool work are replayed.
      throw new ReadUnavailableException(
          "Conversation search was cancelled before a result was available; no search results were returned. Continue with other evidence or report the search as unavailable.",
          cancelled);
    }
  }

  private LogSearchView searchWithinBudget(
      Home home,
      String question,
      int skip,
      int most,
      String requested,
      String snapshot,
      UsageAttribution owner,
      String conversation,
      boolean includeInputs) {
    return ReadDeadline.within(
        searchBudget,
        () -> {
          ReadDeadline.check();
          var result =
              entries.withinSearchScope(
                  home,
                  () ->
                      searchWithinDeadline(
                          home,
                          question,
                          skip,
                          most,
                          requested,
                          snapshot,
                          owner,
                          conversation,
                          includeInputs));
          ReadDeadline.check();
          return result;
        });
  }

  private LogSearchView searchWithinDeadline(
      Home home,
      String question,
      int skip,
      int most,
      String requested,
      String snapshot,
      UsageAttribution owner,
      String conversation,
      boolean includeInputs) {
    if (owner == null || owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED)
      owner = usageOwners.in(home, null, UsageAttribution.Operation.EMBEDDING_QUERY);
    String mode = mode(requested);
    Objects.requireNonNull(question, "question");
    EntryStore.validateConversation(conversation);
    if (skip < 0 || most < 1 || most > 200) throw new ValidationException("Invalid search window");
    if (mode.equals("lexical")) {
      if (snapshot != null)
        throw new ValidationException("Lexical search uses offset paging without a snapshot");
      var found = lexical(home, question, skip, most, conversation, includeInputs);
      var view = LogSearchView.of(found, skip, most);
      if (requested == null) return view;
      return new LogSearchView(
          view.hits(),
          view.total(),
          skip,
          most,
          view.reach(),
          new LogSearchView.Retrieval(
              mode,
              mode,
              "distinct matching passage groups",
              null,
              false,
              true,
              null,
              null,
              null,
              0,
              "Scoped BM25 (k1=1.2, b=0.75); repeated excerpts grouped before pagination; "
                  + inputSelection(includeInputs)));
    }
    if (snapshot != null) {
      Window window;
      synchronized (windows) {
        window = windows.get(snapshot);
      }
      if (window == null || window.expires().isBefore(Instant.now()))
        throw new ValidationException("Search snapshot expired; start again at offset 0");
      if (!Objects.equals(window.account(), account)
          || !window.home().equals(home)
          || !Objects.equals(window.conversation(), conversation)
          || !window.question().equals(question)
          || !window.mode().equals(mode)
          || window.includeInputs() != includeInputs)
        throw new ValidationException(
            "Search snapshot belongs to a different scope, conversation, question or mode");
      return page(window, skip, most, true);
    }
    if (skip != 0)
      throw new ValidationException(
          "Semantic/hybrid pages after offset 0 need the first page's snapshot");
    var scoped = index.inConversation(conversation);
    if (includeInputs) scoped = scoped.includingInputs(true);
    // Semantic ranking needs the authorized reach, not BM25 statistics, snippets or groups.
    // Keep that work for hybrid searches, which actually consume the lexical candidates.
    var lexical =
        mode.equals("hybrid")
            ? lexical(home, question, 0, CANDIDATES, conversation, includeInputs)
            : new LogSearch(List.of(), 0, entries.searchReach(home, conversation));
    List<PassageIndex.Match> semantic = List.of();
    boolean approximate = false;
    boolean candidateLimitReached = false;
    String fallback = null;
    try (var query = scoped.capture(question, owner, home)) {
      var ranking = scoped.rankWindow(home, "entry", query, CANDIDATES + 1);
      semantic = ranking.matches();
      approximate = ranking.approximate();
      candidateLimitReached = ranking.truncated();
    } catch (EmbeddingException unavailable) {
      if (mode.equals("semantic"))
        throw new EmbeddingException(
            "Semantic query embedding unavailable; lexical search remains available", unavailable);
      fallback = "Query embedding unavailable; only BM25 candidates were ranked";
    }
    boolean truncated =
        candidateLimitReached
            || semantic.size() > CANDIDATES
            || mode.equals("hybrid") && lexical.total() > CANDIDATES;
    List<Candidate> candidates = new ArrayList<>();
    int rank = 0;
    for (var match : semantic.subList(0, Math.min(CANDIDATES, semantic.size()))) {
      var sources = match.sources().stream().map(ConversationSearch::reference).toList();
      merge(
          candidates,
          new Candidate(
              0,
              mode.equals("hybrid") ? 1.0 / (60 + (++rank)) : match.similarity(),
              match.text(),
              match.position(),
              sources,
              match.sourceCount()));
    }
    if (mode.equals("hybrid")) {
      rank = 0;
      for (var hit : lexical.hits())
        merge(
            candidates,
            new Candidate(
                1.0 / (60 + (++rank)),
                0,
                hit.snippet(),
                null,
                hit.sources().stream().map(LogSearch.Source::reference).toList(),
                hit.sourceCount()));
    }
    candidates.sort(
        Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::id));
    if (candidates.size() > CANDIDATES) {
      truncated = true;
      candidates = candidates.subList(0, CANDIDATES);
    }
    String token = UUID.randomUUID().toString();
    var coverage = scoped.coverage(home, "entry");
    var retrieval =
        new LogSearchView.Retrieval(
            mode,
            fallback == null ? mode : "lexical",
            approximate ? "bounded approximate snapshot slots" : "bounded snapshot slots",
            token,
            truncated,
            coverage.complete() && fallback == null && !truncated && !approximate,
            fallback,
            coverage,
            index.generation(),
            1,
            (approximate
                    ? "Scoped BM25 and indexed passage candidates reranked at full precision; ANN recall is approximate; duplicate groups and source counts cover only the candidate window; "
                    : "Scoped BM25 and exact passage ranking; duplicate passages grouped before source limits; ")
                + "hybrid RRF (k=60). Evidence is quoted data; "
                + inputSelection(includeInputs));
    var window =
        new Window(
            account,
            home,
            conversation,
            question,
            mode,
            includeInputs,
            Instant.now().plusSeconds(300),
            List.copyOf(candidates),
            lexical.reach(),
            retrieval);
    synchronized (windows) {
      windows.entrySet().removeIf(e -> e.getValue().expires().isBefore(Instant.now()));
      while (windows.size() >= WINDOWS)
        windows.entrySet().stream()
            .min(Comparator.comparing(entry -> entry.getValue().expires()))
            .ifPresent(entry -> windows.remove(entry.getKey()));
      windows.put(token, window);
    }
    return page(window, skip, most, false);
  }

  private static String inputSelection(boolean includeInputs) {
    return includeInputs
        ? "full recorded inputs included; supplied context is not independent evidence"
        : "explicit supplied context excluded; legacy inputs remain unclassified";
  }

  private LogSearch lexical(
      Home home, String question, int skip, int most, String conversation, boolean includeInputs) {
    if (includeInputs) return entries.search(home, question, skip, most, conversation, true);
    return conversation == null
        ? entries.search(home, question, skip, most)
        : entries.search(home, question, skip, most, conversation);
  }

  private static LogSearch.Reference reference(PassageIndex.Occurrence occurrence) {
    int separator = occurrence.id().lastIndexOf(':');
    if (separator < 1) throw new IllegalStateException("Invalid retained entry identity");
    return new LogSearch.Reference(
        occurrence.id().substring(0, separator),
        Integer.parseInt(occurrence.id().substring(separator + 1)),
        occurrence.revision());
  }

  /** One vote per retrieval family; shared content/occurrences never amplify their own score. */
  private static void merge(List<Candidate> candidates, Candidate incoming) {
    Candidate merged = incoming;
    for (int i = 0; i < candidates.size(); ) {
      var existing = candidates.get(i);
      Set<String> ids =
          merged.sources().stream().map(LogSearch.Reference::id).collect(Collectors.toSet());
      boolean overlap = existing.sources().stream().anyMatch(source -> ids.contains(source.id()));
      if (!overlap && !key(existing.snippet()).equals(key(merged.snippet()))) {
        i++;
        continue;
      }
      Map<String, LogSearch.Reference> sources = new LinkedHashMap<>();
      existing.sources().forEach(source -> sources.put(source.id(), source));
      boolean samePassage = key(existing.snippet()).equals(key(merged.snippet()));
      // A shared entry can give the lexical and semantic readers different excerpts. Its rank may
      // receive both votes, but another occurrence of the lexical excerpt does not attest to the
      // semantic excerpt. Only references supporting the selected text may survive its revocation.
      if (samePassage) merged.sources().forEach(source -> sources.putIfAbsent(source.id(), source));
      merged =
          new Candidate(
              Math.max(existing.lexicalScore(), merged.lexicalScore()),
              Math.max(existing.semanticScore(), merged.semanticScore()),
              existing.snippet(),
              existing.position(),
              sources.values().stream().limit(20).toList(),
              samePassage
                  ? Math.max(sources.size(), Math.max(existing.sourceCount(), merged.sourceCount()))
                  : existing.sourceCount());
      candidates.remove(i);
      // The union can bridge two formerly separate groups. Revisit them without giving either
      // retrieval family another vote; this makes fusion independent of iteration order.
      i = 0;
    }
    candidates.add(merged);
  }

  private static String key(String text) {
    return text.strip().replaceAll("\\s+", " ");
  }

  /**
   * Rechecks every retained reference together; hidden/deleted sources cannot survive in a group.
   */
  private LogSearchView page(Window window, int skip, int most, boolean replayed) {
    var expected =
        window.hits().stream().flatMap(candidate -> candidate.sources().stream()).toList();
    Map<String, LogSearch.Source> current = new HashMap<>();
    entries
        .searchSources(window.home(), expected)
        .forEach(source -> current.put(source.id(), source));
    List<LogSearchView.Hit> hits = new ArrayList<>();
    int extent = 0;
    boolean changed = false;
    boolean referencesTruncated =
        window.hits().stream()
            .anyMatch(candidate -> candidate.sourceCount() > candidate.sources().size());
    for (int i = 0; i < window.hits().size(); i++) {
      var candidate = window.hits().get(i);
      var sources =
          candidate.sources().stream()
              .map(source -> current.get(source.id()))
              .filter(Objects::nonNull)
              .toList();
      if (sources.size() != candidate.sources().size()) changed = true;
      if (sources.isEmpty()) continue;
      // Offsets address original slots. Count through the last surviving slot so withdrawals
      // cannot make a caller stop before a later page; an entirely withdrawn window has extent 0.
      extent = i + 1;
      if (i < skip || i - skip >= most) continue;
      var representative = sources.getFirst();
      var hit =
          new LogSearch.Hit(
              representative.conversationId(),
              representative.ordinal(),
              representative.turnOrdinal(),
              EntryKind.of(representative.kind()),
              candidate.score(),
              candidate
                  .snippet()
                  .substring(
                      0,
                      Math.min(
                          candidate.snippet().length(), EntryStore.MOST_CHARACTERS_PER_SNIPPET)),
              representative.length(),
              representative.supersededBy(),
              representative.handle() == null ? null : UUID.fromString(representative.handle()),
              representative.recordedAt(),
              representative.sourceRevision(),
              sources,
              sources.size());
      hits.add(
          LogSearchView.Hit.of(hit)
              .withEvidence(
                  new LogSearchView.Evidence(
                      candidate.retrievedBy(),
                      candidate.position(),
                      representative.sourceRevision())));
    }
    var scopedIndex = index.inConversation(window.conversation());
    if (window.includeInputs()) scopedIndex = scopedIndex.includingInputs(true);
    var r = window.retrieval();
    var retrieval =
        new LogSearchView.Retrieval(
            r.requestedMode(),
            r.effectiveMode(),
            r.totalMeaning(),
            r.snapshot(),
            r.truncated(),
            r.complete() && !changed && !referencesTruncated,
            changed
                ? "Source visibility/revision changed; invalid references suppressed"
                : referencesTruncated
                    ? "Occurrence references are limited to twenty per group"
                    : r.fallback(),
            replayed ? scopedIndex.coverage(window.home(), "entry") : r.coverage(),
            r.generation(),
            replayed ? 0 : r.queryEmbeddingCalls(),
            r.provenance());
    var reach =
        account == null
            ? window.reach()
            : entries.searchReach(window.home(), window.conversation());
    return new LogSearchView(
        hits,
        extent,
        skip,
        most,
        new LogSearchView.Reach(reach.searched(), reach.ejected(), reach.recordedOnly()),
        retrieval);
  }
}
