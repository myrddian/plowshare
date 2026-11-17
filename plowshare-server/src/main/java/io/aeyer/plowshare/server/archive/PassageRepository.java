package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.PassageIndex.Coverage;
import io.aeyer.plowshare.server.archive.PassageIndex.Match;
import io.aeyer.plowshare.server.archive.PassageIndex.PassageText;
import io.aeyer.plowshare.server.archive.PassageIndex.Ranking;
import io.aeyer.plowshare.server.archive.PassageIndex.Source;
import io.aeyer.plowshare.server.embedding.EmbeddingProfile;
import io.aeyer.plowshare.server.embedding.EmbeddingQuery;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Committed retrieval sources and atomic derived-vector publication, without model dispatch. */
public interface PassageRepository {
  record ReadScope(
      boolean constrained,
      String account,
      boolean knowledgeOnly,
      String conversation,
      boolean includeInputs) {
    public ReadScope(
        boolean constrained, String account, boolean knowledgeOnly, String conversation) {
      this(constrained, account, knowledgeOnly, conversation, false);
    }

    public ReadScope(boolean constrained, String account, boolean knowledgeOnly) {
      this(constrained, account, knowledgeOnly, null);
    }

    public ReadScope(boolean constrained, String account) {
      this(constrained, account, false);
    }

    public static final ReadScope UNRESTRICTED = new ReadScope(false, null);
  }

  /**
   * Rechecks the captured embedding profile and read disclosure inside an actual ranking SQL unit.
   * Independent units release their transaction before returning; joined units retain their
   * caller's transaction and earlier deadline. The repository alone decides whether a transaction
   * is joined.
   */
  interface RankingReadUnit {
    <T> T independent(Supplier<T> read);

    <T> T joined(Supplier<T> read);
  }

  record LogOwner(String conversation, int turn) {}

  List<Source> pending(String generation);

  Optional<float[]> digestVector(String id, String generation);

  void failed(Source expected, String error);

  Optional<LogOwner> logOwner(String source);

  Optional<Home> digestHome(String source);

  default Optional<Home> sourceHome(Source source) {
    throw new UnsupportedOperationException("Passage source home unavailable");
  }

  /** Exact queue hash, content and next position; this check never acquires model authority. */
  default void requireSource(Source source, String generation) {
    throw new UnsupportedOperationException("Passage source fence unavailable");
  }

  default boolean beginRepair(Source source, String generation) {
    throw new UnsupportedOperationException("Passage repair attempt fence unavailable");
  }

  default boolean publish(
      Source expected,
      List<String> texts,
      List<float[]> vectors,
      boolean complete,
      String generation,
      Runnable fence) {
    throw new UnsupportedOperationException("Passage publication fence unavailable");
  }

  /** Publishes passage provenance atomically with its text and vectors. */
  default boolean publishProjected(
      Source expected,
      List<PassageText> texts,
      List<float[]> vectors,
      boolean complete,
      String generation,
      Runnable fence) {
    if (texts.stream().anyMatch(PassageText::suppliedContext))
      throw new UnsupportedOperationException("Projected publication is unavailable");
    return publish(
        expected,
        texts.stream().map(PassageText::text).toList(),
        vectors,
        complete,
        generation,
        fence);
  }

  default boolean publishProjectedText(
      Source expected, List<PassageText> texts, boolean complete, String generation) {
    if (texts.stream().anyMatch(PassageText::suppliedContext))
      throw new UnsupportedOperationException("Projected publication is unavailable");
    return publishText(
        expected, texts.stream().map(PassageText::text).toList(), complete, generation);
  }

  /**
   * Locks owner then source, rejects stale hashes/generations/positions and publishes atomically.
   */
  boolean publish(
      Source expected,
      List<String> texts,
      List<float[]> vectors,
      boolean complete,
      String generation);

  default boolean publishText(
      Source expected, List<String> texts, boolean complete, String generation) {
    throw new UnsupportedOperationException(
        "text publication is not implemented by this repository");
  }

  default List<Match> rank(
      Home home, String type, EmbeddingQuery query, int most, String generation, ReadScope scope) {
    throw new UnsupportedOperationException(
        "typed passage retrieval is not implemented by this repository");
  }

  /**
   * Selects authorized candidates before the passage bound, then reranks, groups and limits the
   * retained window. Approximate results never attest exhaustive recall; truncation also records
   * when duplicate passages or selective ANN filtering may have exhausted the candidate window.
   */
  default Ranking rankWindow(
      Home home, String type, EmbeddingQuery query, int most, String generation, ReadScope scope) {
    return new Ranking(rank(home, type, query, most, generation, scope), false, false);
  }

  /**
   * Bounded independent candidate reads, with one final global rerank. Implementations that cannot
   * split their SQL use one guarded unit. No partial result survives a failed unit, and callers
   * must still apply their final retained-source disclosure fence before returning search results.
   */
  default Ranking rankWindow(
      Home home,
      String type,
      EmbeddingQuery query,
      int most,
      String generation,
      ReadScope scope,
      RankingReadUnit units) {
    return units.independent(() -> rankWindow(home, type, query, most, generation, scope));
  }

  default Coverage coverage(
      Home home,
      String type,
      String generation,
      ReadScope scope,
      Optional<EmbeddingProfile> profile) {
    throw new UnsupportedOperationException(
        "typed passage coverage is not implemented by this repository");
  }

  List<Match> rank(
      Home home, String type, float[] query, int most, String generation, ReadScope scope);

  Coverage coverage(Home home, String type, String generation, ReadScope scope);

  LogSearch.Hit hit(
      Home home, String id, String revision, double rank, String snippet, ReadScope scope);

  String revision(Home home, String id, ReadScope scope);
}
