package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.server.embedding.QueryEmbeddings;
import java.util.List;
import java.util.Objects;

/** Admits only recall and reminder reads, never write judgment, curation or vector publication. */
public interface MemoryQueries {
  enum Kind {
    RECALL,
    REMINDER
  }

  /**
   * Trusted entry points supply identity, parent ceiling and live origin independently of usage.
   */
  record Request(
      String account,
      Home home,
      Kind kind,
      String text,
      int limit,
      boolean runBound,
      QueryEmbeddings.Origin origin) {
    public io.aeyer.plowshare.server.access.KnowledgeReadScope readScope() {
      return new io.aeyer.plowshare.server.access.KnowledgeReadScope(
          home, !runBound || origin.globalRead());
    }

    public Request {
      if (account == null || account.isBlank() || text == null || text.isBlank())
        throw new IllegalArgumentException("Authenticated memory query required");
      Objects.requireNonNull(home);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(origin);
      if (runBound && (home.isGlobal() || origin.invocation() == null))
        throw new IllegalArgumentException("Memory query needs its retained home and invocation");
      if (kind == Kind.REMINDER && !runBound)
        throw new IllegalArgumentException("Reminder needs its retained turn");
    }
  }

  record Result(List<Memory> memories, int unsearchable) {
    public Result {
      memories = List.copyOf(memories);
    }
  }

  /**
   * Provider IO precedes the final read transaction; completed requests confer no reusable permit.
   */
  Result read(Request request);
}
