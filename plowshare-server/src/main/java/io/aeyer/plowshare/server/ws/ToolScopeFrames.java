package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.ToolScopes;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.tools.ToolScopeConnections;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Provider assignments derive their identity from the upgrade, never request fields. */
@Component
public final class ToolScopeFrames implements FrameArea {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(
              DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();

  static {
    for (var shape :
        new com.fasterxml.jackson.databind.cfg.CoercionInputShape[] {
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean
        })
      JSON.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
          .setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
  }

  private final ToolScopeConnections connections;

  public ToolScopeFrames(ToolScopeConnections connections) {
    this.connections = java.util.Objects.requireNonNull(connections);
  }

  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.TOOL_SCOPE_CONNECT,
        this::connect,
        FrameTypes.TOOL_SCOPE_LIST,
        this::list,
        FrameTypes.TOOL_SCOPE_DISCONNECT,
        this::disconnect);
  }

  private Outcome connect(Map<String, Object> payload, Asking asking) {
    return Outcome.ok(
        connections.connect(
            asking.requireHandle(FrameTypes.TOOL_SCOPE_CONNECT),
            asking.sessionId(),
            asking.connectionId(),
            decode(payload, ToolScopes.Connect.class)));
  }

  private Outcome list(Map<String, Object> payload, Asking asking) {
    return Outcome.ok(
        connections.list(
            asking.requireHandle(FrameTypes.TOOL_SCOPE_LIST),
            asking.sessionId(),
            decode(payload, ToolScopes.Query.class)));
  }

  private Outcome disconnect(Map<String, Object> payload, Asking asking) {
    return Outcome.ok(
        connections.disconnect(
            asking.requireHandle(FrameTypes.TOOL_SCOPE_DISCONNECT),
            asking.sessionId(),
            decode(payload, ToolScopes.Disconnect.class)));
  }

  private static <T> T decode(Map<String, Object> payload, Class<T> type) {
    try {
      return JSON.convertValue(payload, type);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Invalid tool scope request");
    }
  }
}
