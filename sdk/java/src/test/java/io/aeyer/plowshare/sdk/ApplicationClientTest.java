package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.ApplicationDeployment.*;
import io.aeyer.plowshare.protocol.FileStoreReference;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class ApplicationClientTest {
  @Test
  void first_install_sends_explicit_null_and_foreign_receipts_never_replay() throws Exception {
    UUID requestId = UUID.randomUUID(), revision = UUID.randomUUID();
    AtomicInteger calls = new AtomicInteger();
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String wire) {
                      try {
                        var json = SdkJson.mapper();
                        var frame = json.readTree(wire);
                        int count = calls.incrementAndGet();
                        if (count == 1) {
                          assertEquals("application.deploy", frame.path("type").textValue());
                          assertTrue(frame.path("payload").has("expectedRevision"));
                          assertTrue(frame.path("payload").path("expectedRevision").isNull());
                        }
                        var result =
                            json.createObjectNode()
                                .put("project", count == 1 ? "app" : "foreign")
                                .put("requestId", requestId.toString());
                        result
                            .putObject("release")
                            .put("revision", revision.toString())
                            .put("digest", "a".repeat(64))
                            .put("fileCount", 1);
                        var answer =
                            json.createObjectNode()
                                .put("id", frame.path("id").textValue())
                                .put("type", frame.path("type").textValue())
                                .put("protocol_version", "plowshare-v1");
                        answer.putObject("payload").put("code", "OK").set("payload", result);
                        socket.send(answer.toString());
                      } catch (IOException invalid) {
                        throw new AssertionError(invalid);
                      }
                    }
                  }));
      try (var connection =
          LegacyFixture.connect(
              server.url("/").toString(), "fixture", Duration.ofSeconds(3), null)) {
        var client = new ApplicationClient(connection);
        var request =
            new Deploy(
                "app",
                requestId,
                null,
                new FileStoreReference("applications", "app"),
                List.of(),
                List.of(new File("plowshare.json", "{\"version\":1,\"name\":\"app\"}")));
        assertEquals(revision, client.deploy(request).release().revision());
        assertThrows(IOException.class, () -> client.receipt("app", requestId));
        assertEquals(2, calls.get());
      }
    }
  }

  @Test
  void catalogue_decode_refuses_malformed_aliases_roles_and_missing_fields() throws Exception {
    var json = SdkJson.mapper();
    var type = io.aeyer.plowshare.protocol.FileStoreCatalog.class;
    assertEquals(
        "applications",
        SdkJson.decode(
                json,
                json.readTree(
                    "{\"stores\":[{\"alias\":\"applications\",\"role\":\"MANAGER\",\"future\":true}]}"),
                type)
            .stores()
            .getFirst()
            .alias());
    for (String invalid :
        List.of(
            "{}",
            "{\"stores\":[{\"alias\":\"../secret\",\"role\":\"MANAGER\"}]}",
            "{\"stores\":[{\"alias\":\"applications\",\"role\":\"OWNER\"}]}")) {
      assertThrows(IOException.class, () -> SdkJson.decode(json, json.readTree(invalid), type));
    }
  }

  @Test
  void malformed_release_and_missing_status_fields_are_refused() throws Exception {
    var json = SdkJson.mapper();
    assertThrows(
        IOException.class,
        () ->
            SdkJson.decode(
                json, json.readTree("{\"project\":\"app\",\"releases\":[]}"), Status.class));
    assertThrows(
        IOException.class,
        () ->
            SdkJson.decode(
                json,
                json.readTree(
                    "{\"revision\":\"22222222-2222-2222-2222-222222222222\",\"digest\":\"bad\",\"fileCount\":1}"),
                Release.class));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Deploy(
                "app",
                UUID.randomUUID(),
                null,
                new FileStoreReference("applications", ""),
                List.of(),
                List.of(new File("plowshare.json", "{}"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Deploy(
                "app",
                UUID.randomUUID(),
                null,
                new FileStoreReference("applications", "app"),
                List.of(),
                List.of(
                    new File("plowshare.json", "{}"),
                    new File(".plowshare/agents/worker.md", "hidden definitions"))));
  }
}
