package io.aeyer.plowshare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.a2a.A2aClient;
import io.aeyer.plowshare.a2a.Receiver;
import io.aeyer.plowshare.protocol.ExternalMessage;
import io.aeyer.plowshare.protocol.Incoming;
import io.aeyer.plowshare.sdk.AdministrationClient;
import io.aeyer.plowshare.sdk.IncomingClient;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.server.PlowshareServerApplication;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.auth.ServiceAccounts;
import io.aeyer.plowshare.server.auth.TokenStore;
import io.aeyer.plowshare.server.data.DataLayout;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
    classes = {PlowshareServerApplication.class, EndToEndTest.StubbedEmbeddings.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IncomingEndToEndTest {
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

  @Test
  void receiving_survives_sdk_and_adapter_restart_without_replaying_and_refuses_foreign_clients()
      throws Exception {
    String project = "incoming-fixture";
    UUID request = UUID.randomUUID();
    String taskId, contextId;
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
      projects.define(
          project, Files.createTempDirectory("incoming-workspace-"), List.of(), "incoming-owner");
      try (var receiver = new Receiver(config, sdk);
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
        var receiver = new Receiver(config, sdk);
        var client = new A2aClient(endpoint, "fixture-token", Duration.ofSeconds(3))) {
      assertEquals(httpPort, receiver.port());
      assertEquals(taskId, client.send(request, input).task());
      assertEquals("FAILED", client.status(taskId, contextId).state());
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM firings WHERE message_instance IN (SELECT id FROM message_instances WHERE account='incoming-owner')",
              Integer.class));
      var incoming = new IncomingClient(sdk);
      assertEquals(
          "BAD_REQUEST",
          assertThrows(
                  IncomingClient.Refused.class,
                  () ->
                      incoming.receive(
                          new Incoming.Receive(
                              project,
                              "remote",
                              "scribe",
                              UUID.randomUUID(),
                              null,
                              "Bypass private agent",
                              null,
                              null)))
              .code());
      assertEquals(
          "BAD_REQUEST",
          assertThrows(
                  IncomingClient.Refused.class,
                  () ->
                      incoming.receive(
                          new Incoming.Receive(
                              project,
                              "remote",
                              "interlocutor",
                              UUID.randomUUID(),
                              null,
                              "Bypass grant",
                              "/skill:ungranted",
                              null)))
              .code());
    }
    try (var foreign = sdk("incoming-foreign")) {
      assertThrows(
          IncomingClient.Refused.class,
          () ->
              new IncomingClient(foreign)
                  .status(new Incoming.Id(project, "remote", UUID.fromString(taskId))));
    }
  }

  @Test
  void scoped_machine_ingress_retains_its_receipt_across_rotation_and_obeys_live_grants()
      throws Exception {
    try (var operator = sdk("incoming-owner")) {
      assertTrue(new AdministrationClient(operator).status().serverAdmin());
      String project = "machine-ingress-" + UUID.randomUUID(),
          handle = "adapter-" + UUID.randomUUID();
      projects.define(
          project, Files.createTempDirectory("machine-ingress-work-"), List.of(), "incoming-owner");
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
          new Incoming.Receive(
              project,
              "machine-client",
              "interlocutor",
              request,
              null,
              "Retain this integration receipt",
              null,
              null);
      String id;
      try (var machine =
          Plowshare.connect(
              "http://127.0.0.1:" + port, issued.credential(), Duration.ofSeconds(5), null)) {
        var incoming = new IncomingClient(machine);
        assertTrue(incoming.catalog(new Incoming.CatalogQuery(project, "interlocutor")).served());
        var accepted = incoming.receive(payload);
        id = accepted.id().toString();
        assertThrows(IOException.class, () -> new AdministrationClient(machine).accounts());
      }
      var rotated = services.rotate("incoming-owner", handle, issued.token().id(), 30);
      try (var machine =
          Plowshare.connect(
              "http://127.0.0.1:" + port, rotated.credential(), Duration.ofSeconds(5), null)) {
        assertEquals(id, new IncomingClient(machine).receive(payload).id().toString());
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM message_external_tasks WHERE account=?",
                Integer.class,
                issued.token().principal()));
        members.assign(project, handle, ProjectRole.VIEWER, "incoming-owner", false);
        var incoming = new IncomingClient(machine);
        assertEquals(
            "BAD_REQUEST",
            assertThrows(
                    IncomingClient.Refused.class,
                    () ->
                        incoming.receive(
                            new Incoming.Receive(
                                project,
                                "machine-client",
                                "interlocutor",
                                UUID.randomUUID(),
                                null,
                                "Do not start",
                                null,
                                null)))
                .code());
        assertTrue(incoming.catalog(new Incoming.CatalogQuery(project, "interlocutor")).served());
        members.remove(project, handle, "incoming-owner");
        assertThrows(
            IOException.class,
            () -> incoming.catalog(new Incoming.CatalogQuery(project, "interlocutor")));
      }
    }
  }
}
