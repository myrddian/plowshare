package io.aeyer.plowshare.server.proc;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Set;

/**
 * Bounded audit selection. Visibility is explicit and never supplies an execution identity.
 *
 * <p>{@code order} selects the keyset direction of every paged PROC listing: {@code "oldest"} (the
 * default, and the only behaviour before the field existed) pages forwards with {@code after} as an
 * exclusive lower bound; {@code "newest"} reverses every key with {@code after} as an exclusive
 * upper bound, so current work comes first. A returned cursor continues in the direction that
 * produced it; a caller must send the same {@code order} and operation with that cursor. Each
 * operation has its own key and cursor shape (see {@code ProcCursor}):
 *
 * <ul>
 *   <li>{@code proc.list} and the conversation section of {@code proc.inspect}: conversation id,
 *       which is minted in creation order. The cursor is the last id.
 *   <li>{@code proc.calls}: {@code (created_at, call_id)}. The cursor is {@code
 *       <createdMicros>:<callId>}.
 *   <li>{@code proc.tools}: {@code (conversation, numeric entry ordinal, tool id)}. The cursor is
 *       the last receipt id {@code <conversation>:<ordinal>:<toolId>}.
 *   <li>{@code proc.queues}: page rows by {@code (revision created_at, row id)}; summaries stay
 *       first and independent of the cursor and direction. The cursor is {@code
 *       <revisionCreatedMicros>:<rowId>}.
 * </ul>
 *
 * <p>The queue, call and tool sections of {@code proc.inspect} use the same direction from their
 * first page. A call or queue cursor issued before these chronological keys existed is rejected
 * rather than reinterpreted.
 */
@com.fasterxml.jackson.annotation.JsonInclude(
    com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record ProcQuery(
    String visibility,
    String project,
    String conversation,
    String root,
    String causation,
    Boolean active,
    String after,
    Integer limit,
    String order) {
  /** Repositories may reserve one additional row to establish a continuation cursor. */
  static final int MAX_LIMIT = 100;

  public ProcQuery {
    visibility = visibility == null ? "mine" : visibility;
    if (!Set.of("mine", "server").contains(visibility))
      throw new CallerFault("PROC visibility must be mine or server");
    for (String value : new String[] {project, conversation, root, causation, after})
      if (value != null
          && (value.isBlank()
              || !value.equals(value.strip())
              || value.length() > 256
              || value.codePoints().anyMatch(Character::isISOControl)))
        throw new CallerFault("PROC selectors must be bounded nonblank identifiers");
    limit = limit == null ? 50 : limit;
    if (limit < 1 || limit > MAX_LIMIT) throw new CallerFault("PROC limit must be 1–100");
    order = order == null ? "oldest" : order;
    if (!Set.of("oldest", "newest").contains(order))
      throw new CallerFault("PROC order must be newest or oldest");
  }

  public boolean fleet() {
    return visibility.equals("server");
  }

  /** True when listings page by their descending keys. */
  public boolean newest() {
    return order.equals("newest");
  }

  /** The same selection continued from {@code cursor}, preserving its direction. */
  public ProcQuery page(String cursor) {
    return new ProcQuery(
        visibility, project, conversation, root, causation, active, cursor, limit, order);
  }
}
