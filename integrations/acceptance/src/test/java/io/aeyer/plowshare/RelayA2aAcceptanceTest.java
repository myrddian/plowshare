package io.aeyer.plowshare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.a2a.A2aClient;
import io.aeyer.plowshare.a2a.Ingress;
import io.aeyer.plowshare.a2a.Receiver;
import io.aeyer.plowshare.a2a.model.ExternalMessage;
import io.aeyer.plowshare.a2a.relay.FileIngressJournal;
import io.aeyer.plowshare.a2a.relay.IngressConfiguration;
import io.aeyer.plowshare.a2a.relay.RelayGateway;
import io.aeyer.plowshare.a2a.relay.RelayIngressBridge;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.AdministrationClient;
import io.aeyer.plowshare.sdk.AgentClient;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.RelayClient;
import io.aeyer.plowshare.server.PlowshareServerApplication;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.auth.ServiceAccounts;
import io.aeyer.plowshare.server.auth.TokenStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.relay.RelayPortProperties;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

/**
 * HTTP A2A -> authenticated SDK WS -> migrated Postgres -> durable message outcome. No live
 * inference.
 */
@Tag("full-db")
@Testcontainers
// The application must release its pool and workers before its class-owned database stops.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = {PlowshareServerApplication.class, RelayA2aAcceptanceTest.NoEmbeddings.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RelayA2aAcceptanceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static final Path DATA = data();
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired TokenStore tokens;
  @Autowired ProjectStore projects;
  @Autowired ServiceAccounts services;
  @Autowired ProjectMembers members;
  @Autowired RelayPortProperties portGrants;

  /** Any accidental model I/O fails the isolated transport acceptance test. */
  @TestConfiguration
  static class NoEmbeddings {
    @Bean
    @Primary
    EmbeddingClient forbiddenEmbeddings() {
      return new EmbeddingClient() {
        public float[] embed(String text) {
          throw new AssertionError("This transport acceptance test must not request embeddings");
        }

        public List<float[]> embedAll(List<String> texts) {
          throw new AssertionError("This transport acceptance test must not request embeddings");
        }
      };
    }
  }

  private static Path data() {
    try {
      return new DataLayout(Files.createTempDirectory("plowshare-incoming-test-"))
          .initialise()
          .root();
    } catch (Exception failed) {
      throw new IllegalStateException(failed);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("server.address", () -> "127.0.0.1");
    registry.add(
        "plowshare.projects.workspace-directory",
        () ->
            Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "plowshare-test-workspaces-" + UUID.randomUUID())
                .toString());
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("plowshare.data.dir", DATA::toString);
    registry.add("LLM_BASE_URL", () -> "http://127.0.0.1:1/v1");
    registry.add("LLM_CHAT_MODEL", () -> "fixture-chat");
    registry.add("LLM_EMBEDDING_MODEL", () -> "fixture-embedding");
    // Declare the complete fixture pool: indexed Spring list overrides replace the YAML entry.
    // Vision keeps shipped definition dependencies available; no inference endpoint is running.
    registry.add("plowshare.llm.pools[0].name", () -> "fixture");
    registry.add("plowshare.llm.pools[0].base-url", () -> "http://127.0.0.1:1/v1");
    registry.add("plowshare.llm.pools[0].models[0]", () -> "fixture-chat");
    registry.add("plowshare.llm.pools[0].models[1]", () -> "fixture-embedding");
    registry.add("plowshare.llm.pools[0].vision[0]", () -> "fixture-chat");
    registry.add("plowshare.llm.pools[0].chat", () -> "1");
    registry.add("plowshare.llm.pools[0].embedding", () -> "1");
    registry.add("SPARK_BASE_URL", () -> "http://127.0.0.1:1/v1");
  }

  private Plowshare sdk(String account) throws Exception {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin,must_change_password) VALUES(?,'fixture',?,FALSE) ON CONFLICT DO NOTHING",
        account,
        account.equals("incoming-owner"));
    return Plowshare.connect(
        "http://127.0.0.1:" + port,
        tokens.issuePair(account, false).access(),
        Duration.ofSeconds(5),
        null);
  }

  private void awaitAdmission(String account) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (jdbc.queryForObject(
            "SELECT count(*) FROM firings WHERE message_instance IN (SELECT id FROM message_instances WHERE account=?)",
            Integer.class,
            account)
        == 0) {
      assertTrue(System.nanoTime() < deadline, "Message publication was not admitted");
      Thread.sleep(20);
    }
  }

  private void configureNativeRelay(Plowshare sdk, String project, Path workspace, String account)
      throws Exception {
    var grants = new ArrayList<>(portGrants.getBindings());
    grants.add(
        new RelayPortProperties.Binding(
            project, "a2a.requests", account, RelayPortProperties.Direction.INGRESS, List.of()));
    grants.add(
        new RelayPortProperties.Binding(
            project, "a2a.replies", account, RelayPortProperties.Direction.INGRESS, List.of()));
    grants.add(
        new RelayPortProperties.Binding(
            project,
            "a2a.replies",
            account,
            RelayPortProperties.Direction.EGRESS,
            List.of("a2a.receiver")));
    portGrants.setBindings(grants);
    Path routes = Files.createDirectories(workspace.resolve("Relay/a2a"));
    Files.writeString(
        workspace.resolve("Relay/active.json"), "{\"version\":1,\"active\":[\"a2a\"]}");
    Files.writeString(
        routes.resolve("routes.js"),
        """
        export const manifest={version:1,subscriptions:[{name:'requests',topic:'a2a.requests',kind:'text',start:'oldest-retained'}]};
        export function route(event) {
          const body=JSON.parse(event.payload.text);
          if(body.schema==='fixture.seed/1')return [];
          const names={'plowshare-message-request/1':'message.receive','plowshare-message-inspect/1':'message.inspect','plowshare-message-cancel/1':'message.cancel'};
          const receiver=names[body.schema];if(!receiver)throw new Error('Unsupported fixture command');
          return [{name:'message',receiver,work:{agent:'interlocutor',replyTopic:'a2a.replies',channel:'a2a'}}];
        }
        """);
    var relay = new RelayClient(sdk);
    for (String topic : List.of("a2a.requests", "a2a.replies"))
      relay.publish(
          new RelayPort.Publish(
              UUID.randomUUID().toString(),
              project,
              topic,
              "{\"schema\":\"fixture.seed/1\"}",
              Instant.now(),
              null,
              null,
              null));
    relay.process(new RelayLog.Process(project, 32));
  }

  /** A real public SDK pump exercises the registered receiver; no internal admission shortcut. */
  private Receiving receiving(
      Receiver.Config listener, Plowshare sdk, Path directory, String account) throws Exception {
    assertEquals(account, new AdministrationClient(sdk).status().handle());
    var binding =
        new RelayIngressBridge.Binding(
            listener.project(),
            listener.agent(),
            "a2a.requests",
            "a2a.replies",
            "a2a.receiver",
            UUID.nameUUIDFromBytes(directory.toString().getBytes(StandardCharsets.UTF_8))
                .toString(),
            "messaging:ingress",
            5000);
    var config =
        new IngressConfiguration(
            "http://127.0.0.1:" + port,
            "fixture-token-identity",
            directory,
            binding,
            listener,
            account);
    var journal =
        new FileIngressJournal(directory, config.fingerprint(), config.identityFingerprint());
    try {
      var relay = new RelayClient(sdk);
      RelayGateway gateway =
          new RelayGateway() {
            public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
              var receipt = relay.publish(request);
              relay.process(new RelayLog.Process(request.project(), 32));
              return receipt;
            }

            public RelayPort.Batch consume(RelayPort.Consume request) throws IOException {
              return relay.consume(request);
            }

            public RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException {
              return relay.acknowledge(request);
            }

            public RelayLog.Page log(RelayLog.Query request) throws IOException {
              return relay.log(request);
            }
          };
      var bridge =
          new RelayIngressBridge(
              binding,
              gateway,
              journal,
              () -> {
                var selected =
                    new AgentClient(sdk)
                        .list(listener.project()).stream()
                            .filter(agent -> agent.name().equals(listener.agent()))
                            .findFirst()
                            .orElseThrow(() -> new IOException("Missing receiving definition"));
                var commands =
                    selected.commands().stream()
                        .map(
                            command ->
                                new Ingress.Command(
                                    command.command(),
                                    command.aliases(),
                                    command.kind(),
                                    command.name(),
                                    command.description(),
                                    command.argumentHint(),
                                    command.executor(),
                                    command.mode(),
                                    command.tier(),
                                    command.hash(),
                                    command.agentVisible()))
                        .toList();
                return new Ingress.Catalog(
                    selected.name(), selected.description(), selected.served(), commands);
              },
              Instant::now);
      return new Receiving(new Receiver(listener, bridge), journal, bridge);
    } catch (Exception failure) {
      journal.close();
      throw failure;
    }
  }

  private record Receiving(Receiver receiver, FileIngressJournal journal, RelayIngressBridge bridge)
      implements AutoCloseable {
    int port() {
      return receiver.port();
    }

    @Override
    public void close() throws IOException {
      try {
        receiver.close();
      } finally {
        journal.close();
      }
    }
  }

  /** Programmatic fixture mirrors the validated A2A source that the HTTP receiver retains. */
  private static Ingress.Receive admission(
      String project, String client, UUID id, String body, String command) {
    var source =
        new Ingress.Source(
            id.toString(),
            List.of(new Ingress.TextPart(body)),
            command == null ? null : new Ingress.Metadata(command));
    return new Ingress.Receive(project, client, "interlocutor", id, null, body, command, source);
  }

  @Test
  void receiving_survives_sdk_and_adapter_restart_without_replaying_and_refuses_foreign_clients()
      throws Exception {
    String project = "incoming-fixture";
    UUID request = UUID.randomUUID();
    String taskId, contextId;
    Path journal = Files.createTempDirectory("a2a-native-receiving-");
    Path workspace = Files.createTempDirectory("incoming-workspace-");
    int httpPort;
    try (var socket = new ServerSocket(0)) {
      httpPort = socket.getLocalPort();
    }
    String endpoint = "http://127.0.0.1:" + httpPort + "/rpc";
    var config =
        new Receiver.Config(
            "127.0.0.1",
            httpPort,
            endpoint,
            project,
            "interlocutor",
            Map.of("remote", "fixture-token", "other", "other-token"),
            1000);
    var input = ExternalMessage.text("Please review this");
    try (var sdk = sdk("incoming-owner")) {
      projects.define(project, workspace, List.of(), "incoming-owner");
      configureNativeRelay(sdk, project, workspace, "incoming-owner");
      try (var receiver = receiving(config, sdk, journal, "incoming-owner");
          var client = new A2aClient(endpoint, "fixture-token", Duration.ofSeconds(3))) {
        assertEquals(httpPort, receiver.port());
        assertEquals("interlocutor", client.agentCard().name());
        var sent = client.send(request, input);
        taskId = sent.task();
        contextId = sent.context();
        assertNotNull(taskId);
        awaitAdmission("incoming-owner");
        assertEquals(taskId, client.send(request, input).task());
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM message_external_tasks WHERE account='incoming-owner'",
                Integer.class));
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM firings WHERE message_instance IN (SELECT id FROM message_instances WHERE account='incoming-owner')",
                Integer.class),
            "No caller model wake");
        try (var foreign = new A2aClient(endpoint, "other-token", Duration.ofSeconds(3))) {
          assertThrows(IOException.class, () -> foreign.status(taskId, contextId));
        }
        assertThrows(
            IOException.class, () -> client.send(request, ExternalMessage.text("Changed")));
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        var observed = client.status(taskId, contextId);
        while (observed.state().equals("WORKING") || observed.state().equals("SUBMITTED")) {
          assertTrue(System.nanoTime() < deadline);
          Thread.sleep(20);
          observed = client.status(taskId, contextId);
        }
        assertEquals(
            "FAILED",
            observed.state(),
            "Closed inference endpoint must preserve failure, never claim success");
      }
    }
    try (var sdk = sdk("incoming-owner");
        var receiver = receiving(config, sdk, journal, "incoming-owner");
        var client = new A2aClient(endpoint, "fixture-token", Duration.ofSeconds(3))) {
      assertEquals(httpPort, receiver.port());
      assertEquals(taskId, client.send(request, input).task());
      assertEquals("FAILED", client.status(taskId, contextId).state());
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM firings WHERE message_instance IN (SELECT id FROM message_instances WHERE account='incoming-owner')",
              Integer.class));
      assertThrows(
          IOException.class,
          () ->
              receiver
                  .bridge()
                  .receive(
                      admission(
                          project,
                          "remote",
                          UUID.randomUUID(),
                          "Bypass grant",
                          "/skill:ungranted")));
      Path route = workspace.resolve("Relay/a2a/routes.js");
      Files.writeString(
          route,
          Files.readString(route)
              .replace("agent:'interlocutor'", "agent:'scribe'")
              .replace("channel:'a2a'", "channel:'private-agent-refusal'"));
      assertThrows(
          IOException.class,
          () ->
              receiver
                  .bridge()
                  .receive(
                      admission(
                          project, "remote", UUID.randomUUID(), "Bypass private agent", null)));
    }
    try (var foreign = sdk("incoming-foreign")) {
      assertThrows(IOException.class, () -> new AgentClient(foreign).list(project));
      assertThrows(
          IOException.class,
          () ->
              new RelayClient(foreign)
                  .log(new RelayLog.Query(project, false, "a2a.replies", "0", 1)));
    }
  }

  @Test
  void scoped_machine_ingress_retains_its_receipt_across_rotation_and_obeys_live_grants()
      throws Exception {
    try (var operator = sdk("incoming-owner")) {
      assertTrue(new AdministrationClient(operator).status().serverAdmin());
      String project = "machine-ingress-" + UUID.randomUUID(),
          handle = "adapter-" + UUID.randomUUID();
      Path workspace = Files.createTempDirectory("machine-ingress-work-");
      projects.define(project, workspace, List.of(), "incoming-owner");
      services.create("incoming-owner", handle);
      members.assign(project, handle, ProjectRole.CONTRIBUTOR, "incoming-owner", true);
      var issued =
          services.issue(
              "incoming-owner",
              handle,
              "production",
              List.of(new ServiceAccounts.Scope(project, ProjectRole.CONTRIBUTOR)),
              30);
      UUID request = UUID.randomUUID();
      var payload =
          admission(project, "machine-client", request, "Retain this integration receipt", null);
      String id;
      Path journal = Files.createTempDirectory("machine-ingress-journal-");
      var listener =
          new Receiver.Config(
              "127.0.0.1",
              0,
              "http://127.0.0.1:9999/rpc",
              project,
              "interlocutor",
              Map.of("machine-client", "fixture-client"),
              1000);
      try (var machine =
          Plowshare.connect(
              "http://127.0.0.1:" + port, issued.credential(), Duration.ofSeconds(5), null)) {
        configureNativeRelay(machine, project, workspace, issued.token().principal());
        assertTrue(
            new AgentClient(machine)
                .list(project).stream()
                    .anyMatch(agent -> agent.name().equals("interlocutor") && agent.served()));
        try (var receiver = receiving(listener, machine, journal, issued.token().principal())) {
          var accepted = receiver.bridge().receive(payload);
          id = accepted.id().toString();
        }
        assertThrows(IOException.class, () -> new AdministrationClient(machine).accounts());
      }
      var rotated = services.rotate("incoming-owner", handle, issued.token().id(), 30);
      try (var machine =
          Plowshare.connect(
              "http://127.0.0.1:" + port, rotated.credential(), Duration.ofSeconds(5), null)) {
        try (var receiver = receiving(listener, machine, journal, issued.token().principal())) {
          assertEquals(id, receiver.bridge().receive(payload).id().toString());
        }
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM message_external_tasks WHERE account=?",
                Integer.class,
                issued.token().principal()));
        members.assign(project, handle, ProjectRole.VIEWER, "incoming-owner", false);
        try (var receiver = receiving(listener, machine, journal, issued.token().principal())) {
          assertThrows(
              IOException.class,
              () ->
                  receiver
                      .bridge()
                      .receive(
                          admission(
                              project, "machine-client", UUID.randomUUID(), "Do not start", null)));
          assertTrue(
              new AgentClient(machine)
                  .list(project).stream()
                      .anyMatch(agent -> agent.name().equals("interlocutor") && agent.served()));
          members.remove(project, handle, "incoming-owner");
          assertThrows(IOException.class, () -> new AgentClient(machine).list(project));
        }
      }
    }
  }
}
