package io.aeyer.plowshare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aeyer.plowshare.a2a.A2aClient;
import io.aeyer.plowshare.a2a.model.ExternalMessage;
import io.aeyer.plowshare.a2a.relay.FileDispatchJournal;
import io.aeyer.plowshare.a2a.relay.RelayAdapter;
import io.aeyer.plowshare.a2a.relay.RelayGateway;
import io.aeyer.plowshare.a2a.relay.RelayWork;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.sdk.AgentClient;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.RelayClient;
import io.aeyer.plowshare.sdk.ServerClient;
import io.aeyer.plowshare.server.PlowshareServerApplication;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.auth.TokenStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.messaging.MessageExportProperties;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayExecutionPublisher;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayPortProperties;
import io.aeyer.plowshare.server.relay.RelayPublishingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual broker/SDK/A2A boundaries; fixture SQL inspects effects and never fabricates exports. */
@Tag("full-db")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = {
      PlowshareServerApplication.class,
      RelayA2aAcceptanceTest.NoEmbeddings.class,
      RelayOutboundAcceptanceTest.Models.class
    },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RelayOutboundAcceptanceTest {
  private static final String OWNER = "outbound-owner", ADAPTER = "outbound-adapter";
  private static final String REQUESTS = "outbound.requests", REPLIES = "outbound.replies";
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final Path DATA = data();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @TempDir Path temporary;
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ProjectStore projects;
  @Autowired ProjectMembers members;
  @Autowired TokenStore tokens;
  @Autowired Relay relay;
  @Autowired RelayPortProperties ports;
  @Autowired RelayPublishingProperties publishing;
  @Autowired MessageExportProperties exports;
  @Autowired Model model;

  private record Scope(String project, Path workspace, Path journal, String consumer) {}

  private static Path data() {
    try {
      return new DataLayout(Files.createTempDirectory("relay-outbound-acceptance-"))
          .initialise()
          .root();
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("server.address", () -> "127.0.0.1");
    registry.add(
        "plowshare.projects.workspace-directory", () -> DATA.resolve("workspaces").toString());
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("plowshare.data.dir", DATA::toString);
    registry.add("LLM_BASE_URL", () -> "http://127.0.0.1:1/v1");
    registry.add("SPARK_BASE_URL", () -> "http://127.0.0.1:1/v1");
    registry.add("LLM_CHAT_MODEL", () -> "fixture-model");
    registry.add("LLM_EMBEDDING_MODEL", () -> "fixture-embedding");
  }

  private void account(String name) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin,must_change_password) VALUES(?,'fixture',?,FALSE) ON CONFLICT DO NOTHING",
        name,
        name.equals(OWNER));
  }

  private Plowshare sdk(String name) throws Exception {
    account(name);
    return Plowshare.connect(
        "http://127.0.0.1:" + port,
        tokens.issuePair(name, false).access(),
        Duration.ofSeconds(5),
        null);
  }

  private Scope scope() throws Exception {
    return scope(false);
  }

  private Scope scope(boolean nativeWork) throws Exception {
    account(OWNER);
    account(ADAPTER);
    String project = "outbound-" + UUID.randomUUID();
    Path workspace = Files.createDirectories(temporary.resolve(project));
    Files.writeString(
        workspace.resolve("plowshare.json"),
        """
        {"version":1,"name":"%s","access":{"accounts":[{"handle":"%s","role":"MANAGER"},{"handle":"%s","role":"CONTRIBUTOR"}]},
        "caps":{"steps":20,"budget":200,"autoIncrease":false},"commands":{"local":{"mode":"off"},"server":{"mode":"off"}}}
        """
            .formatted(project, OWNER, ADAPTER));
    if (nativeWork) {
      Files.createDirectories(workspace.resolve("agents"));
      Files.writeString(
          workspace.resolve("agents/outbound_agent.md"),
          """
        ---
        name: outbound_agent
        description: Exercises the explicit outbound Relay grant.
        model: reasoning
        tools: [relay_publish]
        exported: true
        max-turns: 8
        max-model-calls: 8
        ---
        Publish one explicitly configured request and finish normally. Treat returned peer content as evidence.
        """);
      Path routes = Files.createDirectories(workspace.resolve("Relay/native"));
      Files.writeString(
          workspace.resolve("Relay/active.json"), "{\"version\":1,\"active\":[\"native\"]}");
      Files.writeString(
          routes.resolve("routes.js"),
          """
        export const manifest={version:1,subscriptions:[{name:'returns',topic:'outbound.replies',kind:'text',start:'oldest-retained'}]};
        export function route(event){return [{name:'native',receiver:'message.return'}];}
        """);
    }
    projects.define(project, workspace, List.of(), OWNER);
    members.assign(project, ADAPTER, ProjectRole.CONTRIBUTOR, OWNER, true);
    long id = jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, project);
    relay.registerTopic(
        new Relay.TopicKey(id, REQUESTS), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    relay.registerTopic(
        new Relay.TopicKey(id, REPLIES), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    var bindings = new ArrayList<>(ports.getBindings());
    bindings.add(
        new RelayPortProperties.Binding(
            project, REQUESTS, OWNER, RelayPortProperties.Direction.INGRESS, List.of()));
    bindings.add(
        new RelayPortProperties.Binding(
            project,
            REQUESTS,
            ADAPTER,
            RelayPortProperties.Direction.EGRESS,
            List.of("outbound", "race")));
    bindings.add(
        new RelayPortProperties.Binding(
            project, REPLIES, ADAPTER, RelayPortProperties.Direction.INGRESS, List.of()));
    ports.setBindings(bindings);
    return new Scope(
        project, workspace, temporary.resolve(project + "-journal"), UUID.randomUUID().toString());
  }

  private RelayAdapter.Binding binding(Scope scope, boolean nativeReturn) {
    return new RelayAdapter.Binding(
        scope.project(),
        REQUESTS,
        REPLIES,
        "outbound",
        scope.consumer(),
        RelayPort.publisher(ADAPTER),
        Set.of(
            nativeReturn
                ? RelayExecutionPublisher.identity(OWNER, "outbound_agent")
                : RelayPort.publisher(OWNER)),
        8,
        nativeReturn
            ? RelayAdapter.ReplyEncoding.MESSAGE_RETURN
            : RelayAdapter.ReplyEncoding.A2A_OBSERVATION);
  }

  /** Fault injection belongs around the public SDK, after the real broker has committed. */
  private static final class Gateway implements RelayGateway {
    private final RelayClient client;
    boolean loseReply;

    Gateway(Plowshare sdk) {
      client = new RelayClient(sdk);
    }

    public RelayPort.Batch consume(RelayPort.Consume request) throws IOException {
      return client.consume(request);
    }

    public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
      var result = client.publish(request);
      if (loseReply) {
        loseReply = false;
        throw new IOException("Fixture lost accepted reply acknowledgement");
      }
      return result;
    }

    public RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException {
      return client.acknowledge(request);
    }

    public RelayLog.Page log(RelayLog.Query request) throws IOException {
      return client.log(request);
    }
  }

  private static RelayWork.Request request(RelayWork.Action action, String task, String context) {
    return new RelayWork.Request(
        "plowshare-a2a-dispatch/1",
        UUID.randomUUID(),
        "fixture",
        action,
        action == RelayWork.Action.SEND ? ExternalMessage.text("Opaque remote work") : null,
        task,
        context);
  }

  private static RelayPort.Publish publication(Scope scope, RelayWork.Request request)
      throws Exception {
    return new RelayPort.Publish(
        UUID.randomUUID().toString(),
        scope.project(),
        REQUESTS,
        JSON.writeValueAsString(request),
        Instant.now(),
        null,
        null,
        null);
  }

  private static FileDispatchJournal journal(Scope scope) throws IOException {
    return new FileDispatchJournal(
        scope.journal(), RelayPort.hash("acceptance-owner:" + scope.project()));
  }

  private static JsonNode report(RelayClient client, Scope scope, UUID request) throws Exception {
    var page = client.log(new RelayLog.Query(scope.project(), false, REPLIES, "0", 100));
    for (var event : page.events()) {
      var value = JSON.readTree(event.payload().text());
      if (value.path("requestId").asText().equals(request.toString())) return value;
    }
    throw new AssertionError("Missing adapter-owned observation for " + request);
  }

  /** Definite rejection owns a source-derived UUID, not an untrusted requested mutation UUID. */
  private static JsonNode rejectedReport(
      RelayClient client, Scope scope, RelayPort.Published source) throws Exception {
    var publication =
        client.log(
            new RelayLog.Query(
                scope.project(),
                false,
                REQUESTS,
                Long.toString(Long.parseLong(source.position()) - 1),
                1));
    assertEquals(1, publication.events().size());
    var event = publication.events().getFirst();
    assertEquals(source.position(), event.position());
    for (var reply :
        client.log(new RelayLog.Query(scope.project(), false, REPLIES, "0", 100)).events()) {
      var value = JSON.readTree(reply.payload().text());
      if (value.path("sourceEvent").asText().equals(event.eventId())) {
        assertEquals(event.publisher(), value.path("producer").asText());
        assertEquals("REJECTED", value.path("observation").path("state").asText());
        return value;
      }
    }
    throw new AssertionError("Missing definite rejection for authenticated source publication");
  }

  @Test
  void sends_once_then_explicitly_reads_after_restart_and_recovers_an_uncertain_reply_publication()
      throws Exception {
    var scope = scope();
    var send = request(RelayWork.Action.SEND, null, null);
    var input = publication(scope, send);
    var peer = new Peer();
    try (var http = new MockWebServer();
        var producer = sdk(OWNER)) {
      http.setDispatcher(peer);
      var publications = new RelayClient(producer);
      var first = publications.publish(input);
      assertEquals(first.position(), publications.publish(input).position());
      assertThrows(
          IOException.class,
          () ->
              publications.publish(
                  new RelayPort.Publish(
                      input.requestId(),
                      input.project(),
                      input.topic(),
                      input.text() + " ",
                      input.occurredAt(),
                      null,
                      null,
                      null)));
      try (var session = sdk(ADAPTER);
          var journal = journal(scope);
          var remote = new A2aClient(http.url("/rpc").toString(), null, Duration.ofSeconds(2))) {
        var gateway = new Gateway(session);
        gateway.loseReply = true;
        var adapter =
            new RelayAdapter(
                binding(scope, false), gateway, journal, Map.of("fixture", remote), Instant::now);
        var failure = assertThrows(IOException.class, adapter::tick);
        assertEquals(
            "Fixture lost accepted reply acknowledgement",
            failure.getMessage(),
            "This scenario must fail after committed reply publication, not earlier authorization");
        assertEquals(1, peer.sends.get());
        assertEquals(
            RelayWork.Phase.REPLY_INTENT, journal.find(send.requestId()).orElseThrow().phase());
      }
      // The accepted broker receipt is recovered positively; the external SendMessage is never
      // repeated.
      try (var session = sdk(ADAPTER);
          var journal = journal(scope);
          var remote = new A2aClient(http.url("/rpc").toString(), null, Duration.ofSeconds(2))) {
        var adapter =
            new RelayAdapter(
                binding(scope, false),
                new Gateway(session),
                journal,
                Map.of("fixture", remote),
                Instant::now);
        var recovered = adapter.reconcileThroughHead("0", 10);
        assertEquals(1, recovered.confirmed());
        assertTrue(adapter.tick());
        assertEquals(
            "WORKING",
            report(publications, scope, send.requestId())
                .path("observation")
                .path("state")
                .asText());
        var read = request(RelayWork.Action.READ, "remote-task", "remote-context");
        publications.publish(publication(scope, read));
        assertTrue(adapter.tick());
        var observed = report(publications, scope, read.requestId()).path("observation");
        assertEquals("COMPLETED", observed.path("state").asText());
        assertEquals(
            "https://evidence.example.invalid/result.txt",
            observed
                .path("result")
                .path("task")
                .path("artifacts")
                .get(0)
                .path("parts")
                .get(0)
                .path("url")
                .asText());
        assertFalse(adapter.tick());
      }
      assertEquals(1, peer.sends.get());
      assertEquals(1, peer.reads.get());
    }
  }

  @Test
  void unknown_first_send_has_no_invented_remote_identity_and_cancel_requires_observed_ownership()
      throws Exception {
    var scope = scope();
    var peer = new Peer();
    peer.dropNextSend = true;
    var uncertain = request(RelayWork.Action.SEND, null, null);
    try (var http = new MockWebServer();
        var producer = sdk(OWNER)) {
      http.setDispatcher(peer);
      var publications = new RelayClient(producer);
      publications.publish(publication(scope, uncertain));
      try (var session = sdk(ADAPTER);
          var journal = journal(scope);
          var remote = new A2aClient(http.url("/rpc").toString(), null, Duration.ofSeconds(1))) {
        var adapter =
            new RelayAdapter(
                binding(scope, false),
                new Gateway(session),
                journal,
                Map.of("fixture", remote),
                Instant::now);
        assertTrue(adapter.tick());
        var observed = journal.find(uncertain.requestId()).orElseThrow().observation();
        assertEquals("UNKNOWN", observed.state());
        assertEquals(null, observed.task());
        assertEquals(null, observed.context());
      }
      try (var session = sdk(ADAPTER);
          var journal = journal(scope);
          var remote = new A2aClient(http.url("/rpc").toString(), null, Duration.ofSeconds(1))) {
        var adapter =
            new RelayAdapter(
                binding(scope, false),
                new Gateway(session),
                journal,
                Map.of("fixture", remote),
                Instant::now);
        assertFalse(adapter.tick());
        assertEquals(1, peer.sends.get());
        var foreign = request(RelayWork.Action.CANCEL, "unobserved-task", "unobserved-context");
        var denied = publications.publish(publication(scope, foreign));
        assertTrue(adapter.tick());
        assertEquals(
            "REJECTED",
            rejectedReport(publications, scope, denied).path("observation").path("state").asText());
        assertEquals(0, peer.cancels.get());
        // A deliberately separate new operation can establish ownership; this is not recovery of
        // the unknown send.
        var known =
            new RelayWork.Request(
                "plowshare-a2a-dispatch/1",
                UUID.randomUUID(),
                "fixture",
                RelayWork.Action.SEND,
                ExternalMessage.text("Independently authorized new work"),
                null,
                null);
        publications.publish(publication(scope, known));
        assertTrue(adapter.tick());
        var cancel = request(RelayWork.Action.CANCEL, "remote-task", "remote-context");
        publications.publish(publication(scope, cancel));
        assertTrue(adapter.tick());
        assertEquals(
            "CANCELED",
            report(publications, scope, cancel.requestId())
                .path("observation")
                .path("state")
                .asText());
        assertEquals(1, peer.cancels.get());
        assertEquals(2, peer.sends.get());
        assertEquals(
            1,
            peer.sendIdentities.stream().filter(uncertain.requestId().toString()::equals).count());
      }
    }
  }

  @Test
  void discovery_refusal_is_journaled_and_untrusted_producers_or_remote_ids_have_no_peer_effect()
      throws Exception {
    var scope = scope();
    var peer = new Peer();
    peer.validCard = false;
    try (var http = new MockWebServer();
        var producer = sdk(OWNER);
        var session = sdk(ADAPTER);
        var journal = journal(scope)) {
      http.setDispatcher(peer);
      var publications = new RelayClient(producer);
      try (var remote = new A2aClient(http.url("/rpc").toString(), null, Duration.ofSeconds(2))) {
        var adapter =
            new RelayAdapter(
                binding(scope, false),
                new Gateway(session),
                journal,
                Map.of("fixture", remote),
                Instant::now);
        var unavailable = request(RelayWork.Action.SEND, null, null);
        var unavailableSource = publications.publish(publication(scope, unavailable));
        assertTrue(adapter.tick());
        var rejection = rejectedReport(publications, scope, unavailableSource);
        var retained =
            journal.find(UUID.fromString(rejection.path("requestId").asText())).orElseThrow();
        assertEquals("REJECTED", retained.observation().state());
        assertEquals("PEER_DISCOVERY_UNAVAILABLE", retained.observation().error());
        assertEquals(null, retained.observation().task());
        assertEquals(null, retained.observation().context());
        assertEquals(RelayWork.Phase.CONFIRMED, retained.phase());
        assertNotEquals(unavailable.requestId(), retained.requestId());
        assertEquals(3, peer.cards.get());
        assertEquals(0, peer.sends.get());
        peer.validCard = true;
        var valid = request(RelayWork.Action.SEND, null, null);
        publications.publish(publication(scope, valid));
        assertTrue(adapter.tick());
        assertEquals(1, peer.sends.get());
        // The adapter principal is a project member, but it is not in this binding's producer
        // allowlist.
        var grants = new ArrayList<>(ports.getBindings());
        grants.add(
            new RelayPortProperties.Binding(
                scope.project(),
                REQUESTS,
                ADAPTER,
                RelayPortProperties.Direction.INGRESS,
                List.of()));
        ports.setBindings(grants);
        new RelayClient(session)
            .publish(publication(scope, request(RelayWork.Action.SEND, null, null)));
        assertTrue(adapter.tick());
        assertEquals(1, adapter.diagnostics().foreignPublications());
        assertEquals(1, peer.sends.get());
        assertEquals(4, peer.cards.get());
        var unknownPeer =
            new RelayWork.Request(
                "plowshare-a2a-dispatch/1",
                UUID.randomUUID(),
                "unconfigured",
                RelayWork.Action.SEND,
                ExternalMessage.text("No endpoint authority"),
                null,
                null);
        publications.publish(publication(scope, unknownPeer));
        assertTrue(adapter.tick());
        assertEquals(1, peer.sends.get());
        assertEquals(4, peer.cards.get());
      }
    }
  }

  @Test
  void actual_broker_leases_fence_acknowledgement_and_journal_lock_fences_the_supported_owner()
      throws Exception {
    var scope = scope();
    try (var producer = sdk(OWNER);
        var one = sdk(ADAPTER);
        var two = sdk(ADAPTER);
        var foreign = sdk("outbound-foreign");
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      new RelayClient(producer)
          .publish(publication(scope, request(RelayWork.Action.SEND, null, null)));
      var first = new RelayClient(one);
      var second = new RelayClient(two);
      String ca = UUID.randomUUID().toString(), cb = UUID.randomUUID().toString();
      var a =
          tasks.submit(
              () ->
                  first.consume(
                      new RelayPort.Consume(
                          scope.project(),
                          REQUESTS,
                          "race",
                          ca,
                          RelayPort.Start.OLDEST_RETAINED,
                          1,
                          0)));
      var b =
          tasks.submit(
              () ->
                  second.consume(
                      new RelayPort.Consume(
                          scope.project(),
                          REQUESTS,
                          "race",
                          cb,
                          RelayPort.Start.OLDEST_RETAINED,
                          1,
                          0)));
      var ra = a.get(5, TimeUnit.SECONDS);
      var rb = b.get(5, TimeUnit.SECONDS);
      assertNotEquals(ra.status() == RelayPort.Status.DATA, rb.status() == RelayPort.Status.DATA);
      var leased = ra.status() == RelayPort.Status.DATA ? ra : rb;
      var owner = ra.status() == RelayPort.Status.DATA ? first : second;
      var other = ra.status() == RelayPort.Status.DATA ? second : first;
      String otherConsumer = ra.status() == RelayPort.Status.DATA ? cb : ca;
      assertEquals(
          RelayPort.Status.BUSY, ra.status() == RelayPort.Status.DATA ? rb.status() : ra.status());
      assertThrows(
          IOException.class,
          () ->
              other.acknowledge(
                  new RelayPort.Ack(
                      scope.project(),
                      REQUESTS,
                      "race",
                      otherConsumer,
                      leased.batchId(),
                      leased.fence(),
                      null)));
      assertThrows(
          IOException.class,
          () ->
              owner.acknowledge(
                  new RelayPort.Ack(
                      scope.project(),
                      REQUESTS,
                      "race",
                      leased.consumerId(),
                      leased.batchId(),
                      Long.toString(Long.parseLong(leased.fence()) + 1),
                      null)));
      owner.acknowledge(
          new RelayPort.Ack(
              scope.project(),
              REQUESTS,
              "race",
              leased.consumerId(),
              leased.batchId(),
              leased.fence(),
              null));
      assertThrows(
          IOException.class,
          () ->
              new RelayClient(foreign)
                  .consume(
                      new RelayPort.Consume(
                          scope.project(),
                          REQUESTS,
                          "race",
                          UUID.randomUUID().toString(),
                          RelayPort.Start.OLDEST_RETAINED,
                          1,
                          0)));
      assertThrows(
          IOException.class,
          () ->
              new RelayClient(foreign)
                  .log(new RelayLog.Query(scope.project(), false, REPLIES, "0", 1)));
      try (var held = journal(scope)) {
        assertThrows(IOException.class, () -> journal(scope));
        assertTrue(held.entries().isEmpty());
      }
    }
  }

  @Test
  void actual_execution_export_returns_to_original_requester_as_a_later_native_paid_job()
      throws Exception {
    var scope = scope(true);
    model.reset();
    var grants = new ArrayList<>(publishing.getGrants());
    grants.add(new RelayPublishingProperties.Grant(scope.project(), "outbound_agent", REQUESTS));
    publishing.setGrants(grants);
    var policies = new ArrayList<>(exports.getPolicies());
    policies.add(
        new MessageExportProperties.Policy(scope.project(), REQUESTS, "native", REPLIES, ADAPTER));
    exports.setPolicies(policies);
    var peer = new Peer();
    peer.completeImmediately = true;
    try (var http = new MockWebServer();
        var producer = sdk(OWNER);
        var session = sdk(ADAPTER);
        var journal = journal(scope)) {
      http.setDispatcher(peer);
      var agents = new AgentClient(producer);
      var original =
          agents.run(
              new AgentClient.Run(
                  "outbound_agent",
                  "Inspect external evidence",
                  scope.project(),
                  null,
                  null,
                  true));
      long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      ServerClient.JobStatus source;
      do {
        source = agents.status(original.id());
        if (source.outcome() != null && model.event != null) break;
        Thread.sleep(20);
      } while (System.nanoTime() < deadline);
      assertNotNull(source.outcome());
      assertNotNull(
          model.event,
          () ->
              "No committed native publication; fixture tool outcome=" + model.publicationFailure);
      assertEquals(
          "Remote result pending.", JSON.valueToTree(source.outcome()).path("text").asText());
      try (var remote = new A2aClient(http.url("/rpc").toString(), null, Duration.ofSeconds(2))) {
        var adapter =
            new RelayAdapter(
                binding(scope, true),
                new Gateway(session),
                journal,
                Map.of("fixture", remote),
                Instant::now);
        assertTrue(adapter.tick());
      }
      var broker = new RelayClient(producer);
      List<String> returned;
      do {
        broker.process(new RelayLog.Process(scope.project(), 32));
        // A firing claim temporarily uses "pending" before the dispatcher records the
        // registered paid job. Observe that later transition instead of querying a placeholder.
        returned =
            jdbc.queryForList(
                "SELECT f.job_id FROM message_execution_returns r JOIN firings f ON f.data->>'direct_message'=r.message WHERE r.source_event=? AND f.job_id IS NOT NULL AND f.job_id<>'pending'",
                String.class,
                model.event);
        if (!returned.isEmpty() && agents.status(returned.getFirst()).outcome() != null) break;
        Thread.sleep(20);
      } while (System.nanoTime() < deadline);
      assertEquals(1, returned.size());
      var result = agents.status(returned.getFirst());
      assertNotEquals(original.id(), result.id());
      assertEquals(original.conversation(), result.conversation());
      assertEquals(
          "Remote evidence received.", JSON.valueToTree(result.outcome()).path("text").asText());
      assertEquals(
          "Remote result pending.",
          JSON.valueToTree(agents.status(original.id()).outcome()).path("text").asText());
      assertTrue(
          jdbc.queryForObject(
              "SELECT r.reply_to=e.request_message AND r.recipient=e.requester AND e.account=? AND e.project=? FROM message_execution_returns x JOIN message_execution_exports e ON e.source_event=x.source_event JOIN message_routes r ON r.message=x.message WHERE e.source_event=?",
              Boolean.class,
              OWNER,
              scope.project(),
              model.event));
      var observed = JSON.readTree(model.observedBody);
      assertEquals("plowshare-a2a-observation/1", observed.path("schema").asText());
      assertEquals("COMPLETED", observed.path("observation").path("state").asText());
      assertTrue(
          observed.path("observation").path("result").path("task").path("artifacts").isArray());
      assertEquals(3, model.calls.get());
      assertEquals(1, peer.sends.get());
    }
  }

  /** The peer is an independent HTTP oracle; it never reads the adapter's journal or server SQL. */
  private static final class Peer extends Dispatcher {
    final AtomicInteger sends = new AtomicInteger(),
        reads = new AtomicInteger(),
        cancels = new AtomicInteger(),
        cards = new AtomicInteger();
    final List<String> sendIdentities = new CopyOnWriteArrayList<>();
    volatile boolean validCard = true, dropNextSend, completeImmediately;

    public MockResponse dispatch(RecordedRequest request) {
      try {
        if ("GET".equals(request.getMethod())) {
          cards.incrementAndGet();
          String origin = "http://" + request.getHeader("Host");
          return new MockResponse()
              .setHeader("Cache-Control", "no-store")
              .setBody(
                  validCard
                      ? JSON.writeValueAsString(
                          Map.of(
                              "name",
                              "Fixture research",
                              "description",
                              "External evidence",
                              "version",
                              "1.0",
                              "defaultInputModes",
                              List.of("text/plain"),
                              "defaultOutputModes",
                              List.of("text/plain"),
                              "capabilities",
                              Map.of(),
                              "skills",
                              List.of(
                                  Map.of(
                                      "id",
                                      "research",
                                      "name",
                                      "Research",
                                      "description",
                                      "Evidence",
                                      "tags",
                                      List.of("research"))),
                              "supportedInterfaces",
                              List.of(
                                  Map.of(
                                      "url",
                                      origin + "/rpc",
                                      "protocolBinding",
                                      "JSONRPC",
                                      "protocolVersion",
                                      "1.0"))))
                      : "{}");
        }
        var rpc = JSON.readTree(request.getBody().readUtf8());
        String method = rpc.path("method").asText();
        if (method.equals("SendMessage")) {
          sends.incrementAndGet();
          sendIdentities.add(rpc.path("params").path("message").path("messageId").asText());
          if (dropNextSend) {
            dropNextSend = false;
            return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
          }
        } else if (method.equals("GetTask")) {
          assertEquals("remote-task", rpc.path("params").path("id").asText());
          reads.incrementAndGet();
        } else if (method.equals("CancelTask")) {
          assertEquals("remote-task", rpc.path("params").path("id").asText());
          cancels.incrementAndGet();
        } else throw new AssertionError("Unexpected peer method " + method);
        String state =
            method.equals("CancelTask")
                ? "TASK_STATE_CANCELED"
                : method.equals("GetTask") || completeImmediately
                    ? "TASK_STATE_COMPLETED"
                    : "TASK_STATE_WORKING";
        var task =
            Map.of(
                "id",
                "remote-task",
                "contextId",
                "remote-context",
                "status",
                Map.of("state", state),
                "artifacts",
                List.of(
                    Map.of(
                        "artifactId",
                        "evidence",
                        "parts",
                        List.of(
                            Map.of(
                                "url",
                                "https://evidence.example.invalid/result.txt",
                                "mediaType",
                                "text/plain")))));
        return new MockResponse()
            .setBody(
                JSON.writeValueAsString(
                    Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        rpc.get("id"),
                        "result",
                        method.equals("SendMessage") ? Map.of("task", task) : task)));
      } catch (IOException failure) {
        throw new AssertionError(failure);
      }
    }
  }

  @TestConfiguration
  static class Models {
    @Bean
    Model outboundModel() {
      return new Model();
    }

    @Bean
    @Primary
    LlmDispatcher outboundDispatcher(Model model) {
      return new LlmDispatcher(
          List.of(
              new LlmPool(
                  "fixture",
                  List.of("fixture-model"),
                  Map.of(
                      "fast",
                      "fixture-model",
                      "reasoning",
                      "fixture-model",
                      "coding",
                      "fixture-model",
                      "vision",
                      "fixture-model"),
                  4,
                  1,
                  Duration.ofSeconds(5),
                  model,
                  Set.of("fixture-model"))),
          new NoOpTokenLedger(),
          type -> "fixture-model");
    }
  }

  static final class Model implements LlmTransport {
    volatile UUID event;
    volatile String observedBody;
    volatile String publicationFailure;
    final AtomicInteger calls = new AtomicInteger();

    void reset() {
      event = null;
      observedBody = null;
      publicationFailure = null;
      calls.set(0);
    }

    public String poolName() {
      return "fixture";
    }

    @Override
    public void close() {
      // The deterministic transport owns no network or background resources.
    }

    public Completion complete(
        String wire, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      calls.incrementAndGet();
      try {
        var incoming =
            messages.stream()
                .filter(
                    message ->
                        message.role() == ChatMessage.Role.USER
                            && message.content().startsWith("Incoming message ("))
                .reduce((a, b) -> b);
        if (incoming.isPresent()) {
          String text = incoming.orElseThrow().content();
          observedBody =
              JSON.readTree(text.substring(text.indexOf('\n') + 1)).path("body").asText();
          return new Completion("Remote evidence received.", "stop", TokenUsage.UNKNOWN, List.of());
        }
        var last =
            messages.stream()
                .filter(message -> message.role() == ChatMessage.Role.TOOL)
                .reduce((a, b) -> b);
        if (last.isPresent()) {
          assertEquals("publish", last.orElseThrow().toolCallId());
          String receipt = last.orElseThrow().content();
          if (receipt.startsWith("Relay publication refused: ")) {
            // Retain a safe category instead of parsing a friendly refusal as JSON or dumping
            // untrusted tool content. The fixture sends no credentials in tool arguments.
            publicationFailure =
                receipt.equals(
                        "Relay publication refused: This account does not have the required project access")
                    ? "PROJECT_ACCESS_REFUSED"
                    : "NATIVE_EXPORT_REFUSED";
            return new Completion("Native export refused.", "stop", TokenUsage.UNKNOWN, List.of());
          }
          if (!receipt.startsWith("{")) {
            publicationFailure = "INVALID_TOOL_RECEIPT";
            return new Completion(
                "Invalid native export receipt.", "stop", TokenUsage.UNKNOWN, List.of());
          }
          event =
              UUID.fromString(JSON.readTree(last.orElseThrow().content()).path("eventId").asText());
          return new Completion("Remote result pending.", "stop", TokenUsage.UNKNOWN, List.of());
        }
        assertTrue(tools.stream().anyMatch(tool -> tool.name().equals("relay_publish")));
        String payload = JSON.writeValueAsString(request(RelayWork.Action.SEND, null, null));
        return new Completion(
            "",
            "tool_calls",
            TokenUsage.UNKNOWN,
            List.of(
                new ToolCall(
                    "publish",
                    "relay_publish",
                    JSON.writeValueAsString(Map.of("topic", REQUESTS, "text", payload)))));
      } catch (IOException failure) {
        throw new AssertionError(failure);
      }
    }

    public Completion stream(
        String wire,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      var result = complete(wire, messages, sampling, tools);
      if (!result.content().isEmpty()) sink.answered(result.content());
      return result;
    }

    public Embeddings embed(String wire, List<String> input) {
      throw new AssertionError("No embeddings expected");
    }
  }
}
