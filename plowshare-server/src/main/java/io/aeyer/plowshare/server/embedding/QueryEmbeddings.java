package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.information.InformationContext;
import java.util.*;
import java.util.function.Supplier;

/**
 * Admits a synchronous read operation; no public job, caller-chosen payer or restart is implied.
 */
public interface QueryEmbeddings {
  enum Kind {
    SEARCH,
    RETRIEVE,
    RANK,
    STANCE
  }

  record Query(Kind kind, String text, UUID revision, int limit, String mode) {
    public Query {
      Objects.requireNonNull(kind);
      if (text == null || text.isBlank() || limit < 1)
        throw new IllegalArgumentException("Valid query required");
    }

    public List<String> inputs() {
      return kind == Kind.STANCE ? List.of(text, "not " + text) : List.of(text);
    }
  }

  /**
   * The live owner fence is server supplied; invocation keys exist only for retained tool calls.
   */
  record Origin(String invocation, Runnable validity) {
    public Origin {
      Objects.requireNonNull(validity);
      if (invocation != null
          && (invocation.isBlank()
              || invocation.length() > 1024
              || invocation.codePoints().anyMatch(Character::isISOControl)))
        throw new IllegalArgumentException("Bounded query invocation identity required");
    }

    public static Origin request(Runnable validity) {
      return new Origin(null, validity);
    }
  }

  interface Read extends EmbeddingPermit, AutoCloseable {
    /** Runs result assembly under policy/identity locks; provider IO must already be complete. */
    <T> T disclose(Supplier<T> read);

    void close();
  }

  Read open(InformationContext context, Query query, Origin origin);
}
