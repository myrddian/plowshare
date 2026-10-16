package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.server.access.AccessRequest;
import io.aeyer.plowshare.server.access.ResourceScopeRepository.Kind;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Argument boundary for synchronous tools whose execution retains authority. This is capability
 * plumbing, not a second grant list: the definition must still grant every offered tool. New tools
 * must describe resource references here or carry authority into their independent consumers before
 * they can be used by retained work. In particular, a background model/job or approval continuation
 * cannot inherit authority merely by receiving the subject's account name.
 */
public final class RetainedToolAccess {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Set<String> SYNCHRONOUS =
      Set.of(
          "get_date",
          "fetch",
          "search",
          "memory_read",
          "memory_index",
          "conversation_list",
          "conversation_chat",
          "run",
          "result_read",
          "result_list",
          "todo_read",
          "todo_write",
          "agent_run");

  private RetainedToolAccess() {}

  static boolean supported(String name) {
    return SYNCHRONOUS.contains(name) || FileTools.NAMES.contains(name);
  }

  /** Decode only access metadata; raw JSON never reaches the authority contract. */
  static List<AccessRequest.Resource> resources(String name, String arguments) {
    if (!supported(name)) throw new CallerFault("Tool does not yet retain acting authority");
    if (!name.equals("memory_read") && !name.equals("conversation_chat")) return List.of();
    try {
      JsonNode args = JSON.readTree(arguments);
      if (args == null || !args.isObject()) throw invalid();
      if (name.equals("conversation_chat"))
        return List.of(resource(Kind.CONVERSATION, args.get("conversation")));
      JsonNode ids = args.get("ids");
      if (ids != null && ids.isTextual()) return List.of(resource(Kind.MEMORY, ids));
      if (ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > 256) throw invalid();
      List<AccessRequest.Resource> result = new ArrayList<>();
      for (JsonNode id : ids) result.add(resource(Kind.MEMORY, id));
      return List.copyOf(result);
    } catch (IOException | IllegalArgumentException malformed) {
      throw invalid();
    }
  }

  private static AccessRequest.Resource resource(Kind kind, JsonNode id) {
    if (id == null || !id.isTextual()) throw invalid();
    var resource = new AccessRequest.Resource(kind, id.textValue());
    if (!resource.id().equals(id.textValue())) throw invalid();
    return resource;
  }

  private static CallerFault invalid() {
    return new CallerFault("Choose exact scoped resource IDs");
  }
}
