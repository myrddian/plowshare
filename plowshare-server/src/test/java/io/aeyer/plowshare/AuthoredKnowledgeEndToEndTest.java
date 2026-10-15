package io.aeyer.plowshare;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.sdk.*;
import io.aeyer.plowshare.server.PlowshareServerApplication;
import io.aeyer.plowshare.server.auth.TokenStore;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Public SDK admission -> real processing -> an agent's classification/navigation/read/evidence
 * tools -> retained report provenance. Only chat decisions and embedding vectors are scripted. SQL
 * below seeds authentication and independently inspects durable provenance; it does not manufacture
 * documents, types, projections, relationships, evidence or reports.
 */
@Tag("full-db")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = {PlowshareServerApplication.class, AuthoredKnowledgeEndToEndTest.Models.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthoredKnowledgeEndToEndTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @TempDir static Path temporary;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Scripted MODEL = new Scripted();
  static final String ACCOUNT = "knowledge-owner";
  static final String BODY = "Refund safety 🔎\nDisable automatic refunds before replay.";
  static final InformationRequest.Selection SELECT =
      new InformationRequest.Selection(
          InformationRequest.Scope.personal(), "documents", null, InformationContent.VIEW);
  static final int CLOSED_PORT = closedPort();
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired TokenStore tokens;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) throws Exception {
    properties.add("server.address", () -> "127.0.0.1");
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("plowshare.data.dir", () -> temporary.resolve("server").toString());
    properties.add(
        "plowshare.projects.workspace-directory", () -> temporary.resolve("workspaces").toString());
    properties.add("plowshare.information.navigation-writes-enabled", () -> true);
    properties.add("LLM_BASE_URL", () -> "http://127.0.0.1:" + CLOSED_PORT + "/v1");
    properties.add("LLM_CHAT_MODEL", () -> Scripted.CHAT);
    properties.add("LLM_EMBEDDING_MODEL", () -> Scripted.EMBED);
    // A real pinned local tokenizer keeps derivation and embedding admission on production paths.
    Path tokenizer =
        Path.of(
            Objects.requireNonNull(
                    AuthoredKnowledgeEndToEndTest.class.getResource("/tokenizers/fixture.json"))
                .toURI());
    String hash =
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tokenizer)));
    properties.add("plowshare.llm.embedding-tokenizer.file", tokenizer::toString);
    properties.add("plowshare.llm.embedding-tokenizer.sha256", () -> hash);
  }

  @TestConfiguration
  static class Models {
    @Bean
    @Primary
    LlmDispatcher journeyDispatcher() {
      return new LlmDispatcher(
          List.of(
              new LlmPool(
                  "journey",
                  List.of(Scripted.CHAT, Scripted.EMBED),
                  Map.of("fast", Scripted.CHAT, "reasoning", Scripted.CHAT),
                  2,
                  2,
                  Duration.ofSeconds(10),
                  MODEL)),
          new NoOpTokenLedger(),
          ignored -> "fast");
    }
  }

  private WsServerClient client(String account) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin,must_change_password) VALUES(?,'fixture',?,false) ON CONFLICT DO NOTHING",
        account,
        account.equals(ACCOUNT));
    return new WsServerClient(
        "http://127.0.0.1:" + port, tokens.issuePair(account, false).access());
  }

  private static Information.Revision status(ServerClient client, UUID revision)
      throws IOException {
    return assertInstanceOf(
            InformationResponse.Revision.class,
            client.information(new InformationRequest.Status(SELECT, revision, null)))
        .value();
  }

  private static InformationResponse.Admission upload(ServerClient client, String name, String text)
      throws IOException {
    return assertInstanceOf(
        InformationResponse.Admission.class,
        client.information(
            new InformationRequest.Admission(
                InformationRequest.AdmissionAction.UPLOAD,
                SELECT,
                UUID.randomUUID(),
                name,
                text,
                null)));
  }

  private static void awaitPrepared(ServerClient client, UUID revision) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (true) {
      var row = status(client, revision);
      var stages =
          row.steps().stream()
              .filter(
                  step ->
                      Set.of("extract", "derive", "embed", "summarise", "summary_embed")
                          .contains(step.stage()))
              .toList();
      if (stages.size() == 5 && stages.stream().allMatch(step -> step.state().equals("ready")))
        return;
      assertFalse(
          stages.stream()
              .anyMatch(step -> Set.of("failed", "blocked", "cancelled").contains(step.state())),
          row.toString());
      assertTrue(System.nanoTime() < deadline, "Document processing did not finish: " + row);
      Thread.sleep(100);
    }
  }

  @Test
  void ingestClassifyDiscoverTraverseConsumeAndRetainProvenanceAcrossReconnect() throws Exception {
    InformationResponse.Admission service;
    InformationResponse.Admission runbook;
    InformationResponse.Admission decoy;
    InformationContent.Contract contract;
    String job;
    try (var sdk = client(ACCOUNT)) {
      assertTrue(
          assertInstanceOf(
                  InformationResponse.NavigationCapabilities.class,
                  sdk.information(new InformationRequest.NavigationCapabilities(SELECT)))
              .value()
              .writesEnabled());
      var draft =
          assertInstanceOf(
                  InformationResponse.TypeVersion.class,
                  sdk.information(
                      new InformationRequest.TypeDraft(
                          SELECT,
                          UUID.randomUUID(),
                          null,
                          null,
                          "runbook",
                          "Runbook",
                          "Operational procedure classification",
                          SchemaDefinition.fromWire(
                              Map.of(
                                  "type",
                                  "object",
                                  "properties",
                                  Map.of(
                                      "service",
                                      Map.of("type", "string", "enum", List.of("billing"))),
                                  "required",
                                  List.of("service"),
                                  "additionalProperties",
                                  false)),
                          null)))
              .value();
      contract =
          assertInstanceOf(
                  InformationResponse.TypeVersion.class,
                  sdk.information(
                      new InformationRequest.TypeTransition(
                          SELECT,
                          UUID.randomUUID(),
                          draft.contract().version(),
                          draft.controlRevision(),
                          true)))
              .value()
              .contract();
      service = upload(sdk, "billing-service.md", "Billing service owns refund recovery.");
      runbook = upload(sdk, "refund-runbook.md", BODY);
      decoy =
          upload(sdk, "unrelated.md", "An unrelated record must not enter the report's inputs.");
      sdk.information(
          new InformationRequest.Tags(
              SELECT, runbook.revision(), UUID.randomUUID(), List.of("billing")));
      awaitPrepared(sdk, service.revision());
      awaitPrepared(sdk, runbook.revision());
      refused(
          422,
          () ->
              sdk.information(
                  new InformationRequest.ContentRevise(
                      SELECT,
                      UUID.randomUUID(),
                      runbook.revision(),
                      new InformationContent.Typing(
                          "set", contract.version(), DocumentMetadata.empty()))));
      var unchanged =
          assertInstanceOf(
                  InformationResponse.NavigationSelection.class,
                  sdk.information(
                      new InformationRequest.DocumentGet(
                          SELECT, new InformationNavigation.Target(runbook.resource(), null))))
              .value();
      assertEquals(
          runbook.revision(),
          assertInstanceOf(InformationNavigation.Readable.class, unchanged).document().revision());
      MODEL.arm(service, runbook, contract);
      var started =
          new AgentClient(sdk.connection())
              .run(
                  new AgentClient.Run(
                      "interlocutor",
                      "Classify the refund runbook, discover it, follow its service relationship, read it and retain a report with exact evidence.",
                      null,
                      null,
                      null,
                      true));
      job = started.id();
      long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
      ServerClient.JobStatus finished;
      do {
        finished = sdk.job(job);
        assertTrue(System.nanoTime() < deadline, "Agent did not finish: " + finished);
        if (!finished.state().equals("RUNNING")) break;
        Thread.sleep(100);
      } while (true);
      assertNotNull(finished.outcome());
      assertEquals("ANSWERED", finished.outcome().ending(), finished.toString());
      assertEquals(12, MODEL.calls);
      assertTrue(finished.outcome().text().contains(MODEL.report.toString()));
      assertEquals(BODY, MODEL.window.path("text").asText());
      assertEquals(2, status(sdk, MODEL.classified).ordinal());
      var metadata =
          assertInstanceOf(
                  InformationResponse.Content.class,
                  sdk.information(new InformationRequest.ContentRead(SELECT, MODEL.classified)))
              .value();
      assertEquals(runbook.revision(), metadata.startingRevision());
      assertEquals(ACCOUNT, metadata.revisedBy());
      assertEquals("explicit_only", metadata.processingPolicy());
      assertEquals(contract.version(), metadata.contentType().contract().version());
      assertTrue(metadata.reused().stream().anyMatch(reuse -> reuse.stage().equals("extract")));
      assertEquals(0, status(sdk, MODEL.classified).allowanceSpent());
      assertEquals(
          status(sdk, runbook.revision()).contentHash(),
          status(sdk, MODEL.classified).contentHash());
    }

    // A fresh public connection recovers committed receipts and provenance, not cached test state.
    try (var sdk = client(ACCOUNT);
        var foreign = client("knowledge-outsider")) {
      var recovered =
          assertInstanceOf(
              InformationResponse.Admission.class,
              sdk.information(
                  new InformationRequest.ContentRevise(
                      SELECT,
                      MODEL.classificationRequest,
                      runbook.revision(),
                      new InformationContent.Typing(
                          "set",
                          contract.version(),
                          DocumentMetadata.instance(
                              SchemaValue.fromWire(Map.of("service", "billing")))))));
      assertEquals(MODEL.classified, recovered.revision());
      awaitPrepared(sdk, MODEL.report);
      var retainedAnswer =
          assertInstanceOf(
                  InformationResponse.Window.class,
                  sdk.information(new InformationRequest.Read(SELECT, MODEL.report, 8192, 0)))
              .value();
      assertTrue(retainedAnswer.text().contains(BODY));
      var report = status(sdk, MODEL.report);
      assertEquals(
          Set.of(service.revision(), runbook.revision(), MODEL.classified),
          new HashSet<>(report.inputs()));
      assertFalse(report.inputs().contains(decoy.revision()));
      assertEquals(List.of(MODEL.evidence), report.citations());
      assertEquals("draft", report.report().status());
      String producer = report.report().producedBy();
      assertNotNull(producer);
      assertEquals(
          ACCOUNT,
          jdbc.queryForObject(
              "SELECT owner_handle FROM conversations WHERE id=?", String.class, producer));
      var ledger =
          jdbc.queryForList(
              "SELECT revision_id FROM information_job_inputs WHERE job_id=?",
              UUID.class,
              producer);
      assertTrue(ledger.containsAll(report.inputs()));
      var evidence =
          assertInstanceOf(
                  InformationResponse.Evidence.class,
                  sdk.information(new InformationRequest.EvidenceRead(SELECT, MODEL.evidence)))
              .value();
      assertEquals(MODEL.classified, evidence.revisionId());
      assertEquals(BODY, evidence.quote());
      assertEquals(BODY.length(), evidence.endOffset() - evidence.startOffset());
      refused(
          404,
          () ->
              foreign.information(new InformationRequest.Read(SELECT, MODEL.classified, 8192, 0)));
      refused(
          404,
          () -> foreign.information(new InformationRequest.EvidenceRead(SELECT, MODEL.evidence)));
      assertInstanceOf(
          InformationNavigation.Unavailable.class,
          assertInstanceOf(
                  InformationResponse.NavigationSelection.class,
                  foreign.information(
                      new InformationRequest.DocumentGet(
                          SELECT, new InformationNavigation.Target(runbook.resource(), null))))
              .value());
      // A real source lifecycle mutation must close old report, evidence and job-result paths.
      sdk.information(
          new InformationRequest.RevisionChange(
              InformationRequest.RevisionAction.WITHDRAW,
              SELECT,
              MODEL.classified,
              UUID.randomUUID()));
      refused(
          404, () -> sdk.information(new InformationRequest.Read(SELECT, MODEL.report, 8192, 0)));
      refused(
          404, () -> sdk.information(new InformationRequest.EvidenceRead(SELECT, MODEL.evidence)));
      refused(404, () -> sdk.job(job));
    }
  }

  private static void refused(int status, org.junit.jupiter.api.function.Executable action) {
    var refusal = assertThrows(ServerClient.ServerError.class, action);
    assertEquals(status, refusal.status(), refusal.getMessage());
  }

  private static int closedPort() {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  /** Script consumes actual tool results and derives all downstream IDs and offsets from them. */
  static final class Scripted implements LlmTransport {
    static final String CHAT = "journey-chat", EMBED = "journey-embed";
    InformationResponse.Admission service, original;
    InformationContent.Contract contract;
    volatile UUID classified, evidence, report;
    final UUID classificationRequest = UUID.randomUUID();
    volatile JsonNode window;
    volatile int calls;

    synchronized void arm(
        InformationResponse.Admission service,
        InformationResponse.Admission original,
        InformationContent.Contract contract) {
      this.service = service;
      this.original = original;
      this.contract = contract;
    }

    @Override
    public String poolName() {
      return "journey";
    }

    @Override
    public OptionalInt contextLength(String model) {
      return OptionalInt.of(64000);
    }

    @Override
    public synchronized Completion complete(
        String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      if (tools.isEmpty()) {
        String system = messages.getFirst().content();
        String text =
            system.contains("autoTag")
                ? "{\"autoTag\":[\"billing\"],\"tagGroups\":{\"operations\":[\"billing\"]},\"documentAuthor\":null,\"documentOrganisation\":null}"
                : system.contains("Group the supplied existing tags")
                    ? "{}"
                    : "Retained operational summary.";
        return new Completion(text, "stop", TokenUsage.of(20, 10, null), List.of());
      }
      try {
        JsonNode previous = null;
        for (var message : messages)
          if (message.role() == ChatMessage.Role.TOOL) {
            previous = JSON.readTree(message.content());
          }
        Map<String, Object> arguments;
        String tool = "information_read";
        switch (calls++) {
          case 0 -> arguments = Map.of("operation", "types.list");
          case 1 -> {
            assertNotNull(previous);
            assertEquals(
                contract.version().toString(),
                previous.path("results").get(0).path("contract").path("version").asText());
            arguments =
                Map.of(
                    "operation",
                    "types.validate",
                    "version",
                    contract.version().toString(),
                    "metadata",
                    Map.of());
          }
          case 2 -> {
            assertEquals("INVALID", previous.path("status").asText().toUpperCase(Locale.ROOT));
            tool = "information_write";
            arguments =
                Map.of(
                    "operation",
                    "content.revise",
                    "requestId",
                    classificationRequest.toString(),
                    "expectedRevision",
                    original.revision().toString(),
                    "typing",
                    Map.of(
                        "mode",
                        "set",
                        "version",
                        contract.version().toString(),
                        "metadata",
                        Map.of("service", "billing")));
          }
          case 3 -> {
            classified = UUID.fromString(previous.path("revision").asText());
            assertNotEquals(original.revision(), classified);
            arguments =
                Map.of(
                    "operation",
                    "documents.list",
                    "filter",
                    Map.of("contentType", contract.type().toString(), "tags", List.of("billing")));
          }
          case 4 -> {
            assertEquals(1, previous.path("documents").size());
            var found = previous.path("documents").get(0);
            assertEquals(original.resource().toString(), found.path("resource").asText());
            assertEquals(classified.toString(), found.path("revision").asText());
            arguments =
                Map.of("operation", "content.get", "revision", found.path("revision").asText());
          }
          case 5 -> {
            assertEquals(
                "billing", previous.path("contentType").path("metadata").path("service").asText());
            tool = "information_write";
            arguments =
                Map.of(
                    "operation",
                    "relationships.create",
                    "requestId",
                    UUID.randomUUID().toString(),
                    "source",
                    service.resource().toString(),
                    "expectedSourceRevision",
                    service.revision().toString(),
                    "target",
                    Map.of("resource", original.resource().toString()),
                    "relation",
                    "runbook");
          }
          case 6 -> {
            assertEquals(original.resource().toString(), previous.path("target").asText());
            arguments =
                Map.of(
                    "operation",
                    "relationships.list",
                    "resource",
                    service.resource().toString(),
                    "direction",
                    "outgoing",
                    "relation",
                    "runbook");
          }
          case 7 -> {
            assertEquals(1, previous.path("connections").size());
            var target = previous.path("connections").get(0).path("selection").path("document");
            assertEquals(classified.toString(), target.path("revision").asText());
            arguments =
                Map.of(
                    "operation",
                    "documents.get",
                    "target",
                    Map.of("resource", target.path("resource").asText()));
          }
          case 8 -> {
            arguments =
                Map.of(
                    "operation",
                    "read",
                    "revision",
                    previous.path("document").path("revision").asText(),
                    "offset",
                    0,
                    "limit",
                    8192);
          }
          case 9 -> {
            window = previous;
            assertEquals(BODY, window.path("text").asText());
            tool = "information_write";
            arguments =
                Map.of(
                    "operation",
                    "evidence",
                    "requestId",
                    UUID.randomUUID().toString(),
                    "revision",
                    window.path("revision").asText(),
                    "start",
                    window.path("start").asInt(),
                    "end",
                    window.path("end").asInt(),
                    "quote",
                    window.path("text").asText(),
                    "locator",
                    "extracted-text:utf16");
          }
          case 10 -> {
            evidence = UUID.fromString(previous.path("evidence").asText());
            tool = "information_write";
            arguments =
                Map.of(
                    "operation",
                    "report",
                    "requestId",
                    UUID.randomUUID().toString(),
                    "name",
                    "refund-answer.md",
                    "text",
                    "Recovery procedure: " + window.path("text").asText(),
                    "inputs",
                    List.of(),
                    "citations",
                    List.of(evidence.toString()));
          }
          case 11 -> {
            report = UUID.fromString(previous.path("revision").asText());
            return new Completion(
                "Retained grounded report " + report,
                "stop",
                TokenUsage.of(20, 10, null),
                List.of());
          }
          default -> throw new AssertionError("Unexpected repeated agent step");
        }
        String selectedTool = tool;
        assertTrue(
            tools.stream().anyMatch(schema -> schema.name().equals(selectedTool)),
            "Agent lacks " + selectedTool);
        return new Completion(
            "",
            "tool_calls",
            TokenUsage.of(20, 10, null),
            List.of(new ToolCall("journey_" + calls, tool, JSON.writeValueAsString(arguments))));
      } catch (IOException invalid) {
        throw new java.io.UncheckedIOException(invalid);
      }
    }

    @Override
    public Completion stream(
        String model,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      if (abandoned.getAsBoolean()) throw new CallerAbandonedException(poolName());
      var result = complete(model, messages, sampling, tools);
      if (!result.content().isEmpty()) sink.answered(result.content());
      return result;
    }

    @Override
    public Embeddings embed(String model, List<String> input) {
      return new Embeddings(
          input.stream()
              .map(
                  text -> {
                    float[] vector = new float[768];
                    vector[0] = 1;
                    return vector;
                  })
              .toList(),
          TokenUsage.of(10, 0, null));
    }

    @Override
    public void close() {}
  }
}
