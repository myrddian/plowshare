package io.aeyer.plowshare.server.proc;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Set;

/** Bounded audit selection. Visibility is explicit and never supplies an execution identity. */
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
    Integer limit) {
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
  }

  public boolean fleet() {
    return visibility.equals("server");
  }

  public ProcQuery page(String cursor) {
    return new ProcQuery(visibility, project, conversation, root, causation, active, cursor, limit);
  }
}
