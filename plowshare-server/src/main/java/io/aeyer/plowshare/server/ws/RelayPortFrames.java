package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.RelayPorts;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Strict public ingress/egress boundary. No publisher or account is accepted from the payload. */
@Component
public final class RelayPortFrames implements FrameArea {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
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

  private final RelayPorts ports;

  public RelayPortFrames(RelayPorts ports) {
    this.ports = Objects.requireNonNull(ports);
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.RELAY_PUBLISH,
        this::publish,
        FrameTypes.RELAY_CONSUME,
        this::consume,
        FrameTypes.RELAY_ACK,
        this::ack);
  }

  Outcome publish(Map<String, Object> payload, Asking asking) {
    var request = decode(payload, RelayPort.Publish.class);
    return Outcome.ok(ports.publish(asking.requireHandle(FrameTypes.RELAY_PUBLISH), request));
  }

  Outcome consume(Map<String, Object> payload, Asking asking) {
    var request = decode(payload, RelayPort.Consume.class);
    return Outcome.ok(
        ports.consume(
            asking.requireHandle(FrameTypes.RELAY_CONSUME), request, asking.relayBudget()));
  }

  Outcome ack(Map<String, Object> payload, Asking asking) {
    var request = decode(payload, RelayPort.Ack.class);
    return Outcome.ok(ports.acknowledge(asking.requireHandle(FrameTypes.RELAY_ACK), request));
  }

  private static <T> T decode(Map<String, Object> payload, Class<T> type) {
    try {
      return JSON.convertValue(payload, type);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Invalid Relay port request");
    }
  }
}
