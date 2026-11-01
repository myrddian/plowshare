package io.aeyer.plowshare.server.relay.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.protocol.ToolContent;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelayToolContentTest {
  final String id = "11111111-1111-1111-1111-111111111111";

  @Test
  void legacy_result_wire_is_unchanged_and_v3_round_trips_ordered_refs() {
    var old =
        new RelayToolCodec.Result(
            RelayToolCodec.VERSION,
            id,
            "atlas",
            "browser",
            "capture",
            RelayToolCodec.State.COMPLETED,
            "original");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RelayToolCodec.result(
                RelayToolCodec.write(old)
                    .replace("\"text\":\"original\"", "\"text\":\"original\",\"content\":null")));
    assertFalse(RelayToolCodec.write(old).contains("content"));
    assertEquals(old, RelayToolCodec.result(RelayToolCodec.write(old)));
    var body =
        new ToolContent.Body(
            "content/1",
            List.of(
                new ToolContent.Part(
                    ToolContent.Kind.IMAGE,
                    null,
                    "img_" + "a".repeat(32),
                    0,
                    ToolContent.Use.OBSERVATION)));
    var result =
        new RelayToolCodec.Result(
            RelayToolCodec.CONTENT_VERSION,
            id,
            "atlas",
            "browser",
            "capture",
            RelayToolCodec.State.COMPLETED,
            "",
            body);
    assertEquals(result, RelayToolCodec.result(RelayToolCodec.write(result)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayToolCodec.Result(
                RelayToolCodec.VERSION,
                id,
                "atlas",
                "browser",
                "capture",
                RelayToolCodec.State.COMPLETED,
                "",
                body));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayToolCodec.Result(
                RelayToolCodec.CONTENT_VERSION,
                id,
                "atlas",
                "browser",
                "capture",
                RelayToolCodec.State.UNKNOWN,
                "",
                body));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayToolCodec.Result(
                RelayToolCodec.CONTENT_VERSION,
                id,
                "atlas",
                "browser",
                "capture",
                RelayToolCodec.State.COMPLETED,
                "different",
                body));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RelayToolCodec.result(
                RelayToolCodec.write(result)
                    .replace("\"ordinal\":0", "\"ordinal\":0,\"ordinal\":1")));
  }
}
