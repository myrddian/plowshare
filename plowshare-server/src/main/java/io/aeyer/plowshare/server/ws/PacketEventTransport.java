package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.transport.*;
import java.io.IOException;
import java.util.Optional;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** All writes share the same lock; byte credits can pass a blocked logical-message sender. */
final class PacketEventTransport implements EventTransport, PacketWire {
  private final WebSocketSession socket;
  private final Object writeLock = new Object();
  private final ObjectMapper json;
  private final SegmentedMessages packets;

  PacketEventTransport(WebSocketSession socket, ObjectMapper json, PacketBudget budget) {
    this.socket = socket;
    this.json =
        json.copy()
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    this.json
        .coercionConfigFor(LogicalType.Integer)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    this.json
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    packets = new SegmentedMessages(this, budget, this::abort);
  }

  public Optional<String> receive(String frame) throws IOException {
    if (frame.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        > SegmentedMessages.MAX_PACKET_BYTES)
      throw new IOException("packet exceeds wire allowance");
    var tree = json.readTree(frame);
    if (tree == null || !tree.isObject() || !tree.path("kind").isTextual())
      throw new IOException("invalid packet");
    return switch (tree.get("kind").textValue()) {
      case "transport.segment" -> packets.receive(json.treeToValue(tree, MessageSegment.class));
      case "transport.credit" -> {
        packets.credit(json.treeToValue(tree, SegmentCredit.class));
        yield Optional.empty();
      }
      default -> throw new IOException("unsupported packet kind");
    };
  }

  public void send(String frame) throws IOException {
    packets.send(frame);
  }

  public void segment(MessageSegment value) throws IOException {
    write(value);
  }

  public void credit(SegmentCredit value) throws IOException {
    write(value);
  }

  private void write(Object packet) throws IOException {
    synchronized (writeLock) {
      socket.sendMessage(new TextMessage(json.writeValueAsString(packet)));
    }
  }

  private void abort() {
    try {
      socket.close(CloseStatus.BAD_DATA.withReason("packet transfer failed"));
    } catch (IOException unusable) {
      packets.close();
    }
  }

  public void close() {
    packets.close();
  }
}
