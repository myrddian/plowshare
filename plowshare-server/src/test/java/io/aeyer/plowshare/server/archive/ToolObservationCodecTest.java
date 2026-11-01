package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.ToolContent;
import io.aeyer.plowshare.server.agents.ToolObservation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolObservationCodecTest {
  @Test
  void stored_content_preserves_explicit_withholding_and_rejects_missing_or_coerced_fields() {
    var content =
        new ToolContent.Body(
            "content/1",
            List.of(
                new ToolContent.Part(
                    ToolContent.Kind.IMAGE,
                    null,
                    "img_" + "a".repeat(32),
                    0,
                    ToolContent.Use.BOTH)));
    var original = new ToolObservation(UUID.randomUUID(), content, null);
    var encoded = ToolObservationCodec.write(original);
    assertEquals(original, ToolObservationCodec.read(encoded));
    assertThrows(
        InvalidToolObservationException.class,
        () -> ToolObservationCodec.read(encoded.replace(",\"effective\":null", "")));
    assertThrows(
        InvalidToolObservationException.class,
        () -> ToolObservationCodec.read(encoded.replace("\"ordinal\":0", "\"ordinal\":\"0\"")));
    assertThrows(
        InvalidToolObservationException.class,
        () -> ToolObservationCodec.read(encoded.replace("\"ordinal\":0", "\"ordinal\":0.5")));
    assertThrows(
        InvalidToolObservationException.class, () -> ToolObservationCodec.read(encoded + " {}"));
  }
}
