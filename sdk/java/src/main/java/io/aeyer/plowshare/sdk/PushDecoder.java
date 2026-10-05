package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.*;
import java.io.IOException;

/** Recognizes supported notification shapes before any consumer callback runs. */
final class PushDecoder {
  private PushDecoder() {}

  static ServerPush decode(ObjectMapper json, JsonNode frame) throws IOException {
    if (!frame.isObject()) throw new IOException("notification must be an object");
    JsonNode payload = frame;
    String kind;
    if (frame.has("protocol_version")) {
      if (!"plowshare-v1".equals(frame.path("protocol_version").textValue()))
        throw new IOException("unsupported notification protocol");
      kind = frame.path("type").textValue();
      payload = frame.path("payload");
    } else {
      kind = frame.path("kind").textValue();
      if (kind == null && frame.has("part")) return SdkJson.decode(json, frame, JobDelta.class);
    }
    if (kind == null) throw new IOException("missing notification kind");
    return switch (kind) {
      case "started", "model_call", "tool_called", "alive", "ended" ->
          SdkJson.decode(json, payload, JobEvent.class);
      case "inbox.changed" -> SdkJson.decode(json, payload, AccountEvent.InboxChanged.class);
      case "information.changed" ->
          SdkJson.decode(json, payload, AccountEvent.InformationChanged.class);
      case "orchestration.resumed" ->
          SdkJson.decode(json, payload, AccountEvent.OrchestrationResumed.class);
      case "orchestration.changed" ->
          SdkJson.decode(json, payload, AccountEvent.OrchestrationChanged.class);
      case "orchestration.recorded" ->
          SdkJson.decode(json, payload, AccountEvent.OrchestrationRecorded.class);
      case "todos.changed" -> SdkJson.decode(json, payload, AccountEvent.TodosChanged.class);
      case "conversation.appended" -> SdkJson.decode(json, payload, ConversationGrowth.class);
      case "usage.updated" -> SdkJson.decode(json, payload, Usage.Updated.class);
      case "usage.closed" -> SdkJson.decode(json, payload, Usage.Closed.class);
      default -> throw new IOException("unsupported notification kind");
    };
  }
}
