package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.PassageIndex.*;
import io.aeyer.plowshare.server.embedding.*;
import java.util.List;
import java.util.Optional;

/** Committed retrieval sources and atomic derived-vector publication, without model dispatch. */
public interface PassageRepository {
  record ReadScope(boolean constrained, String account) {
    public static final ReadScope UNRESTRICTED = new ReadScope(false, null);
  }

  record LogOwner(String conversation, int turn) {}

  List<Source> pending(String generation);

  Optional<float[]> digestVector(String id, String generation);

  void failed(Source expected, String error);

  Optional<LogOwner> logOwner(String source);

  Optional<Home> digestHome(String source);

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
