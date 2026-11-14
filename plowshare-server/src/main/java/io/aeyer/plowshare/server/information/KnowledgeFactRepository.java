package io.aeyer.plowshare.server.information;

import java.util.UUID;

/**
 * Reads the tenant-independent facts of the targeted readability check, one row group per call.
 *
 * <p>Each method is one indexed statement in the caller's current transaction context and returns
 * the row as of that statement. It performs no authorization: the facts are shared across accounts
 * and {@link KnowledgeReadability} decides access from them. Results are never truncated; a row
 * with more than {@link KnowledgeFact#MAX_EDGES} edges of one kind is reported as {@link
 * KnowledgeFact.Oversized}. Database failures propagate.
 */
public interface KnowledgeFactRepository {
  /** The revision, or {@link KnowledgeFact.Absent}, or {@link KnowledgeFact.Oversized}. */
  KnowledgeFact revision(UUID id);

  /** The resource, or {@link KnowledgeFact.Absent}, or {@link KnowledgeFact.Oversized}. */
  KnowledgeFact resource(UUID id);
}
