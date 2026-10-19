package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.ResourceScopeRepository.Kind;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;

/** Decodes execution-target metadata only; each tool still owns its complete input contract. */
final class OperationToolTargets {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private OperationToolTargets() {}

  static AccessRequest decode(String tool, String input) {
    if (tool.equals("memory_read") || tool.equals("conversation_chat"))
      return new AccessRequest(Set.of(), RetainedToolAccess.resources(tool, input), false, null);
    // Provider/file/information tools retain their own typed capability and home checks. Their
    // arbitrary argument names must never be reinterpreted as account authorization metadata.
    if (!Set.of("conversation_trajectory", "conversation_context").contains(tool))
      return new AccessRequest(Set.of(), List.of(), false, null);
    try {
      JsonNode root = JSON.readTree(input == null || input.isBlank() ? "{}" : input);
      if (root == null || !root.isObject())
        throw new CallerFault("Tool arguments must be an object");
      var conversation = root.get("conversation");
      var references =
          conversation == null
              ? List.<AccessRequest.Resource>of()
              : List.of(new AccessRequest.Resource(Kind.CONVERSATION, text(conversation)));
      return new AccessRequest(Set.of(), references, false, null);
    } catch (java.io.IOException malformed) {
      throw new CallerFault("Malformed tool target metadata");
    }
  }

  private static String text(JsonNode value) {
    if (!value.isTextual()) throw new CallerFault("Exact tool target identity required");
    return value.textValue();
  }
}
