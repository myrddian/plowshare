package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/** Invalid inputs are refused before a connection or mutation can be submitted. */
class WsRequestValidationTest {
  @Test
  void image_receipts_must_match_the_explicit_project_without_replay() throws Exception {
    for (String returned : List.of("selected", "foreign")) {
      try (var server = new MockWebServer();
          var client = new WsServerClient(server.url("/").toString(), null)) {
        server.enqueue(
            new MockResponse()
                .setBody("{\"id\":\"image\",\"project\":\"" + returned + "\",\"format\":\"png\"}"));
        if (returned.equals("selected"))
          assertEquals(
              "selected", client.uploadImage("selected", "image.png", new byte[] {1}).project());
        else
          assertEquals(
              Plowshare.Delivery.INVALID_RESPONSE,
              assertThrows(
                      Plowshare.TransportException.class,
                      () -> client.uploadImage("selected", "image.png", new byte[] {1}))
                  .delivery());
        assertEquals(1, server.getRequestCount());
      }
    }
  }

  @Test
  void an_existing_conversation_is_an_explicit_scope() {
    var request = new AgentClient.Run("worker", "work", null, null, "cnv_existing", false);
    assertNull(request.project());
    assertEquals("cnv_existing", request.conversation());
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentClient.Run("worker", "work", null, null, "cnv_existing", true));
  }

  @Test
  void malformed_requests_never_open_a_socket_or_upload() throws Exception {
    try (var server = new MockWebServer();
        var client =
            new WsServerClient(
                server.url("/").toString(),
                null,
                Plowshare.TransportMode.LEGACY,
                Plowshare.DispatchMode.ORDERED)) {
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
              () -> client.openConversation(null, 10),
              () -> client.openConversation(" project ", 10),
              () -> client.uploadImage(" project ", "image", new byte[] {1}),
              () -> client.conversations(" project "),
              () -> client.run("agent", "work", null, null, null),
              () -> client.run("agent", "work", "project", null, "conversation"),
              () -> client.uploadImage(null, "image", new byte[] {1}),
              () -> new AgentClient.Run("agent", "work", null, null, null, true),
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
