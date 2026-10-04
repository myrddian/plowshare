package io.aeyer.plowshare;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.aeyer.plowshare.a2a.*;
import io.aeyer.plowshare.protocol.Outgoing;
import io.aeyer.plowshare.sdk.*;
import io.aeyer.plowshare.server.PlowshareServerApplication;
import io.aeyer.plowshare.server.auth.TokenStore;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real authenticated WS + SDK + A2A fixture peer + migrated Postgres, with no inference. */
@Testcontainers
@SpringBootTest(
    classes = {PlowshareServerApplication.class, EndToEndTest.StubbedEmbeddings.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutgoingEndToEndTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static final ObjectMapper JSON = new ObjectMapper();
  static final Path DATA = data();
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired TokenStore tokens;

  private static Path data() {
    try {
      return new io.aeyer.plowshare.server.data.DataLayout(
              Files.createTempDirectory("plowshare-outgoing-test-"))
          .initialise()
          .root();
    } catch (Exception failed) {
      throw new IllegalStateException(failed);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("plowshare.data.dir", DATA::toString);
    registry.add("LLM_BASE_URL", () -> "http://127.0.0.1:1/v1");
    registry.add("SPARK_BASE_URL", () -> "http://127.0.0.1:1/v1");
  }

  private Plowshare sdk(String account) throws Exception {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES(?,'fixture') ON CONFLICT DO NOTHING",
        account);
    return Plowshare.connect(
        "http://127.0.0.1:" + port,
        tokens.issuePair(account, false).access(),
        Duration.ofSeconds(5),
        null);
  }

  private static Outgoing.Send send() {
    return new Outgoing.Send(UUID.randomUUID(), "fixture", nullMessage(), null, null);
  }

  private static Map<String, Object> nullMessage() {
    return Map.of("parts", List.of(Map.of("text", "Do opaque remote work")));
  }

  private static Map<String, Object> card(MockWebServer peer) {
    return Map.of(
        "name",
        "Fixture research",
        "description",
        "Research external evidence",
        "version",
        "1.0.0",
        "supportedInterfaces",
        List.of(
            Map.of(
                "url",
                peer.url("/rpc").toString(),
                "protocolBinding",
                "JSONRPC",
                "protocolVersion",
                "1.0")),
        "capabilities",
        Map.of(),
        "defaultInputModes",
        List.of("text/plain"),
        "defaultOutputModes",
        List.of("text/plain"),
        "skills",
        List.of(
            Map.of(
                "id",
                "research",
                "name",
                "Research",
                "description",
                "Research evidence",
                "tags",
                List.of("research"))));
  }

  private void expire(UUID id) {
    jdbc.update("UPDATE outgoing_work SET lease_until=now()-interval '1 second' WHERE id=?", id);
  }

  @BeforeEach
  void reset() {
    jdbc.execute("TRUNCATE outgoing_work,outgoing_peers");
  }

  @Test
  void sends_once_polls_after_adapter_restart_and_preserves_remote_outputs() throws Exception {
    try (var peer = new MockWebServer();
        var sdk = sdk("outgoing-user")) {
      var sent = new java.util.concurrent.atomic.AtomicInteger();
      var polled = new java.util.concurrent.atomic.AtomicInteger();
      peer.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              try {
                if (request.getMethod().equals("GET"))
                  return new MockResponse().setBody(JSON.writeValueAsString(card(peer)));
                JsonNode rpc = JSON.readTree(request.getBody().readUtf8());
                boolean initial = rpc.path("method").asText().equals("SendMessage");
                if (initial) sent.incrementAndGet();
                else polled.incrementAndGet();
                Object task =
                    Map.of(
                        "id",
                        "remote-task",
                        "contextId",
                        "remote-context",
                        "status",
                        Map.of("state", initial ? "TASK_STATE_WORKING" : "TASK_STATE_COMPLETED"),
                        "artifacts",
                        List.of(
                            Map.of(
                                "artifactId",
                                "result",
                                "parts",
                                List.of(Map.of("url", "https://example.invalid/result.txt")))));
                return new MockResponse()
                    .setBody(
                        JSON.writeValueAsString(
                            Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                rpc.get("id"),
                                "result",
                                initial ? Map.of("task", task) : task)));
              } catch (Exception failed) {
                throw new AssertionError(failed);
              }
            }
          });
      var outgoing = new OutgoingClient(sdk);
      outgoing.advertise(null, List.of("fixture"));
      var request = send();
      var receipt = outgoing.send(request);
      assertEquals(receipt.id(), outgoing.send(request).id());
      assertEquals(
          "BAD_REQUEST",
          sdk.request(
                  "outgoing.send",
                  Map.of(
                      "requestId",
                      request.requestId(),
                      "peer",
                      "fixture",
                      "message",
                      Map.of("parts", List.of(Map.of("text", "different")))))
              .code());
      try (var client = new A2aClient(peer.url("/rpc").toString(), null, Duration.ofSeconds(3))) {
        assertTrue(new Adapter(outgoing, null, Map.of("fixture", client)).tick());
        assertEquals("WORKING", outgoing.status(receipt.id()).state());
        assertEquals(
            "Fixture research", outgoing.peers(null).details().getFirst().agentCard().get("name"));
        assertEquals(
            "research",
            JSON.valueToTree(outgoing.peers(null))
                .path("details")
                .get(0)
                .path("agentCard")
                .path("skills")
                .get(0)
                .path("id")
                .asText());
      }
      expire(receipt.id());
      try (var restarted = sdk("outgoing-user");
          var client = new A2aClient(peer.url("/rpc").toString(), null, Duration.ofSeconds(3))) {
        var after = new OutgoingClient(restarted);
        assertTrue(new Adapter(after, null, Map.of("fixture", client)).tick());
        var result = after.status(receipt.id());
        assertEquals("COMPLETED", result.state());
        assertTrue(
            JSON.valueToTree(result.result())
                .path("task")
                .path("artifacts")
                .get(0)
                .path("parts")
                .get(0)
                .has("url"));
        assertFalse(new Adapter(after, null, Map.of("fixture", client)).tick());
      }
      assertEquals(1, sent.get());
      assertEquals(1, polled.get());
    }
  }

  @Test
  void lost_remote_reply_stays_unknown_without_resending_and_queued_cancel_never_dispatches()
      throws Exception {
    try (var peer = new MockWebServer();
        var sdk = sdk("uncertain-user");
        var client = new A2aClient(peer.url("/rpc").toString(), null, Duration.ofSeconds(1))) {
      peer.enqueue(new MockResponse().setBody(JSON.writeValueAsString(card(peer))));
      peer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
      var outgoing = new OutgoingClient(sdk);
      outgoing.advertise(null, List.of("fixture"));
      var receipt = outgoing.send(send());
      var adapter = new Adapter(outgoing, null, Map.of("fixture", client));
      assertTrue(adapter.tick());
      assertEquals("UNKNOWN", outgoing.status(receipt.id()).state());
      expire(receipt.id());
      assertFalse(adapter.tick());
      assertEquals(2, peer.getRequestCount());
      var canceled = outgoing.send(send());
      assertEquals("CANCELED", outgoing.cancel(canceled.id()).state());
      assertFalse(adapter.tick());
      assertEquals(2, peer.getRequestCount());
    }
  }

  @Test
  void card_discovery_is_scoped_expires_and_refuses_dispatch_before_a_valid_card()
      throws Exception {
    try (var peer = new MockWebServer();
        var sdk = sdk("card-owner");
        var other = sdk("card-other");
        var client = new A2aClient(peer.url("/rpc").toString(), null, Duration.ofSeconds(2))) {
      var outgoing = new OutgoingClient(sdk);
      outgoing.advertise(null, List.of("fixture"), Map.of("fixture", card(peer)));
      assertEquals(
          "Fixture research", outgoing.peers(null).details().getFirst().agentCard().get("name"));
      assertTrue(new OutgoingClient(other).peers(null).details().isEmpty());
      assertEquals(
          "BAD_REQUEST",
          sdk.request(
                  "outgoing.advertise",
                  Map.of("peers", List.of("fixture"), "agentCards", Map.of("other", card(peer))))
              .code());
      var receipt = outgoing.send(send());
      peer.enqueue(new MockResponse().setBody("{}"));
      assertThrows(
          java.io.IOException.class,
          () -> new Adapter(outgoing, null, Map.of("fixture", client)).tick());
      assertEquals("QUEUED", outgoing.status(receipt.id()).state());
      assertEquals(1, peer.getRequestCount());
      jdbc.update(
          "UPDATE outgoing_peers SET advertised_at=now()-interval '3 minutes' WHERE account='card-owner'");
      assertTrue(outgoing.peers(null).peers().isEmpty());
      assertTrue(outgoing.peers(null).details().isEmpty());
    }
  }

  @Test
  void concurrent_claims_are_exclusive_reports_are_fenced_and_owner_access_is_enforced()
      throws Exception {
    try (var one = sdk("claim-user");
        var two = sdk("claim-user");
        var other = sdk("other-user");
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = new OutgoingClient(one);
      var second = new OutgoingClient(two);
      first.advertise(null, List.of("fixture"));
      var receipt = first.send(send());
      var a = tasks.submit(() -> first.claim(null, List.of("fixture")));
      var b = tasks.submit(() -> second.claim(null, List.of("fixture")));
      var ca = a.get(5, TimeUnit.SECONDS);
      var cb = b.get(5, TimeUnit.SECONDS);
      assertNotEquals(ca.work() == null, cb.work() == null);
      var claiming = ca.work() == null ? second : first;
      var notClaiming = ca.work() == null ? first : second;
      var claimed = ca.work() == null ? cb.work() : ca.work();
      var observation =
          new Outgoing.Report(
              receipt.id(),
              claimed.revision(),
              "WORKING",
              "remote",
              "context",
              Map.of("task", Map.of("id", "remote")),
              null);
      assertThrows(java.io.IOException.class, () -> notClaiming.report(observation));
      assertEquals("WORKING", claiming.report(observation).state());
      assertThrows(java.io.IOException.class, () -> claiming.report(observation));
      assertEquals(
          "BAD_REQUEST", other.request("outgoing.status", Map.of("id", receipt.id())).code());
      first.cancel(receipt.id());
      expire(receipt.id());
      var cancel = second.claim(null, List.of("fixture"));
      assertEquals("cancel", cancel.action());
      second.report(
          new Outgoing.Report(
              receipt.id(), cancel.work().revision(), "CANCELED", "remote", "context", null, null));
      assertEquals("CANCELED", first.status(receipt.id()).state());
    }
  }
}
