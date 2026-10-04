package io.aeyer.plowshare;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.a2a.*;
import io.aeyer.plowshare.protocol.Incoming;
import io.aeyer.plowshare.sdk.*;
import io.aeyer.plowshare.server.PlowshareServerApplication;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.auth.TokenStore;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

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
  @Autowired io.aeyer.plowshare.server.auth.ServiceAccounts services;
  @Autowired io.aeyer.plowshare.server.archive.ProjectMembers members;

  private static Path data() {
    try {
      return new io.aeyer.plowshare.server.data.DataLayout(
              Files.createTempDirectory("plowshare-incoming-test-"))
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
        "INSERT INTO admins(handle,password_hash,server_admin,must_change_password) VALUES(?,'fixture',?,FALSE) ON CONFLICT DO NOTHING",
        account,
        account.equals("incoming-owner"));
    return Plowshare.connect(
        "http://127.0.0.1:" + port,
        tokens.issuePair(account, false).access(),
        Duration.ofSeconds(5),
        null);
  }

  @Test
  void receiving_survives_sdk_and_adapter_restart_without_replaying_and_refuses_foreign_clients()
      throws Exception {
    String project = "incoming-fixture";
    UUID request = UUID.randomUUID();
    String taskId, contextId;
    int httpPort;
    try (var socket = new java.net.ServerSocket(0)) {
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
    var input = Map.<String, Object>of("parts", List.of(Map.of("text", "Please review this")));
    try (var sdk = sdk("incoming-owner")) {
      projects.define(
          project, Files.createTempDirectory("incoming-workspace-"), List.of(), "incoming-owner");
      try (var receiver = new Receiver(config, sdk);
          var client = new A2aClient(endpoint, "fixture-token", Duration.ofSeconds(3))) {
        assertEquals(httpPort, receiver.port());
        assertEquals("interlocutor", client.agentCard().get("name"));
        var sent = client.send(request, input);
        taskId = sent.task();
        contextId = sent.context();
        assertNotNull(taskId);
        assertEquals(taskId, client.send(request, input).task());
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM message_external_tasks WHERE account='incoming-owner'",
                Integer.class));
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM firings WHERE topic IN (SELECT topic FROM board_message_instances WHERE account='incoming-owner')",
                Integer.class),
            "No caller model wake");
        try (var foreign = new A2aClient(endpoint, "other-token", Duration.ofSeconds(3))) {
          assertThrows(java.io.IOException.class, () -> foreign.status(taskId, contextId));
        }
        assertThrows(
            java.io.IOException.class,
            () -> client.send(request, Map.of("parts", List.of(Map.of("text", "Changed")))));
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        var observed = client.status(taskId, contextId);
        while (observed.state().equals("WORKING")) {
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
              "SELECT count(*) FROM firings WHERE topic IN (SELECT topic FROM board_message_instances WHERE account='incoming-owner')",
              Integer.class));
      assertEquals(
          "BAD_REQUEST",
          sdk.request(
                  "incoming.receive",
                  Map.of(
                      "project",
                      project,
                      "client",
                      "remote",
                      "agent",
                      "scribe",
                      "requestId",
                      UUID.randomUUID(),
                      "body",
                      "Bypass private agent"))
              .code());
      assertEquals(
          "BAD_REQUEST",
          sdk.request(
                  "incoming.receive",
                  Map.of(
                      "project",
                      project,
                      "client",
                      "remote",
                      "agent",
                      "interlocutor",
                      "requestId",
                      UUID.randomUUID(),
                      "body",
                      "Bypass grant",
                      "command",
                      "/skill:ungranted"))
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
      assertEquals("OK", operator.request("admin.status", Map.of()).code());
      String project = "machine-ingress-" + UUID.randomUUID(),
          handle = "adapter-" + UUID.randomUUID();
      projects.define(
          project, Files.createTempDirectory("machine-ingress-work-"), List.of(), "incoming-owner");
      services.create("incoming-owner", handle);
      members.assign(
          project,
          handle,
          io.aeyer.plowshare.server.archive.ProjectRole.CONTRIBUTOR,
          "incoming-owner",
          true);
      var issued =
          services.issue(
              "incoming-owner",
              handle,
              "production",
              List.of(
                  new io.aeyer.plowshare.server.auth.ServiceAccounts.Scope(
                      project, io.aeyer.plowshare.server.archive.ProjectRole.CONTRIBUTOR)),
              30);
      UUID request = UUID.randomUUID();
      var payload =
          Map.<String, Object>of(
              "project",
              project,
              "client",
              "machine-client",
              "agent",
              "interlocutor",
              "requestId",
              request,
              "body",
              "Retain this integration receipt");
      String id;
      try (var machine =
          Plowshare.connect(
              "http://127.0.0.1:" + port, issued.credential(), Duration.ofSeconds(5), null)) {
        assertEquals(
            "OK",
            machine
                .request("incoming.catalog", Map.of("project", project, "agent", "interlocutor"))
                .code());
        var accepted = machine.request("incoming.receive", payload);
        assertEquals("OK", accepted.code());
        id = accepted.requirePayload().get("id").asText();
        assertEquals("BAD_REQUEST", machine.request("admin.accounts", Map.of()).code());
      }
      var rotated = services.rotate("incoming-owner", handle, issued.token().id(), 30);
      try (var machine =
          Plowshare.connect(
              "http://127.0.0.1:" + port, rotated.credential(), Duration.ofSeconds(5), null)) {
        assertEquals(
            id, machine.request("incoming.receive", payload).requirePayload().get("id").asText());
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM message_external_tasks WHERE account=?",
                Integer.class,
                issued.token().principal()));
        members.assign(
            project,
            handle,
            io.aeyer.plowshare.server.archive.ProjectRole.VIEWER,
            "incoming-owner",
            false);
        assertEquals(
            "BAD_REQUEST",
            machine
                .request(
                    "incoming.receive",
                    Map.of(
                        "project",
                        project,
                        "client",
                        "machine-client",
                        "agent",
                        "interlocutor",
                        "requestId",
                        UUID.randomUUID(),
                        "body",
                        "Do not start"))
                .code());
        assertEquals(
            "OK",
            machine
                .request("incoming.catalog", Map.of("project", project, "agent", "interlocutor"))
                .code());
        members.remove(project, handle, "incoming-owner");
        assertEquals(
            "BAD_REQUEST",
            machine
                .request("incoming.catalog", Map.of("project", project, "agent", "interlocutor"))
                .code());
      }
    }
  }
}
