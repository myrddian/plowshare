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
   *
   * <p><strong>{@code acting} is an authority subject, not a provenance value.</strong> Every place
   * it is read is a gate: {@code MemoryStore.pin(Request)}'s {@code authority_accounts a WHERE
   * a.legacy_handle=? AND information_global_read_member(?)} arm and its {@code
   * contained_memory_actor_allows(?,?,false)} arm, {@code information_lock_scope} in {@code
   * MemoryStore.lockRead}, and {@code PersonalSpaces.requireOwn} plus {@code
   * ScopedPermissions.allows} in {@code BoundMemoryQueries.requireScope}. A read books nothing to
   * it at all — {@code embedding_queries.account_id} takes {@code QuerySources.Pin#account()},
   * which the pin's SQL projects. It was called {@code account}; the rename is so that a run inside
   * a contained Application presents its CHILD principal here, which is what V232's arm admits.
   *
   * @param acting the principal every authority gate on the read path is asked about
   */
  record Request(
      String acting,
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
      if (acting == null || acting.isBlank() || text == null || text.isBlank())
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
