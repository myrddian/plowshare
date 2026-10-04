package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;

/** Selection only. Account identity is supplied separately by the authenticated adapter. */
public final class InformationScopes {
  private InformationScopes() {}

  public static InformationContext.Selection from(Map<String, Object> payload) {
    Object scope = payload.get("scope");
    if (scope == null) {
      Object project = payload.get("project");
      if (project == null) return InformationContext.Selection.personal();
      if (!(project instanceof String name) || name.isBlank()) {
        throw new CallerFault("information project must be a nonblank name");
      }
      return InformationContext.Selection.project(name);
    }
    if (!(scope instanceof Map<?, ?> fields))
      throw new CallerFault("information scope must be an object");
    Object kind = fields.get("kind");
    if (!(kind instanceof String name)) throw new CallerFault("information scope needs a kind");
    Object project = fields.get("project");
    if (project != null && !(project instanceof String))
      throw new CallerFault("scope project must be a name");
    Object shared = fields.get("includeShared");
    if (shared != null && !(shared instanceof Boolean))
      throw new CallerFault("includeShared must be boolean");
    try {
      var type = InformationContext.Scope.valueOf(name.toUpperCase(java.util.Locale.ROOT));
      return new InformationContext.Selection(
          type,
          (String) project,
          type != InformationContext.Scope.SHARED && (shared == null || (Boolean) shared));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("information scope must be personal, project with a name, or shared");
    }
  }
}
