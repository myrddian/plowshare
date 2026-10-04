package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Context correlation only: an acknowledgment is not physical verification. */
final class Feedback {
  static final int MAX_PER_BINDING = 64, MAX_TOTAL = 256;
  static final int MAX_DEPTH = 16;

  private Feedback() {}

  static String context(JsonNode value) {
    return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 128
        ? value.asText()
        : null;
  }

  static String key(String binding, String context) {
    return Json.identity(binding + ":" + context).toString();
  }

  record Correlation(JsonNode match, ObjectNode descendant, String ambiguousKey) {}

  private record Link(String operation, String root, long expiresAt, int depth) {}

  /** Every retained parent must still lead to the same unambiguous acknowledged root. */
  private static Link resolve(
      JsonNode causes, Configuration.Binding binding, String context, long now) {
    if (context == null) return null;
    JsonNode first = causes.path(key(binding.name(), context));
    int depth = first.path("depth").asInt(0);
    String operation = first.path("operation").asText();
    long expires = first.path("expiresAt").asLong();
    if (depth < 0 || depth > MAX_DEPTH || expires <= now) return null;
    String cursor = context;
    for (int remaining = depth; remaining >= 0; remaining--) {
      JsonNode entry = causes.path(key(binding.name(), cursor));
      if (!entry.path("binding").asText().equals(binding.name())
          || !entry.path("fingerprint").asText().equals(binding.fingerprint())
          || entry.path("ambiguous").asBoolean()
          || entry.path("expiresAt").asLong() != expires
          || !entry.path("operation").asText().equals(operation)
          || entry.path("depth").asInt(0) != remaining) return null;
      if (remaining == 0) return new Link(operation, cursor, expires, depth);
      cursor = context(entry.path("parent"));
      if (cursor == null) return null;
    }
    return null;
  }

  static Correlation correlate(
      JsonNode causes, Configuration.Binding binding, JsonNode event, long now) {
    Correlation absent = new Correlation(Json.MAPPER.missingNode(), null, null);
    if (!binding.feedback().suppressPipelineStarts()) return absent;
    String id = context(event.path("context").path("id"));
    String parent = context(event.path("context").path("parent_id"));
    String ownKey = id == null ? null : key(binding.name(), id);
    JsonNode own = ownKey == null ? Json.MAPPER.missingNode() : causes.path(ownKey);
    boolean retained = !own.isMissingNode() && own.path("expiresAt").asLong() > now;
    Link exact = resolve(causes, binding, id, now),
        ancestor = resolve(causes, binding, parent, now);
    boolean conflict =
        id != null && id.equals(parent)
            || retained
                && own.path("depth").asInt(0) > 0
                && parent != null
                && !parent.equals(own.path("parent").asText())
            || exact != null
                && ancestor != null
                && (!exact.operation().equals(ancestor.operation())
                    || !exact.root().equals(ancestor.root())
                    || ancestor.depth() >= exact.depth());
    if (conflict) return new Correlation(absent.match(), null, retained ? ownKey : null);
    // An invalid/ambiguous retained identity must not escape through its parent.
    if (retained && exact == null) return absent;
    int limit = binding.feedback().maxDepth();
    Link matched =
        exact != null && exact.depth() <= limit
            ? exact
            : ancestor != null && ancestor.depth() < limit ? ancestor : null;
    if (matched == null) return absent;
    boolean direct = matched == exact;
    int depth = direct ? matched.depth() : matched.depth() + 1;
    ObjectNode descendant = null;
    if (!direct && id != null && !retained && limit > 1)
      descendant =
          Json.object()
              .put("binding", binding.name())
              .put("fingerprint", binding.fingerprint())
              .put("context", id)
              .put("operation", matched.operation())
              .put("expiresAt", matched.expiresAt())
              .put("depth", depth)
              .put("parent", parent);
    return new Correlation(
        Json.object()
            .put("operation", matched.operation())
            .put("context", direct ? id : parent)
            .put("relation", direct ? "id" : "parent_id")
            .put("rootContext", matched.root())
            .put("depth", depth)
            .put("expiresAt", matched.expiresAt())
            .put("pipelineStartsSuppressed", true),
        descendant,
        null);
  }
}
