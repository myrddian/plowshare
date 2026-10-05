package io.aeyer.plowshare;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.server.PlowshareServerApplication;
import io.aeyer.plowshare.server.auth.TokenStore;
import io.aeyer.plowshare.server.data.DataLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
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

/** Real authenticated socket, caller grants, pinned custom script, durable receipt and runtime. */
@Tag("full-db")
@Testcontainers
// The application must release its pool and workers before its class-owned database stops.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = {PlowshareServerApplication.class, EndToEndTest.StubbedEmbeddings.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrchestrationStartEndToEndTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static final ObjectMapper JSON = new ObjectMapper();
  static final Path DATA = fixture();
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired TokenStore tokens;
  @Autowired io.aeyer.plowshare.server.archive.ProjectMembers members;

  static Path fixture() {
    try {
      var layout =
          new DataLayout(Files.createTempDirectory("plowshare-direct-start-")).initialise();
      Files.createDirectories(layout.botsFor(null));
      Files.writeString(
          layout.botsFor(null).resolve("direct_fixture.md"),
          """
                    ---
                    name: direct_fixture
                    description: Authorises a deterministic custom script.
                    model: fast
                    exported: true
                    delegable: false
                    tools: []
                    calls: []
                    scopes: []
                    max-turns: 10
                    max-model-calls: 5
                    orchestrations: [direct_script]
                    ---
                    Direct fixture.
                    """);
      Files.createDirectories(layout.orchestrationsFor(null));
      Files.writeString(
          layout.orchestrationsFor(null).resolve("direct_script.js"),
          """
                    // plowshare-script v1
                    export const manifest={name:'direct_script',description:'A deterministic direct-start fixture',model:'fast',
                      tools:[],calls:[],scopes:[],stages:[{id:'work'}],'max-turns':10,'max-model-calls':5};
                    export function step(input) {
                      const s=input.state || {n:0}; const todo=input.todos.find(t=>t.stageId==='work');
                      if(s.n++===0 && JSON.parse(input.message).request==='ask')
                        return {state:s,command:{tool:'orchestration_ask',arguments:{question:'Which source should I use?'}}};
                      if(String(todo.status).toLowerCase()==='pending')
                        return {state:s,command:{tool:'todo_write',arguments:{ops:[{op:'update',id:todo.id,status:'in_progress'}]}}};
                      if(String(todo.status).toLowerCase()!=='done')
                        return {state:s,command:{tool:'todo_write',arguments:{ops:[{op:'update',id:todo.id,status:'done',summary:'Completed without inference'}]}}};
                      return {state:s,command:{tool:'orchestration_finish',arguments:{result:'deterministic result'}}};
                    }
                    """);
      return layout.root();
    } catch (Exception failed) {
      throw new IllegalStateException(failed);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "plowshare.projects.workspace-directory",
        () ->
            java.nio.file.Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "plowshare-test-workspaces-" + java.util.UUID.randomUUID())
                .toString());
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("plowshare.data.dir", DATA::toString);
    // No inference endpoint can accidentally perform paid work in this fixture.
    registry.add("LLM_BASE_URL", () -> "http://127.0.0.1:1/v1");
    registry.add("SPARK_BASE_URL", () -> "http://127.0.0.1:1/v1");
  }

  @Test
  void lost_acknowledgment_recovers_one_custom_run_and_questions_survive_disconnects()
      throws Exception {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('direct-user','fixture',FALSE)");
    String access = tokens.issuePair("direct-user", false).access();
    String key = UUID.randomUUID().toString();
    var start =
        Map.<String, Object>of(
            "agent",
            "direct_fixture",
            "definition",
            "direct_script",
            "request",
            "complete",
            "requestId",
            key);
    // Discard the acknowledgment after the server commits and disconnect that session.
    assertNull(ask(access, "orchestration.start", start, true));
    var receipt = ask(access, "orchestration.receipt", Map.of("requestId", key), false);
    assertEquals("OK", receipt.path("code").asText(), receipt.toString());
    String id = receipt.path("payload").path("id").asText();
    assertTrue(id.startsWith("orc_"));
    assertEquals(
        id, ask(access, "orchestration.start", start, false).path("payload").path("id").asText());
    var changed = new java.util.HashMap<>(start);
    changed.put("request", "changed");
    assertEquals(
        "BAD_REQUEST", ask(access, "orchestration.start", changed, false).path("code").asText());
    var status = awaitState(access, id, "finished");
    assertEquals("deterministic result", status.path("orchestration").path("result").asText());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM orchestrations WHERE caller_handle='direct-user'",
            Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM inference_calls WHERE orchestration_id=?", Integer.class, id));

    var ungranted = new java.util.HashMap<>(start);
    ungranted.put("definition", "deep_research");
    ungranted.put("requestId", UUID.randomUUID().toString());
    assertEquals(
        "BAD_REQUEST", ask(access, "orchestration.start", ungranted, false).path("code").asText());
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('other-user','fixture',FALSE)");
    jdbc.update(
        "INSERT INTO projects(name) VALUES ('private-project') ON CONFLICT (name) DO NOTHING");
    members.add("private-project", "other-user");
    assertTrue(members.mayUse("private-project", "other-user"));
    var inaccessible = new java.util.HashMap<>(start);
    inaccessible.put("project", "private-project");
    inaccessible.put("requestId", UUID.randomUUID().toString());
    assertEquals(
        "BAD_REQUEST",
        ask(access, "orchestration.start", inaccessible, false).path("code").asText());
    var asking =
        Map.<String, Object>of(
            "agent",
            "direct_fixture",
            "definition",
            "direct_script",
            "request",
            "ask",
            "requestId",
            UUID.randomUUID().toString());
    String questionRun =
        ask(access, "orchestration.start", asking, false).path("payload").path("id").asText();
    var question = awaitState(access, questionRun, "asking");
    assertTrue(question.path("messages").toString().contains("Which source should I use?"));
    assertEquals(
        "asking",
        ask(access, "orchestration.status", Map.of("id", questionRun), false)
            .path("payload")
            .path("orchestration")
            .path("state")
            .asText());
    assertEquals(
        "OK",
        ask(access, "orchestration.cancel", Map.of("id", questionRun), false)
            .path("code")
            .asText());
    awaitState(access, questionRun, "cancelled");
  }

  @Test
  void an_agent_job_can_open_a_personal_conversation_in_one_authorized_request() throws Exception {
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('job-user','fixture')");
    String access = tokens.issuePair("job-user", false).access();
    var started =
        ask(
            access,
            "agent.run",
            Map.of("agent", "direct_fixture", "task", "work", "newConversation", true),
            false);
    assertEquals("ACCEPTED", started.path("code").asText(), started.toString());
    String conversation = started.path("payload").path("conversation").asText();
    assertTrue(conversation.startsWith("cnv_"));
    assertTrue(started.path("payload").path("id").asText().startsWith("job_"));
    var row =
        jdbc.queryForMap(
            "SELECT origin,project_id,budget_total,owner_handle FROM conversations WHERE id=?",
            conversation);
    assertEquals("turn", row.get("origin"));
    assertEquals(
        jdbc.queryForObject(
            "SELECT id FROM projects WHERE personal_owner=?", Long.class, "job-user"),
        row.get("project_id"));
    assertNotNull(row.get("budget_total"));
    assertEquals("job-user", row.get("owner_handle"));
    var refused =
        ask(
            access,
            "agent.run",
            Map.of("agent", "unknown", "task", "work", "newConversation", true),
            false);
    assertEquals("BAD_REQUEST", refused.path("code").asText());
  }

  @Test
  void personal_is_private_over_http_ws_and_git_even_when_a_conversation_id_is_known()
      throws Exception {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('private-user','fixture'),('curious-user','fixture')");
    String owner = tokens.issuePair("private-user", false).access();
    String other = tokens.issuePair("curious-user", false).access();
    var opened = ask(owner, "conversation.open", Map.of(), false);
    assertEquals("OK", opened.path("code").asText(), opened.toString());
    String conversation = opened.path("payload").path("id").asText();
    String personal = opened.path("payload").path("project").asText();
    assertTrue(personal.startsWith("personal:"));
    assertEquals(
        "BAD_REQUEST",
        ask(other, "conversation.trajectory", Map.of("conversation", conversation), false)
            .path("code")
            .asText());
    assertFalse(
        ask(other, "project.list", Map.of(), false).path("payload").toString().contains(personal));
    var http = new OkHttpClient();
    for (String route :
        java.util.List.of(
            "/v1/agents?project=" + personal,
            "/v1/conversations/" + conversation + "/trajectory")) {
      try (var answer =
          http.newCall(
                  new Request.Builder()
                      .url("http://localhost:" + port + route)
                      .header("Authorization", "Bearer " + other)
                      .build())
              .execute()) {
        assertEquals(400, answer.code());
      }
    }
    try (var answer =
        http.newCall(
                new Request.Builder()
                    .url("http://localhost:" + port + "/v1/memories/recall")
                    .header("Authorization", "Bearer " + other)
                    .post(
                        RequestBody.create(
                            "{\"project\":\"" + personal + "\",\"question\":\"private\"}",
                            MediaType.get("application/json")))
                    .build())
            .execute()) {
      assertEquals(400, answer.code());
    }
    try (var answer =
        http.newCall(
                new Request.Builder()
                    .url(
                        "http://localhost:"
                            + port
                            + "/v1/sync/"
                            + personal
                            + ".git/info/refs?service=git-upload-pack")
                    .header("Authorization", "Bearer " + other)
                    .build())
            .execute()) {
      assertEquals(404, answer.code());
    }
  }

  JsonNode awaitState(String access, String id, String expected) throws Exception {
    JsonNode latest = null;
    for (int poll = 0; poll < 100; poll++) {
      latest = ask(access, "orchestration.status", Map.of("id", id), false).path("payload");
      if (latest.path("orchestration").path("state").asText().equals(expected)) return latest;
      assertNotEquals(
          "failed", latest.path("orchestration").path("state").asText(), latest.toString());
      Thread.sleep(50);
    }
    fail("Expected " + expected + ": " + latest);
    return latest;
  }

  JsonNode ask(String access, String type, Map<String, Object> payload, boolean discard)
      throws Exception {
    var answer = new CompletableFuture<JsonNode>();
    String correlation = UUID.randomUUID().toString();
    String frame =
        JSON.writeValueAsString(new Envelope(correlation, type, Envelope.CURRENT_VERSION, payload));
    var http = new OkHttpClient();
    var socket =
        http.newWebSocket(
            new Request.Builder()
                .url("http://localhost:" + port + "/v1/events?session=" + UUID.randomUUID())
                .header("Authorization", "Bearer " + access)
                .build(),
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket socket, Response response) {
                socket.send(frame);
              }

              @Override
              public void onMessage(WebSocket socket, String text) {
                try {
                  var envelope = JSON.readTree(text);
                  if (correlation.equals(envelope.path("id").asText()))
                    answer.complete(discard ? null : envelope.path("payload"));
                } catch (Exception failed) {
                  answer.completeExceptionally(failed);
                }
              }

              @Override
              public void onFailure(WebSocket socket, Throwable failure, Response response) {
                answer.completeExceptionally(failure);
              }
            });
    try {
      return answer.get(20, TimeUnit.SECONDS);
    } finally {
      socket.close(1000, "fixture disconnect");
      http.dispatcher().executorService().shutdown();
      http.connectionPool().evictAll();
    }
  }
}
