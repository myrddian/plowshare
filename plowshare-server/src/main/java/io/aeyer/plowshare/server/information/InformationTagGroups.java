package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;

/** Bounded many-to-many category membership; groups never manufacture document tags. */
public final class InformationTagGroups {
  private InformationTagGroups() {}

  public static Map<String, List<String>> from(Object raw, Collection<String> tags) {
    if (!(raw instanceof Map<?, ?> groups) || groups.size() > 16)
      throw new CallerFault("tag groups must be an object of at most 16 categories");
    var allowed = tags == null ? null : new HashSet<>(tags);
    var result = new TreeMap<String, List<String>>();
    for (var entry : groups.entrySet()) {
      if (!(entry.getKey() instanceof String))
        throw new CallerFault("tag group names must be strings");
      String name = InformationFacets.tags(List.of(entry.getKey())).getFirst();
      var members = InformationFacets.tags(entry.getValue());
      if (members.isEmpty())
        throw new CallerFault("tag groups must contain at least one existing tag");
      if (allowed != null && !allowed.containsAll(members))
        throw new CallerFault("tag groups can only refer to this document's existing tags");
      var merged = new TreeSet<>(result.getOrDefault(name, List.of()));
      merged.addAll(members);
      if (merged.size() > 32) throw new CallerFault("a tag group can contain at most 32 tags");
      result.put(name, List.copyOf(merged));
    }
    return Collections.unmodifiableMap(result);
  }
}
