package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ScheduleClientTest {
  @Test
  void explicit_pages_preserve_the_exclusive_key_limit_and_immutable_typed_results()
      throws Exception {
    try (var fixture = new Fixture((frame, count) -> ok(schedules(1, 1)))) {
      var page = fixture.client.listPage("selected", "schedule-00000", 1);
      assertEquals("schedule-00001", page.getFirst().name());
      assertThrows(UnsupportedOperationException.class, page::clear);
      assertEquals(
          SdkJson.mapper()
              .readTree("{\"project\":\"selected\",\"after\":\"schedule-00000\",\"limit\":1}"),
          fixture.requests.element().path("payload"));
    }
    try (var fixture = new Fixture((frame, count) -> ok(files(1, 2)))) {
      var page = fixture.client.filesPage("selected", "runtime-00000", 2);
      assertEquals(
          List.of("runtime-00001", "runtime-00002"),
          page.stream().map(io.aeyer.plowshare.protocol.ScheduledWork.File::internalName).toList());
      assertEquals("same-display-name", page.getFirst().name());
      assertEquals("refused", page.getFirst().status());
    }
  }

  @Test
  void omitted_page_controls_use_fifty_and_invalid_inputs_do_not_submit_requests()
      throws Exception {
    try (var fixture = new Fixture((frame, count) -> ok(schedules(0, 1)))) {
      fixture.client.listPage("selected", null, null);
      assertEquals(
          SdkJson.mapper().readTree("{\"project\":\"selected\"}"),
          fixture.requests.element().path("payload"));
      for (String cursor :
          List.of("", " ", " padded", "padded ", "a\n", "a\u007f", "a\u0085", "x".repeat(513)))
        assertThrows(
            IllegalArgumentException.class, () -> fixture.client.listPage("selected", cursor, 50));
      for (int limit : List.of(0, -1, 101))
        assertThrows(
            IllegalArgumentException.class,
            () -> fixture.client.filesPage("selected", null, limit));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.client.listPage("Personal:fixture", null, 50));
      assertEquals(1, fixture.requests.size());
    }
    try (var fixture = new Fixture((frame, count) -> ok(schedules(0, 51)))) {
      assertThrows(IOException.class, () -> fixture.client.listPage("selected", null, null));
      assertEquals(1, fixture.requests.size());
    }
  }

  @Test
  void complete_read_conveniences_deliberately_page_using_the_owning_key() throws Exception {
    try (var fixture =
        new Fixture((frame, count) -> ok(schedules(count == 1 ? 0 : 100, count == 1 ? 100 : 1)))) {
      assertEquals(101, fixture.client.list("selected").size());
      var requests = List.copyOf(fixture.requests);
      assertEquals(2, requests.size());
      assertEquals(100, requests.getFirst().path("payload").path("limit").intValue());
      assertEquals("schedule-00099", requests.getLast().path("payload").path("after").textValue());
    }
    try (var fixture =
        new Fixture((frame, count) -> ok(files(count == 1 ? 0 : 100, count == 1 ? 100 : 1)))) {
      assertEquals(101, fixture.client.files("selected").size());
      assertEquals(
          "runtime-00099",
          List.copyOf(fixture.requests).getLast().path("payload").path("after").textValue());
    }
  }

  @Test
  void repeated_cursor_duplicate_key_and_null_rows_fail_without_another_read() throws Exception {
    var duplicate = files(0, 1);
    duplicate.add(duplicate.get(0).deepCopy());
    var nullRow = SdkJson.mapper().createArrayNode().addNull();
    var missingKey = files(0, 1);
    ((com.fasterxml.jackson.databind.node.ObjectNode) missingKey.get(0)).remove("internalName");
    for (JsonNode rows : List.of(duplicate, nullRow, missingKey)) {
      try (var fixture = new Fixture((frame, count) -> ok(rows))) {
        assertThrows(IOException.class, () -> fixture.client.filesPage("selected", null, 2));
        assertEquals(1, fixture.requests.size());
      }
    }
    try (var fixture = new Fixture((frame, count) -> ok(schedules(0, 1)))) {
      assertThrows(
          IOException.class, () -> fixture.client.listPage("selected", "schedule-00000", 1));
      assertEquals(1, fixture.requests.size());
    }
  }

  @Test
  void traversal_refusal_or_nonprogress_never_returns_a_partial_collection_or_replays()
      throws Exception {
    for (boolean refused : List.of(false, true)) {
      try (var fixture =
          new Fixture(
              (frame, count) ->
                  count == 1
                      ? ok(schedules(0, 100))
                      : refused
                          ? SdkJson.mapper()
                              .createObjectNode()
                              .put("code", "FORBIDDEN")
                              .put("said", "scope changed")
                          : ok(schedules(0, 1)))) {
        assertThrows(IOException.class, () -> fixture.client.list("selected"));
        assertEquals(2, fixture.requests.size());
        assertTrue(
            fixture.requests.stream()
                .allMatch(frame -> frame.path("type").textValue().equals("schedule.list")));
      }
    }
  }

  @Test
  void complete_read_cap_is_explicit_and_exactly_ten_thousand_can_finish() throws Exception {
    for (boolean exceeds : List.of(false, true)) {
      try (var fixture =
          new Fixture(
              (frame, count) ->
                  ok(schedules((count - 1) * 100, count <= 100 ? 100 : exceeds ? 1 : 0)))) {
        // This case measures the complete-read cap, not the traversal deadline. One absolute
        // budget now covers all 101 pages rather than restarting the fixture's two-second timer.
        var options = RequestOptions.defaults(Duration.ofSeconds(10));
        if (exceeds) {
          var failure =
              assertThrows(
                  IOException.class,
                  () -> Plowshare.await(fixture.client.listAsync("selected", options)));
          assertTrue(failure.getMessage().contains("10,000"));
        } else
          assertEquals(
              10000, Plowshare.await(fixture.client.listAsync("selected", options)).size());
        assertEquals(101, fixture.requests.size());
      }
    }
  }

  private static ArrayNode schedules(int first, int count) {
    var rows = SdkJson.mapper().createArrayNode();
    for (int index = first; index < first + count; index++)
      rows.addObject()
          .put("name", "schedule-%05d".formatted(index))
          .put("cron", "0 0 9 * * *")
          .put("zone", "UTC")
          .put("emits", "topic")
          .put("paused", false)
          .putNull("nextFireAt")
          .put("definedBy", "fixture")
          .put("futureField", "ignored");
    return rows;
  }

  private static ArrayNode files(int first, int count) {
    var rows = SdkJson.mapper().createArrayNode();
    for (int index = first; index < first + count; index++)
      rows.addObject()
          .put("name", "same-display-name")
          .put("project", "selected")
          .put("source", "server")
          .put("path", "schedules/same-display-name.json")
          .put("internalName", "runtime-%05d".formatted(index))
          .putNull("definition")
          .put("status", "refused")
          .put("error", "invalid source definition");
    return rows;
  }

  private static JsonNode ok(JsonNode rows) {
    return SdkJson.mapper().createObjectNode().put("code", "OK").set("payload", rows);
  }

  private static final class Fixture implements AutoCloseable {
    private final MockWebServer server = new MockWebServer();
    private final Plowshare connection;
    private final ScheduleClient client;
    private final ConcurrentLinkedQueue<JsonNode> requests = new ConcurrentLinkedQueue<>();

    Fixture(BiFunction<JsonNode, Integer, JsonNode> script) throws IOException {
      var sequence = new AtomicInteger();
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      try {
                        var json = SdkJson.mapper();
                        var frame = json.readTree(text);
                        requests.add(frame.deepCopy());
                        var reply =
                            json.createObjectNode()
                                .put("id", frame.path("id").textValue())
                                .put("type", frame.path("type").textValue())
                                .put("protocol_version", "plowshare-v1");
                        reply.set("payload", script.apply(frame, sequence.incrementAndGet()));
                        socket.send(json.writeValueAsString(reply));
                      } catch (IOException failure) {
                        throw new AssertionError(failure);
                      }
                    }
                  }));
      connection =
          LegacyFixture.connect(
              server.url("/").toString(), "fixture", Duration.ofSeconds(2), push -> {});
      client = new ScheduleClient(connection);
    }

    @Override
    public void close() throws IOException {
      connection.close();
      server.close();
    }
  }
}
