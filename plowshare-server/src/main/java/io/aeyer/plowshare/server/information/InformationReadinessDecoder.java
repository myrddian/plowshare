package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;

/** Parses untrusted readiness references before invoking the fence. */
public final class InformationReadinessDecoder {
  private InformationReadinessDecoder() {}

  public static List<InformationReadiness.Source> sources(Object value) {
    if (!(value instanceof List<?> rows) || rows.isEmpty() || rows.size() > 100)
      throw new CallerFault("readiness sources must contain 1..100 source references");
    var sources = new LinkedHashSet<InformationReadiness.Source>();
    for (Object row : rows) {
      if (!(row instanceof Map<?, ?> fields))
        throw new CallerFault("each fence source must be an object");
      if (!Set.of("revision", "acquisition").containsAll(fields.keySet()))
        throw new CallerFault("unknown readiness source field");
      sources.add(
          new InformationReadiness.Source(id(fields, "revision"), id(fields, "acquisition")));
    }
    return List.copyOf(sources);
  }

  private static UUID id(Map<?, ?> fields, String name) {
    Object value = fields.get(name);
    if (value == null) return null;
    if (!(value instanceof String text)) throw new CallerFault(name + " must be a UUID");
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault(name + " must be a UUID");
    }
  }
}
