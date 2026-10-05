package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;

/** HTTP/WebSocket scope projection. Raw transport objects stop here, before ownership policy. */
public final class PersonalScopeDecoder {
  private PersonalScopeDecoder() {}

  public static PersonalScope decode(Map<?, ?> payload) {
    var client = new ArrayList<String>();
    collect(payload, client, 0);
    var projects = new ArrayList<String>();
    add(projects, payload.get("project"));
    var conversations = new ArrayList<String>();
    add(conversations, payload.get("conversation"));
    Object multiple = payload.get("conversations");
    if (multiple != null) {
      if (!(multiple instanceof List<?> ids) || ids.size() > 256)
        throw new CallerFault("conversations must be a bounded list");
      for (Object id : ids) add(conversations, id);
    }
    return new PersonalScope(client, projects, conversations);
  }

  public static String identity(Object value) {
    if (value == null) return null;
    if (!(value instanceof String text)) throw new CallerFault("Scope reference must be text");
    if (text.isBlank()
        || text.length() > 1024
        || text.codePoints().anyMatch(Character::isISOControl))
      throw new CallerFault("Scope reference must be a bounded nonblank identity");
    return text;
  }

  private static void add(List<String> values, Object value) {
    if (value instanceof String text && text.isBlank())
      throw new CallerFault(
          "a project home needs a project name; use Home.global() for the global tier");
    String id = identity(value);
    if (id != null) values.add(id);
    if (values.size() > 256) throw new CallerFault("Too many scope references");
  }

  private static void collect(Object value, List<String> projects, int depth) {
    if (depth > 32) throw new CallerFault("Request exceeds supported nesting depth");
    if (value instanceof Map<?, ?> map) {
      for (var entry : map.entrySet()) {
        if ("project".equals(entry.getKey()) || "collectionProject".equals(entry.getKey()))
          add(projects, entry.getValue());
        collect(entry.getValue(), projects, depth + 1);
      }
    } else if (value instanceof List<?> list) {
      for (Object item : list) collect(item, projects, depth + 1);
    }
  }
}
