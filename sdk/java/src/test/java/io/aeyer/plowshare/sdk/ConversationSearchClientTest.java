package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class ConversationSearchClientTest {
  @Test
  void selectedSemanticSearchUsesWebSocketAndRetainsSnapshotMetadata() throws Exception {
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      try {
                        var frame = SdkJson.mapper().readTree(text);
                        assertEquals("conversation.search", frame.path("type").asText());
                        assertEquals(
                            "cnv_selected", frame.path("payload").path("conversation").asText());
                        assertEquals("semantic", frame.path("payload").path("mode").asText());
                        assertEquals(true, frame.path("payload").path("includeInputs").asBoolean());
                        socket.send(
                            "{\"id\":\""
                                + frame.path("id").asText()
                                + "\",\"type\":\"conversation.search\",\"protocol_version\":\"plowshare-v1\",\"payload\":{\"code\":\"OK\",\"payload\":{"
                                + "\"hits\":[],\"total\":0,\"offset\":0,\"limit\":10,\"reach\":{\"searched\":0,\"ejected\":0,\"recordedOnly\":0},"
                                + "\"retrieval\":{\"requestedMode\":\"semantic\",\"effectiveMode\":\"semantic\",\"totalMeaning\":\"groups\",\"snapshot\":\"snapshot\","
                                + "\"truncated\":false,\"complete\":true,\"fallback\":null,\"coverage\":null,\"generation\":null,\"queryEmbeddingCalls\":1,\"provenance\":\"fixture\"}}}}");
                      } catch (IOException failed) {
                        throw new AssertionError(failed);
                      }
                    }
                  }));
      try (var client =
          new WsServerClient(
              server.url("/").toString(),
              null,
              Plowshare.TransportMode.LEGACY,
              Plowshare.DispatchMode.ORDERED)) {
        var result =
            client
                .searchEntriesAsync(
                    new ServerClient.LogQuery(
                        "project", "question", "cnv_selected", "semantic", null, 0, 10, true),
                    RequestOptions.defaults(Duration.ofSeconds(2)))
                .toCompletableFuture()
                .get(3, TimeUnit.SECONDS);
        assertEquals("snapshot", result.retrieval().snapshot());
        assertEquals(1, result.retrieval().queryEmbeddingCalls());
      }
    }
  }

  @Test
  void suppliedContextCannotExceedItsSourceOrUnderstateItsRetainedReferences() throws Exception {
    var json = SdkJson.mapper();
    String valid =
        """
        {"conversationId":"cnv_a","ordinal":1,"turnOrdinal":1,"kind":"utterance",
        "handle":null,"recordedAt":null,"sourceRevision":"hash","length":20,"supersededBy":null,
        "inputProvenance":{"start":5,"end":20,"sourceCount":100,
        "sources":[{"id":"evidence-1","revision":"revision-1"}]}}
        """;
    var source = SdkJson.decode(json, json.readTree(valid), ServerClient.LogSource.class);
    assertEquals(20, source.inputProvenance().end());
    for (String invalid :
        List.of(
            valid.replace("\"end\":20", "\"end\":21"),
            valid.replace("\"sourceCount\":100", "\"sourceCount\":0"))) {
      assertThrows(
          IOException.class,
          () -> SdkJson.decode(json, json.readTree(invalid), ServerClient.LogSource.class));
    }
  }

  @Test
  void groupedReferencesAreTypedAndInvalidCoordinatesAreRefused() throws Exception {
    var json = SdkJson.mapper();
    String valid =
        """
        {"conversationId":"cnv_a","ordinal":1,"turnOrdinal":1,"kind":"utterance","rank":1,
        "snippet":"passage","length":20,"supersededBy":null,"handle":null,"recordedAt":null,
        "sourceCount":1,"sources":[{"conversationId":"cnv_a","ordinal":1,"turnOrdinal":1,
        "kind":"utterance","handle":null,"recordedAt":null,"sourceRevision":"hash","length":20,"supersededBy":null}]}
        """;
    var hit = SdkJson.decode(json, json.readTree(valid), ServerClient.LogHit.class);
    assertEquals("cnv_a", hit.sources().getFirst().conversationId());
    String invalid = valid.replace("\"ordinal\":1", "\"ordinal\":0");
    assertThrows(
        IOException.class,
        () -> SdkJson.decode(json, json.readTree(invalid), ServerClient.LogHit.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ServerClient.LogQuery("project", "q", " padded ", "semantic", null, 0, 10));
  }
}
