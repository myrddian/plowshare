package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.RelayControl;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.RelayInspection;
import io.aeyer.plowshare.server.relay.RelayOperations;
import io.aeyer.plowshare.server.relay.RelayProcessing;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Strict transport boundary. Reading a log never creates or acknowledges a subscriber. */
@Component
public final class RelayFrames implements FrameArea {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();

  static {
    for (var shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
  }

  private final RelayInspection relay;
  private final RelayProcessing processing;

  private final RelayOperations operations;

  public RelayFrames(
      RelayInspection relay, RelayProcessing processing, RelayOperations operations) {
    this.operations = Objects.requireNonNull(operations);
    this.relay = Objects.requireNonNull(relay);
    this.processing = Objects.requireNonNull(processing);
  }

  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.RELAY_TOPICS,
        this::topics,
        FrameTypes.RELAY_LOG,
        this::log,
        FrameTypes.RELAY_PROCESS,
        this::process,
        FrameTypes.RELAY_OPERATE,
        this::operate);
  }

  Outcome operate(Map<String, Object> payload, Asking asking) {
    return Outcome.ok(
        operations.operate(
            asking.requireHandle(FrameTypes.RELAY_OPERATE),
            decode(payload, RelayControl.Request.class)));
  }

  Outcome process(Map<String, Object> payload, Asking asking) {
    return Outcome.ok(
        processing.process(
            asking.requireHandle(FrameTypes.RELAY_PROCESS),
            decode(payload, RelayLog.Process.class)));
  }

  Outcome topics(Map<String, Object> payload, Asking asking) {
    var query = decode(payload, RelayLog.TopicsQuery.class);
    return Outcome.ok(relay.topics(asking.requireHandle(FrameTypes.RELAY_TOPICS), query));
  }

  Outcome log(Map<String, Object> payload, Asking asking) {
    var query = decode(payload, RelayLog.Query.class);
    return Outcome.ok(relay.log(asking.requireHandle(FrameTypes.RELAY_LOG), query));
  }

  private static <T> T decode(Map<String, Object> payload, Class<T> type) {
    try {
      // The public read contract makes system optional for project scope. Jackson treats an
      // absent record primitive like null under FAIL_ON_NULL_FOR_PRIMITIVES. Default only absence;
      // explicit null and scalar coercion must still fail before inspection or authorization.
      if ((type == RelayLog.TopicsQuery.class || type == RelayLog.Query.class)
          && !payload.containsKey("system")) {
        payload = new HashMap<>(payload);
        payload.put("system", false);
      }
      return JSON.convertValue(payload, type);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Invalid Relay request");
    }
  }
}
