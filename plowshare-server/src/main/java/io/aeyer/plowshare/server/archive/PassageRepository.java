package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.PassageIndex.*;
import io.aeyer.plowshare.server.embedding.*;
import java.util.List;
import java.util.Optional;

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
