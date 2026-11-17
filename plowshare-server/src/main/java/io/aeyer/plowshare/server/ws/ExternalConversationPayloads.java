package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict Ex boundary; optional absence differs from an explicit null except for session. */
final class ExternalConversationPayloads {
  private ExternalConversationPayloads() {}

  static <T> T decode(
      Map<String, Object> payload,
      Class<T> shape,
      String type,
      Set<String> required,
      List<String> optional) {
    if (payload == null
        || !payload.keySet().containsAll(required)
        || payload.keySet().stream()
            .anyMatch(key -> !required.contains(key) && !optional.contains(key))
        || payload.entrySet().stream()
            .anyMatch(entry -> entry.getValue() == null && !entry.getKey().equals("session")))
      throw new CallerFault("Supply the complete valid request for " + type);
    Map<String, Object> complete = new LinkedHashMap<>(payload);
    // EXACT refuses missing creator properties; fill only admitted absent optional fields.
    // This does not coerce a present null into omission or alter the client's text.
    optional.forEach(field -> complete.putIfAbsent(field, null));
    return Payloads.exact(complete, shape, type);
  }
}
