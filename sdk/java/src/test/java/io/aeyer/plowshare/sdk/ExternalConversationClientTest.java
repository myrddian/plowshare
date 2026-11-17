package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual public SDK calls over an isolated socket; no server/model/database is needed. */
class ExternalConversationClientTest {
  private static final ObjectMapper JSON = SdkJson.mapper();
  private static final ConversationOpenRequestEx OPEN =
      new ConversationOpenRequestEx("project", "sdk.instance", "Case/opaque:ID-1");
  private static final AgentRunRequestEx RUN =
      new AgentRunRequestEx("project", "sdk.instance", "Case/opaque:ID-1", "worker", "work");

  private static ObjectNode conversation() throws IOException {
    return (ObjectNode)
        JSON.readTree(
            """
            {"id":"cnv_external","project":"project","maxModelCalls":0,
             "modelCallsSpent":0,"maxTurns":null,"noTurnCap":false,"noBudget":false,
             "title":null,"future":"discard"}
            """);
  }

  private static ObjectNode started() throws IOException {
    return (ObjectNode)
        JSON.readTree(
            """
            {"id":"job_external","agent":"worker","conversation":"cnv_external",
             "revision":"undeclared-future-field","future":"discard"}
            """);
  }

  private static JsonNode answer(String code, JsonNode payload) {
    var response = JSON.createObjectNode().put("code", code);
    if (payload != null) response.set("payload", payload);
    return response;
  }

  @Test
  void typedOpenAndRunEachSubmitOneFrameWithExactLocatorsAndNoResolveRpc() throws Exception {
    var view = conversation();
    var job = started();
    try (var fixture =
            new Fixture(
                frame ->
                    frame.path("type").asText().equals("conversation.open.ex")
                        ? answer("OK", view)
                        : answer("ACCEPTED", job));
        var connection = fixture.connect()) {
      var client = new ExternalConversationClient(connection);
      var opened = client.open(OPEN);
      assertEquals(Long.valueOf(0), opened.maxModelCalls());
      assertNull(opened.maxTurns());
      assertFalse(opened.noBudget());
      var request =
          new AgentRunRequestEx(
              "project",
              "sdk.instance",
              "Case/opaque:ID-1",
              "worker",
              "  original\ntext  ",
              new AgentRunRequestEx.Session(null),
              23,
              3,
              null,
              List.of());
      var accepted = client.run(request);
      assertEquals("job_external", accepted.id());
      assertEquals("cnv_external", accepted.conversation());
      assertEquals(2, fixture.frames.size());
      assertEquals("conversation.open.ex", fixture.frames.get(0).path("type").asText());
      assertEquals(JSON.valueToTree(OPEN), fixture.frames.get(0).get("payload"));
      assertEquals("agent.run.ex", fixture.frames.get(1).path("type").asText());
      assertEquals(JSON.valueToTree(request), fixture.frames.get(1).get("payload"));
      assertEquals(
          "  original\ntext  ", fixture.frames.get(1).path("payload").path("task").asText());
      assertTrue(fixture.frames.get(1).path("payload").get("session").isNull());
      assertFalse(JSON.valueToTree(accepted).has("revision"));
    }
  }

  @Test
  void requestValuesPreserveOmissionExplicitSessionNullAndNamedSession() {
    var absent = JSON.valueToTree(RUN);
    assertFalse(absent.has("session"));
    for (String field : List.of("maxModelCalls", "maxTurns", "noTurnCap", "images"))
      assertFalse(absent.has(field));
    var explicit =
        new AgentRunRequestEx(
            RUN.project(),
            RUN.source(),
            RUN.externalConversation(),
            RUN.agent(),
            RUN.task(),
            new AgentRunRequestEx.Session(null),
            null,
            null,
            false,
            List.of());
    var encoded = JSON.valueToTree(explicit);
    assertTrue(encoded.get("session").isNull());
    assertTrue(encoded.get("images").isArray());
    assertFalse(encoded.get("noTurnCap").booleanValue());
    var named = new AgentRunRequestEx.Session("workspace-session");
    assertEquals("workspace-session", JSON.valueToTree(named).textValue());
    assertEquals(" padded ", new AgentRunRequestEx.Session(" padded ").value());
    assertThrows(IllegalArgumentException.class, () -> new AgentRunRequestEx.Session("\ufeff"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "id",
        "project",
        "maxModelCalls",
        "modelCallsSpent",
        "maxTurns",
        "noTurnCap",
        "noBudget",
        "title"
      })
  void openRequiresEveryCanonicalFieldRatherThanInventingDefaults(String missing) throws Exception {
    var payload = conversation();
    payload.remove(missing);
    try (var fixture = new Fixture(frame -> answer("OK", payload));
        var connection = fixture.connect()) {
      var failure =
          assertThrows(
              Plowshare.TransportException.class,
              () -> new ExternalConversationClient(connection).open(OPEN));
      assertEquals(Plowshare.Delivery.INVALID_RESPONSE, failure.delivery());
      assertEquals(1, fixture.frames.size());
    }
  }

  @Test
  void explicitNullableOpenFieldsAndAgentOmittedRunRemainValidWithoutFutureFieldExposure()
      throws Exception {
    var view = conversation();
    for (String field : List.of("maxModelCalls", "modelCallsSpent", "maxTurns", "title"))
      view.putNull(field);
    var job = started();
    job.remove("agent");
    try (var fixture =
            new Fixture(
                frame ->
                    frame.path("type").asText().equals("conversation.open.ex")
                        ? answer("OK", view)
                        : answer("ACCEPTED", job));
        var connection = fixture.connect()) {
      var client = new ExternalConversationClient(connection);
      assertNull(client.open(OPEN).maxModelCalls());
      var accepted = client.run(RUN);
      assertNull(accepted.agent());
      assertEquals("cnv_external", accepted.conversation());
      assertFalse(JSON.valueToTree(accepted).has("revision"));
      assertFalse(JSON.valueToTree(accepted).has("future"));
      assertEquals(2, fixture.frames.size());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "null", "wrong-prefix", "wrong-type"})
  void incompleteOrForeignRunConversationIsInvalidWithoutReplay(String variant) throws Exception {
    var job = started();
    switch (variant) {
      case "missing" -> job.remove("conversation");
      case "null" -> job.putNull("conversation");
      case "wrong-prefix" -> job.put("conversation", "job_other");
      case "wrong-type" -> job.put("conversation", 2);
      default -> throw new IllegalArgumentException("Unknown fixture variant");
    }
    try (var fixture = new Fixture(frame -> answer("ACCEPTED", job));
        var connection = fixture.connect()) {
      var failure =
          assertThrows(
              Plowshare.TransportException.class,
              () -> new ExternalConversationClient(connection).run(RUN));
      assertEquals(Plowshare.Delivery.INVALID_RESPONSE, failure.delivery());
      assertEquals(1, fixture.frames.size());
    }
  }

  @Test
  void wrongSuccessStatusesAreInvalidWhileTrueRefusalsStayRefusals() throws Exception {
    for (boolean open : List.of(true, false)) {
      var payload = open ? conversation() : started();
      try (var fixture = new Fixture(frame -> answer(open ? "CREATED" : "OK", payload));
          var connection = fixture.connect()) {
        var client = new ExternalConversationClient(connection);
        var failure =
            assertThrows(
                Plowshare.TransportException.class,
                () -> {
                  if (open) client.open(OPEN);
                  else client.run(RUN);
                });
        assertEquals(Plowshare.Delivery.INVALID_RESPONSE, failure.delivery());
        assertEquals(1, fixture.frames.size());
      }
    }
    try (var fixture =
            new Fixture(
                frame -> answer("NOT_FOUND", JSON.createObjectNode().put("secret", "hidden")));
        var connection = fixture.connect()) {
      var failure =
          assertThrows(
              IOException.class, () -> new ExternalConversationClient(connection).run(RUN));
      assertTrue(failure.getMessage().contains("NOT_FOUND"));
      assertFalse(failure.getMessage().contains("hidden"));
      assertFalse(failure instanceof Plowshare.TransportException);
      assertEquals(1, fixture.frames.size());
    }
  }

  @Test
  void canonicalProjectReplyMayBeNullOrRenamedButPresentAgentCannotBeNull() throws Exception {
    for (String project : new String[] {null, "renamed-project"}) {
      var view = conversation();
      if (project == null) view.putNull("project");
      else view.put("project", project);
      try (var fixture = new Fixture(frame -> answer("OK", view));
          var connection = fixture.connect()) {
        assertEquals(project, new ExternalConversationClient(connection).open(OPEN).project());
      }
    }
    var job = started();
    job.putNull("agent");
    try (var fixture = new Fixture(frame -> answer("ACCEPTED", job));
        var connection = fixture.connect()) {
      assertEquals(
          Plowshare.Delivery.INVALID_RESPONSE,
          assertThrows(
                  Plowshare.TransportException.class,
                  () -> new ExternalConversationClient(connection).run(RUN))
              .delivery());
      assertEquals(1, fixture.frames.size());
    }
  }

  @Test
  void openPreservesCanonicalLongTitleExactTextAndSafeIntegerCounts() throws Exception {
    var view = conversation();
    view.put("id", " cnv_external ").put("project", " renamed-project ");
    view.put("title", "x".repeat(32769));
    view.put("maxModelCalls", 2_147_483_648L);
    view.put("modelCallsSpent", new BigDecimal("1.0"));
    view.put("maxTurns", 9_007_199_254_740_991L);
    try (var fixture = new Fixture(frame -> answer("OK", view));
        var connection = fixture.connect()) {
      var result = new ExternalConversationClient(connection).open(OPEN);
      assertEquals(" cnv_external ", result.id());
      assertEquals(" renamed-project ", result.project());
      assertEquals(32769, result.title().length());
      assertEquals(Long.valueOf(2_147_483_648L), result.maxModelCalls());
      assertEquals(Long.valueOf(1), result.modelCallsSpent());
      assertEquals(Long.valueOf(9_007_199_254_740_991L), result.maxTurns());
      assertFalse(JSON.valueToTree(result).has("future"));
    }
  }

  @Test
  void timeoutDisconnectAndCancellationAfterSendStayUnknownWithoutReplacement() throws Exception {
    for (String mode : List.of("timeout", "disconnect", "cancel")) {
      try (var fixture = new Fixture(frame -> null);
          var connection = fixture.connect()) {
        fixture.disconnect = mode.equals("disconnect");
        var cancellation = new RequestCancellation();
        var stage =
            new ExternalConversationClient(connection)
                .runAsync(RUN, new RequestOptions(Duration.ofMillis(200), cancellation));
        assertTrue(fixture.submitted.await(2, TimeUnit.SECONDS));
        if (mode.equals("cancel")) cancellation.cancel();
        var failure =
            assertThrows(Plowshare.TransportException.class, () -> Plowshare.await(stage));
        assertEquals(Plowshare.Delivery.UNKNOWN, failure.delivery());
        assertEquals(1, fixture.frames.size());
      }
    }
  }

  @Test
  void preCancelledRunIsNotSubmitted() throws Exception {
    try (var fixture = new Fixture(frame -> null);
        var connection = fixture.connect()) {
      var cancellation = new RequestCancellation();
      cancellation.cancel();
      var stage =
          new ExternalConversationClient(connection)
              .runAsync(RUN, new RequestOptions(Duration.ofSeconds(2), cancellation));
      var failure = assertThrows(Plowshare.TransportException.class, () -> Plowshare.await(stage));
      assertEquals(Plowshare.Delivery.NOT_SUBMITTED, failure.delivery());
      assertTrue(fixture.frames.isEmpty());
    }
  }

  @Test
  void malformedSelectorsAndLimitsCannotProduceAnExecutableRequest() {
    for (String source : List.of("", "UPPER", "has space", "a..b", "a".repeat(129), "é"))
      assertThrows(
          IllegalArgumentException.class,
          () -> new ConversationOpenRequestEx("project", source, "external"));
    for (String external : List.of("", "has space", "é", "\u007f", "x".repeat(257)))
      assertThrows(
          IllegalArgumentException.class,
          () -> new ConversationOpenRequestEx("project", "sdk", external));
    for (String project :
        List.of("", " padded ", "\u00a0p", "p\ufeff", "bad\ncontrol", "x".repeat(1025)))
      assertThrows(
          IllegalArgumentException.class,
          () -> new ConversationOpenRequestEx(project, "sdk", "external"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConversationOpenRequestEx("project", "sdk", "external", 0, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConversationOpenRequestEx("project", "sdk", "external", 2, null, null, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConversationOpenRequestEx("project", "sdk", "external", null, 2, true, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentRunRequestEx("project", "sdk", "external", "worker", " "));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentRunRequestEx(
                "project",
                "sdk",
                "external",
                "worker",
                "work",
                null,
                null,
                null,
                null,
                List.of("image")));
    assertNotNull(
        new ConversationOpenRequestEx("p".repeat(1024), "a".repeat(128), "x".repeat(256)));
    for (String task : List.of("x".repeat(32769), "\u0085", "\u001c", "\0", "  original\ntext  "))
      assertEquals(
          task, new AgentRunRequestEx("project", "sdk", "external", "worker", task).task());
    for (String value :
        List.of(" padded ", "\ufeffworker", "\u0085", "\u001c", "\0", "x".repeat(32769))) {
      assertEquals(
          value, new AgentRunRequestEx("project", "sdk", "external", value, "work").agent());
      assertEquals(value, new AgentRunRequestEx.Session(value).value());
    }
    for (String task : List.of("", "\ufeff", "\u00a0", " \u2003\n"))
      assertThrows(
          IllegalArgumentException.class,
          () -> new AgentRunRequestEx("project", "sdk", "external", "worker", task));
  }

  private static final class Fixture extends WebSocketListener implements AutoCloseable {
    private final MockWebServer server = new MockWebServer();
    private final Function<JsonNode, JsonNode> response;
    private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
    private final CountDownLatch submitted = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile boolean disconnect;

    private Fixture(Function<JsonNode, JsonNode> response) {
      this.response = response;
      server.enqueue(new MockResponse().withWebSocketUpgrade(this));
    }

    private Plowshare connect() throws IOException {
      return LegacyFixture.connect(
          server.url("/").toString(), "fixture", Duration.ofSeconds(2), null);
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
      try {
        var frame = JSON.readTree(text);
        frames.add(frame);
        submitted.countDown();
        if (disconnect) {
          // MockWebServer's accepted socket has no client Call to cancel. A peer close after the
          // observed request is a supported disconnect and leaves its outcome unknown.
          socket.close(1001, "fixture disconnect");
          return;
        }
        var payload = response.apply(frame);
        if (payload == null) return;
        var envelope =
            JSON.createObjectNode()
                .put("type", frame.path("type").asText())
                .put("protocol_version", "plowshare-v1");
        envelope.set("id", frame.get("id"));
        envelope.set("payload", payload);
        socket.send(envelope.toString());
      } catch (Throwable thrown) {
        failure.set(thrown);
        socket.close(1011, "fixture failed");
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
      if (failure.get() != null) throw new AssertionError("Fixture failed", failure.get());
    }
  }
}
