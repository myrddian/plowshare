package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelayBlobTest {
  @Test
  void descriptor_is_bounded_and_contains_no_location_or_bytes() throws Exception {
    var descriptor = new RelayBlob.Descriptor("a".repeat(64), 3, "image/png");
    var payload = new RelayLog.Payload("BLOB", null, null, null, null, null, null, descriptor);
    String json = new ObjectMapper().writeValueAsString(payload);
    assertTrue(json.contains("\"blob\""));
    assertFalse(json.contains("file"));
    assertEquals(payload, new ObjectMapper().readValue(json, RelayLog.Payload.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayBlob.Descriptor("a".repeat(32), 3, "image/png"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayBlob.Descriptor("a".repeat(64), 0, "image/png"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayBlob.Descriptor("a".repeat(64), RelayBlob.MAX_BYTES + 1L, "image/png"));
    for (String mime :
        new String[] {
          "Image/PNG",
          "image/*",
          "image/png;foo=bar",
          "image/png\r\nx: y",
          "https://example.invalid/file"
        })
      assertThrows(
          IllegalArgumentException.class, () -> new RelayBlob.Descriptor("a".repeat(64), 3, mime));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayLog.Payload("TEXT", "text", null, null, null, null, null, descriptor));
    assertThrows(
        IllegalArgumentException.class, () -> new RelayLog.Payload("BLOB", null, null, null, null));
  }

  @Test
  void text_serialization_has_no_new_null_blob_field() throws Exception {
    var payload = new RelayLog.Payload("TEXT", "complete text", null, null, null);
    assertEquals(
        "{\"kind\":\"TEXT\",\"text\":\"complete text\",\"schedule\":null,\"emits\":null,\"fireAt\":null,\"lifecycle\":null,\"wake\":null}",
        new ObjectMapper().writeValueAsString(payload));
  }

  @Test
  void metadata_and_reads_share_existing_port_validation() {
    String id = UUID.randomUUID().toString();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayBlob.Publish(
                id,
                "atlas",
                "media.capture",
                Instant.EPOCH,
                "image/png",
                null,
                "source.topic",
                null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayBlob.Read("atlas", "media.capture", "group", "not-a-uuid"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayBlob.Publish(
                id,
                "atlas",
                "media.capture",
                Instant.parse("0000-01-01T00:00:00Z"),
                "image/png",
                null,
                null,
                null));
  }
}
