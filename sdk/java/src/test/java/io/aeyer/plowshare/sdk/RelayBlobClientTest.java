package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.RelayBlob;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.Test;

class RelayBlobClientTest {
  final byte[] bytes = new byte[] {0, 1, -1};
  final RelayBlob.Publish request =
      new RelayBlob.Publish(
          UUID.randomUUID().toString(),
          "atlas",
          "media.capture",
          Instant.parse("2026-10-22T00:00:00Z"),
          "image/png",
          null,
          null,
          null);

  RelayBlob.Descriptor descriptor() throws Exception {
    return new RelayBlob.Descriptor(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        bytes.length,
        request.mediaType());
  }

  @Test
  void publication_checks_receipt_and_never_replays_refusals_or_redirects() throws Exception {
    var receipt =
        new RelayBlob.Published(
            new RelayPort.Published(
                request.requestId(), request.project(), request.topic(), "1", request.occurredAt()),
            descriptor());
    try (var server = new MockWebServer();
        var client = new WsServerClient(server.url("/").toString(), "fixture")) {
      server.enqueue(new MockResponse().setBody(SdkJson.mapper().writeValueAsString(receipt)));
      assertEquals(receipt, client.publishRelayBlob(request, bytes));
      var sent = server.takeRequest();
      assertEquals("/v1/relay/blobs", sent.getPath());
      assertEquals("Bearer fixture", sent.getHeader("Authorization"));
      assertTrue(sent.getBody().readUtf8().contains(request.requestId()));
      server.enqueue(new MockResponse().setResponseCode(503));
      assertThrows(ServerClient.ServerError.class, () -> client.publishRelayBlob(request, bytes));
      server.enqueue(
          new MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")));
      assertThrows(ServerClient.ServerError.class, () -> client.publishRelayBlob(request, bytes));
      assertEquals(3, server.getRequestCount());
    }
  }

  @Test
  void bytes_are_bound_to_the_consumed_descriptor_and_publication_selector() throws Exception {
    var read =
        new RelayBlob.Read(request.project(), request.topic(), "workers", request.requestId());
    try (var server = new MockWebServer();
        var client = new WsServerClient(server.url("/").toString(), "fixture")) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/octet-stream")
              .setBody(new Buffer().write(bytes)));
      assertArrayEquals(bytes, client.readRelayBlob(read, descriptor()));
      assertTrue(server.takeRequest().getPath().contains("eventId=" + request.requestId()));
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/octet-stream")
              .setBody(new Buffer().write(new byte[] {3, 2, 1})));
      assertThrows(IOException.class, () -> client.readRelayBlob(read, descriptor()));
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/octet-stream")
              .setChunkedBody(new Buffer().write(new byte[] {0, 1, -1, 0}), 1));
      assertThrows(IOException.class, () -> client.readRelayBlob(read, descriptor()));
      server.enqueue(new MockResponse().setBody(" ".repeat(16385)));
      assertThrows(IOException.class, () -> client.publishRelayBlob(request, bytes));
    }
  }
}
