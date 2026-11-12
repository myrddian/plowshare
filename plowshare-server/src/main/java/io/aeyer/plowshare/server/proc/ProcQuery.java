package io.aeyer.plowshare.server.proc;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Set;

/**
 * Bounded audit selection. Visibility is explicit and never supplies an execution identity.
 *
 * <p>{@code order} selects the keyset direction of the conversation listing only: {@code "oldest"}
 * (the default, and the only behaviour before the field existed) pages by ascending conversation id
 * with {@code after} as an exclusive lower bound; {@code "newest"} pages by descending id with
 * {@code after} as an exclusive upper bound. Conversation ids increase over time, so {@code newest}
 * returns current work first. A returned cursor continues in the direction that produced it; a
 * caller must send the same {@code order} with that cursor. It is honoured by {@code proc.list} and
 * by the conversation section of {@code proc.inspect}; {@code proc.queues}, {@code proc.calls},
 * {@code proc.tools} and the other inspection sections keep their own fixed orderings and ignore
 * it, although every reply echoes it in its selection.
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

  /** True when the conversation listing pages by descending id. */
  public boolean newest() {
    return order.equals("newest");
  }

  /** The same selection continued from {@code cursor}, preserving its direction. */
  public ProcQuery page(String cursor) {
    return new ProcQuery(
        visibility, project, conversation, root, causation, active, cursor, limit, order);
  }
}
