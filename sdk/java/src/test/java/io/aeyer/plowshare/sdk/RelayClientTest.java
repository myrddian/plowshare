package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.RelayControl;
import io.aeyer.plowshare.protocol.RelayLog;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class RelayClientTest {
  private static final String TOPIC =
      """
      {"name":"schedule.due","kind":"SCHEDULE_DUE","retentionSeconds":"345600",
       "maxRecords":null,"through":"9007199254740993","expiredThrough":"9007199254740992"}
      """;

  @Test
  void strict_reply_decoding_preserves_positions_and_refuses_corrupted_semantics()
      throws Exception {
    var json = SdkJson.mapper();
    String page =
        """
        {"scope":{"project":"project","system":false},"topic":%s,
         "after":"0","next":"9007199254740992","gapThrough":"9007199254740992",
         "events":[],"subscribers":[],"branches":[]}
        """
            .formatted(TOPIC);
    assertEquals(
        "9007199254740992", SdkJson.decode(json, json.readTree(page), RelayLog.Page.class).next());
    for (String invalid :
        List.of(
            page.replace("\"next\":\"9007199254740992\"", "\"next\":1"),
            page.replace("\"gapThrough\":\"9007199254740992\"", "\"gapThrough\":null"),
            page.replace(
                "\"expiredThrough\":\"9007199254740992\"",
                "\"expiredThrough\":\"9223372036854775808\""),
            page.replace("\"system\":false", "\"system\":true")))
      assertThrows(
          IOException.class,
          () -> SdkJson.decode(json, json.readTree(invalid), RelayLog.Page.class));
  }

  @Test
  void foreign_processing_reply_is_refused_after_exactly_one_submission() throws Exception {
    var received = new AtomicInteger();
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      received.incrementAndGet();
                      try {
                        String id = SdkJson.mapper().readTree(text).path("id").textValue();
                        socket.send(
                            """
                {"id":"%s","type":"relay.process","protocol_version":"plowshare-v1",
                 "payload":{"code":"OK","payload":{"project":"foreign","admitted":1,"dispatched":1,"gaps":[]}}}
                """
                                .formatted(id));
                      } catch (IOException error) {
                        throw new AssertionError(error);
                      }
                    }
                  }));
      try (var connection =
          LegacyFixture.connect(
              server.url("/").toString(), "fixture", Duration.ofSeconds(3), null)) {
        assertThrows(
            IOException.class,
            () -> new RelayClient(connection).process(new RelayLog.Process("project", 1)));
        assertEquals(1, received.get());
      }
    }
  }

  @Test
  void foreign_control_outcome_is_refused_without_replaying_the_mutation() throws Exception {
    String requestId = "26e48bfd-0664-47ab-b3a1-88984c02e8fa";
    var request =
        new RelayControl.Request(
            requestId,
            "project",
            "event",
            requestId,
            RelayControl.Action.ACKNOWLEDGE_GAP,
            "relay.notices.release",
            requestId,
            null,
            null,
            "9007199254740993",
            null,
            "Acknowledge expired work");
    var received = new AtomicInteger();
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      received.incrementAndGet();
                      try {
                        String id = SdkJson.mapper().readTree(text).path("id").textValue();
                        socket.send(
                            """
                {"id":"%s","type":"relay.operate","protocol_version":"plowshare-v1",
                  "payload":{"code":"OK","payload":{"requestId":"11111111-1111-1111-1111-111111111111",
                  "project":"project","topic":"event","action":"ACKNOWLEDGE_GAP","subscriber":"relay.notices.release",
                  "deliveryId":null,"status":"GAP_ACKNOWLEDGED","seenThrough":"9007199254740993","completedAt":"2026-10-05T01:00:00Z"}}}
                """
                                .formatted(id));
                      } catch (IOException error) {
                        throw new AssertionError(error);
                      }
                    }
                  }));
      try (var connection =
          LegacyFixture.connect(
              server.url("/").toString(), "fixture", Duration.ofSeconds(3), null)) {
        assertThrows(IOException.class, () -> new RelayClient(connection).operate(request));
        assertEquals(1, received.get());
      }
    }
  }
}
