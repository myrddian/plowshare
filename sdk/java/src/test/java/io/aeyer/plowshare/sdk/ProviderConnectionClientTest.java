package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;

class ProviderConnectionClientTest {
  static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final Definition DEFINITION =
      new Definition(
          "Fixture", "https://provider.example/mcp", Authentication.API_KEY, "X-API-Key", null);

  static Connection connection() {
    return new Connection(ID, "fixture", ID, true, false, DEFINITION);
  }

  static Credential credential() {
    return new Credential(ID, "fixture", "owner", ID, ID, State.READY, null, "fixture-key");
  }

  @Test
  void older_server_refusals_remain_explicit_for_owner_management_without_secret_payloads()
      throws Exception {
    var received = new AtomicInteger();
    var json = SdkJson.mapper();
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      try {
                        var input = json.readTree(text);
                        received.incrementAndGet();
                        var type = input.path("type").textValue();
                        var body = json.createObjectNode();
                        body.put("id", input.path("id").textValue());
                        body.put("type", type);
                        body.put("protocol_version", "plowshare-v1");
                        var outcome = body.putObject("payload");
                        outcome.put("code", "NOT_FOUND");
                        outcome.put("said", "no handler is registered for type \"" + type + "\"");
                        outcome.putObject("payload").put("accessToken", "must-not-escape");
                        socket.send(json.writeValueAsString(body));
                      } catch (IOException invalid) {
                        throw new AssertionError(invalid);
                      }
                    }
                  }));
      try (var client =
          Plowshare.connect(
              server.url("/").toString(),
              "fixture-bearer",
              "fixture-session",
              Duration.ofSeconds(5),
              ignored -> {},
              Plowshare.TransportMode.LEGACY,
              Plowshare.DispatchMode.ORDERED)) {
        var provider =
            assertThrows(
                IOException.class, () -> new ProviderConnectionClient(client).list("fixture"));
        assertTrue(provider.getMessage().contains("NOT_FOUND"));
        assertFalse(provider.getMessage().contains("must-not-escape"));
        var hosted =
            assertThrows(
                IOException.class,
                () ->
                    new HostedToolScopeClient(client)
                        .list(new io.aeyer.plowshare.protocol.HostedToolScopes.Query("fixture")));
        assertTrue(hosted.getMessage().contains("NOT_FOUND"));
        assertFalse(hosted.getMessage().contains("must-not-escape"));
        assertEquals(2, received.get());
      }
    }
  }

  @Test
  void nullable_metadata_fields_are_required_and_uuid_text_cannot_be_coerced() throws Exception {
    var json = SdkJson.mapper();
    var wire = json.valueToTree(credential());
    assertEquals(credential(), SdkJson.decode(json, wire, Credential.class));
    var missing = wire.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) missing).remove("keyId");
    assertThrows(IOException.class, () -> SdkJson.decode(json, missing, Credential.class));
    var invalid = wire.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).put("generation", "1-1-1-1-1");
    assertThrows(IOException.class, () -> SdkJson.decode(json, invalid, Credential.class));
    var future = wire.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) future).put("accessToken", "private-access");
    assertFalse(
        json.writeValueAsString(SdkJson.decode(json, future, Credential.class))
            .contains("private-access"));
  }

  @Test
  void a_foreign_receipt_is_refused_after_one_send_and_nullable_ownership_is_explicit()
      throws Exception {
    var received = new AtomicInteger();
    var json = SdkJson.mapper();
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      try {
                        var input = json.readTree(text);
                        received.incrementAndGet();
                        assertEquals("provider.credential.put", input.path("type").textValue());
                        assertTrue(input.path("payload").has("subject"));
                        assertTrue(input.path("payload").path("subject").isNull());
                        var receipt =
                            new Receipt(
                                UUID.randomUUID(),
                                "provider.credential.put",
                                connection(),
                                credential());
                        socket.send(
                            "{\"id\":\""
                                + input.path("id").textValue()
                                + "\",\"type\":\"provider.credential.put\",\"protocol_version\":\"plowshare-v1\",\"payload\":{\"code\":\"OK\",\"payload\":"
                                + json.writeValueAsString(receipt)
                                + "}}");
                      } catch (IOException invalid) {
                        throw new AssertionError(invalid);
                      }
                    }
                  }));
      try (var client =
          Plowshare.connect(
              server.url("/").toString(),
              "fixture-bearer",
              "fixture-session",
              Duration.ofSeconds(5),
              ignored -> {},
              Plowshare.TransportMode.LEGACY,
              Plowshare.DispatchMode.ORDERED)) {
        assertThrows(
            IOException.class,
            () ->
                new ProviderConnectionClient(client)
                    .put(new Put("fixture", ID, null, ID, null, "private-key")));
        assertEquals(1, received.get());
      }
    }
  }
}
