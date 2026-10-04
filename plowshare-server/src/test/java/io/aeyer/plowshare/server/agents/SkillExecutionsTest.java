package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SkillExecutionsTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  SkillExecutions executions;
  String parent;
  String child;
  final SkillDefinition skill =
      SkillDefinition.parse(
          new DefinitionSource.Definition(
              "review",
              "package/SKILL.md",
              "---\nname: review\ndescription: Review\nmode: NEW\nallowed-tools: file_read\n---\nPinned instructions."),
          OrchestrationDefinition.Tier.PROJECT);

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void fresh() {
    jdbc.execute("TRUNCATE TABLE conversations, admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle, password_hash) VALUES ('alice', 'hash'), ('bob', 'hash')");
    var conversations = new ConversationStore(jdbc, Instant::now, null);
    parent = conversations.open(Home.global(), Budget.of(8), TurnCap.of(4), "alice").id();
    child =
        conversations
            .log(Origin.DELEGATION, Home.global(), "interlocutor", parent, null, "alice")
            .id();
    executions = new SkillExecutions(jdbc);
  }

  @Test
  void claimed_work_is_account_scoped_and_retains_its_exact_source_across_resume() {
    UUID id = UUID.randomUUID();
    assertTrue(
        executions.claim(
            "alice", id, "exact input", parent, "interlocutor", skill, SkillDefinition.Mode.NEW));
    assertFalse(
        executions.claim(
            "alice",
            id,
            "different input",
            parent,
            "interlocutor",
            skill,
            SkillDefinition.Mode.NEW));
    assertTrue(executions.find("bob", id).isEmpty());
    assertEquals("exact input", executions.find("alice", id).orElseThrow().payload());
    executions.running("alice", id, child);
    assertThrows(IllegalStateException.class, () -> executions.running("alice", id, child));
    assertEquals(
        skill.source(), new SkillExecutions(jdbc).active(child).getFirst().skill().source());
    assertEquals(skill.hash(), executions.active(child).getFirst().skill().hash());
    executions.closed(child, new Outcome(Outcome.Ending.AWAITING, "Approval needed", 1, 1, ""));
    assertEquals("awaiting", executions.active(child).getFirst().state());
    executions.closed(child, new Outcome(Outcome.Ending.ANSWERED, "Reviewed", 1, 1, ""));
    assertTrue(executions.active(child).isEmpty());
    assertEquals("Reviewed", executions.find("alice", id).orElseThrow().result());
    assertFalse(
        executions.claim(
            "alice", id, "exact input", parent, "interlocutor", skill, SkillDefinition.Mode.NEW));
  }

  @Test
  void racing_claims_admit_one_invocation_only() throws Exception {
    UUID id = UUID.randomUUID();
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var starts =
          java.util.stream.IntStream.range(0, 12)
              .mapToObj(
                  ignored ->
                      pool.submit(
                          () ->
                              executions.claim(
                                  "alice",
                                  id,
                                  "input",
                                  parent,
                                  "interlocutor",
                                  skill,
                                  SkillDefinition.Mode.NEW)))
              .toList();
      int accepted = 0;
      for (var start : starts) if (start.get()) accepted++;
      assertEquals(1, accepted);
    }
    executions.failed("alice", id, "The accepted start could not proceed");
    assertEquals("failed", executions.find("alice", id).orElseThrow().state());
    assertTrue(executions.active(child).isEmpty());
  }

  @Test
  void context_copy_is_stable_and_old_result_handles_remain_scoped_to_the_child() {
    var entries =
        new io.aeyer.plowshare.server.archive.EntryStore(
            jdbc, (java.util.function.Supplier<Instant>) Instant::now);
    entries.append(parent, 1, LoggedEntry.utterance("Original context", Speaker.person("alice")));
    var call = new io.aeyer.plowshare.protocol.ToolCall("read", "file_read", "{}");
    entries.append(parent, 1, LoggedEntry.answer("", List.of(call)));
    var result = entries.append(parent, 1, LoggedEntry.toolResult("read", "Evidence payload"));
    Transcript transcript = org.mockito.Mockito.mock(Transcript.class);
    org.mockito.Mockito.when(transcript.conversationId()).thenReturn(parent);
    var contexts =
        new SkillContexts(entries, org.mockito.Mockito.mock(Compaction.class), executions);
    var snapshot =
        contexts.prepare(
            SkillDefinition.Mode.INHERITED, transcript, "alice", Budget.of(8), () -> false);
    UUID id = UUID.randomUUID();
    assertTrue(
        executions.claim(
            "alice", id, "input", parent, "interlocutor", skill, SkillDefinition.Mode.INHERITED));
    contexts.pin("alice", id, snapshot);
    executions.running("alice", id, child);
    Transcript childTranscript = org.mockito.Mockito.mock(Transcript.class);
    org.mockito.Mockito.when(childTranscript.conversationId()).thenReturn(child);
    contexts.seed(childTranscript, snapshot);
    entries.append(
        parent, 2, LoggedEntry.utterance("Later parent context", Speaker.person("alice")));
    var copied = entries.forConversation(child);
    assertEquals(1, copied.size());
    assertTrue(copied.getFirst().content().contains("Original context"));
    assertFalse(copied.getFirst().content().contains("Later parent context"));
    assertTrue(copied.getFirst().content().contains("speaker_name"));
    assertEquals(
        "Evidence payload",
        executions.contextResult("alice", child, result.handle()).orElseThrow());
    assertTrue(executions.contextResult("bob", child, result.handle()).isEmpty());
    assertTrue(executions.contextResult("alice", parent, result.handle()).isEmpty());
    assertTrue(executions.contextResult("alice", child, UUID.randomUUID()).isEmpty());
    assertEquals(4, entries.forConversation(parent).size());
  }

  @Test
  void command_binding_is_durable_scoped_and_claimed_once() throws Exception {
    var commands = new CommandInvocations(jdbc);
    var entry =
        new CommandCatalog.Entry(
            "/skill:review",
            List.of(),
            "skill",
            "review",
            "Review",
            "Arguments",
            "interlocutor",
            "NEW",
            "PROJECT",
            skill.hash());
    var first = commands.bind("alice", parent, "job", "bot", entry, "original request", "NEW");
    assertEquals(
        first.id(),
        commands.bind("alice", parent, "job", "bot", entry, "original request", "NEW").id());
    assertThrows(
        IllegalStateException.class,
        () -> commands.bind("alice", parent, "job", "bot", entry, "replacement", "NEW"));
    assertTrue(commands.find("bob", parent, "bot", first.id()).isEmpty());
    assertTrue(commands.find("alice", child, "bot", first.id()).isEmpty());
    assertTrue(commands.find("alice", parent, "other", first.id()).isEmpty());
    assertEquals(1, commands.pending("alice", parent, "bot").size());
    try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var claims = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int n = 0; n < 12; n++) claims.add(threads.submit(() -> commands.claim(first)));
      int won = 0;
      for (var claim : claims) if (claim.get()) won++;
      assertEquals(1, won);
    }
    assertEquals("dispatching", commands.pending("alice", parent, "bot").getFirst().state());
    assertEquals(
        "dispatching",
        new CommandInvocations(jdbc)
            .find("alice", parent, "bot", first.id())
            .orElseThrow()
            .state());
    assertFalse(commands.claim(first));
    commands.ended(first, "finished", "existing result");
    assertEquals(
        "existing result",
        commands.find("alice", parent, "bot", first.id()).orElseThrow().result());
  }
}
