package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/** Invalid inputs are refused before a connection or mutation can be submitted. */
class WsRequestValidationTest {
  @Test
  void malformed_requests_never_open_a_socket_or_upload() throws Exception {
    try (var server = new MockWebServer();
        var client =
            new WsServerClient(server.url("/").toString(), null, Plowshare.TransportMode.LEGACY)) {
      var invalid =
          List.<org.junit.jupiter.api.function.Executable>of(
              () -> client.recall(" ", "question", 1),
              () -> client.recall(null, "question", 0),
              () -> client.read("id\nforged"),
              () -> client.index(" project "),
              () -> client.search("query", 0, 10, 1),
              () -> client.search("query", 10, 0, 1),
              () -> client.search("query", 10, 10, 0),
              () -> client.fetch("https://user:secret@example.com", 0),
              () -> client.fetch("file:///etc/hosts", 0),
              () -> client.fetch("https://example.com", -1),
              () -> client.searchDocuments("query", 0),
              () -> client.retrieve("query", "doc\u2028forged", 1),
              () -> client.documentStance("doc", ""),
              () -> client.listDocuments(null, 1, -1),
              () -> client.run("agent", "", null, null, null),
              () -> client.openConversation(null, 0),
              () -> client.chat("conversation", 0, 0),
              () -> client.trajectory("conversation", -1, 1),
              () -> client.context("", null),
              () -> client.askDocument("doc", "question", -1),
              () -> client.job(""),
              () -> client.cancelJob("job\0injected"),
              () -> client.defineProject("project", "/path\nforged", List.of()),
              () -> client.lendProject("project", List.of()),
              () -> client.unlendProject("project", List.of(" ")),
              () -> client.moveProject("project", " destination "),
              () -> client.resolve("", true, null, null),
              () -> client.uploadImage(null, "filename\r\nheader", new byte[] {1}),
              () -> client.uploadImage(null, "image", new byte[0]));
      for (var call : invalid) assertThrows(IllegalArgumentException.class, call);
      assertThrows(NullPointerException.class, () -> client.information(null));
      assertThrows(NullPointerException.class, () -> client.write(null, null));
      assertEquals(0, server.getRequestCount());
    }
  }
}
