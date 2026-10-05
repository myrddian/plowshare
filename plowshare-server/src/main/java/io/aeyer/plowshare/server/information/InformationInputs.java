package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;

/** Transport and configuration codec for information discovery selections. */
public final class InformationInputs {
  private InformationInputs() {}

  public static InformationContext.Corpus corpus(Object value) {
    if (value == null) return null;
    if (!(value instanceof String name)) throw new CallerFault("corpus must be documents or code");
    return switch (name) {
      case "documents" -> InformationContext.Corpus.DOCUMENTS;
      case "code" -> InformationContext.Corpus.CODE;
      default -> throw new CallerFault("corpus must be documents or code");
    };
  }

  public static InformationFacets facets(Object raw) {
    if (raw == null) return InformationFacets.NONE;
    if (!(raw instanceof Map<?, ?> fields)) throw new CallerFault("filter must be an object");
    for (Object key : fields.keySet())
      if (!(key instanceof String name)
          || !InformationFacets.NAMES.contains(name) && !name.equals("search"))
        throw new CallerFault("unknown information facet: " + key);
    return new InformationFacets(
        text(fields, "kind"),
        tags(fields, "tags"),
        tags(fields, "autoTag"),
        text(fields, "tagGroup"),
        text(fields, "author"),
        text(fields, "documentAuthor"),
        text(fields, "when"),
        text(fields, "subtype"),
        text(fields, "search"));
  }

  private static String text(Map<?, ?> fields, String name) {
    if (!fields.containsKey(name)) return null;
    if (!(fields.get(name) instanceof String value))
      throw new CallerFault(name + " must be bounded nonblank text");
    return value;
  }

  private static List<String> tags(Map<?, ?> fields, String name) {
    return fields.containsKey(name) ? InformationFacets.tags(fields.get(name)) : List.of();
  }
}
