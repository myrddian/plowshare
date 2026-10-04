package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.delivery.PersonDelivery;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.swarm.DispatcherPools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual shipped entry points reach the starting-topic tool and get their resolution back. */
@Tag("full-db")
@Testcontainers
class BoardShippedOpeningTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @TempDir Path root;

  private AgentRegistry shipped() throws Exception {
    Path merged = Files.createDirectories(root.resolve("definitions"));
    for (String folder : List.of("agents", "bots")) {
      try (var files = Files.list(Path.of("src/main/resources", folder))) {
        for (Path file : files.filter(path -> path.toString().endsWith(".md")).toList()) {
          Files.copy(file, merged.resolve(file.getFileName()));
        }
      }
    }
    return AgentRegistry.of(merged, BoundTools.boundByThisServer());
  }

  @ParameterizedTest
  @ValueSource(strings = {"farnsworth", "aristoxenus", "daedalus", "interlocutor"})
  void shipped_chat_definitions_open_the_default_swarm_and_receive_its_resolution(String name)
      throws Exception {
    BoardFixture fixture = new BoardFixture(source);
    AgentRegistry registry = shipped();
    AgentDefinition definition = registry.get(name);
    assertTrue(definition.board(), name + " has no opening grant");
    OpeningTransport transport = new OpeningTransport();
    try (LlmDispatcher llm =
        new LlmDispatcher(
            List.of(
                new LlmPool(
                    "spark",
                    List.of("model"),
                    Map.of("reasoning", "model"),
                    4,
                    1,
                    Duration.ofSeconds(5),
                    transport,
                    java.util.Set.of(),
                    2)),
            new NoOpTokenLedger())) {
      SwarmDefinitions definitions =
          new SwarmDefinitions(new DataLayout(root), project -> registry, new DispatcherPools(llm));
      var swarm = definitions.forProject(null);
      assertEquals(List.of("researcher", "spec_writer", "critic"), swarm.members(), swarm.why());
      assertTrue(swarm.refused().isEmpty(), swarm.why());
      Board board = fixture.board(swarm);
      BoardRunExtras extras = new BoardRunExtras(fixture.store, board, fixture.conversations);
      JobRuntime runtime = new JobRuntime(llm, List.of(), () -> registry);
      runtime.useRunExtras(extras);
      String conversation =
          fixture.conversations.open(Home.of("payments"), Budget.of(10), null, "enzo").id();
      Transcript transcript =
          new Transcript() {
            public List<ChatMessage> before() {
              return List.of();
            }

            public void promptMeasured(int tokens) {}

            public String conversationId() {
              return conversation;
            }
          };
      Outcome outcome =
          runtime.run(
              definition,
              "Start a swarm to find what sync means.",
              Home.of("payments"),
              Budget.of(4),
              () -> false,
              null,
              JobWatch.UNWATCHED,
              transcript,
              TurnCap.of(4),
              List.of(),
              "enzo");
      assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
      assertTrue(transport.sawOpen, "the shipped definition never reached board_open");
      BoardTopic topic = fixture.store.openTopics().getFirst();
      assertEquals(definition.bot() ? BoardTopic.BY_BOT : BoardTopic.BY_AGENT, topic.openerKind());
      assertEquals(conversation, topic.originConversation());
      assertEquals(name, topic.opener());
      assertEquals("enzo", topic.account());
      assertEquals("What does sync mean?", fixture.store.messages(topic.id()).getFirst().body());
      assertEquals(
          3,
          fixture.jdbc.queryForObject(
              "SELECT count(*) FROM firings WHERE topic = ?", Integer.class, topic.id()));
      BoardSeat opener = fixture.store.seat(topic.id(), BoardSeat.OPENER).orElseThrow();
      var actions =
          extras.forRun(
              new RunExtras.Context(
                  definition,
                  opener.conversation(),
                  null,
                  Transcript.NONE,
                  Home.of("payments"),
                  "enzo",
                  null,
                  new TurnEnd()));
      var close =
          actions.tools().stream()
              .filter(tool -> tool.schema().name().equals("board_close"))
              .findFirst()
              .orElseThrow();
      assertTrue(
          close
              .run("{\"resolution\":\"Sync means replicas converge.\"}", Home.of("payments"))
              .startsWith("Closed."));
      List<String> delivered = new ArrayList<>();
      PersonDelivery people =
          new PersonDelivery(
              fixture.conversations,
              new PersonDelivery.Voice() {
                public boolean isSpeaking(String id) {
                  return false;
                }

                public void speak(String id, String agent, String text, Speaker speaker) {
                  delivered.add(id + "|" + agent);
                }
              },
              (handle, kind, text, about) ->
                  fail("resolution must return to the original conversation"));
      BoardDelivery delivery = new BoardDelivery(fixture.store, people, id -> false);
      delivery.resolved(topic.id());
      delivery.drainAll();
      assertEquals(List.of(conversation + "|" + name), delivered);
      assertFalse(fixture.store.resolutionUndelivered(topic.id()));
    }
  }

  private static final class OpeningTransport implements LlmTransport {
    boolean sawOpen;

    public String poolName() {
      return "spark";
    }

    public Completion complete(
        String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      if (!sawOpen) {
        assertTrue(tools.stream().anyMatch(tool -> tool.name().equals("board_open")));
        sawOpen = true;
        return new Completion(
            "",
            "tool_calls",
            TokenUsage.UNKNOWN,
            List.of(
                new ToolCall(
                    "start",
                    "board_open",
                    "{\"title\":\"sync\",\"label\":\"NEED INFO\",\"body\":\"What does sync mean?\"}")));
      }
      return new Completion("Opened.", "stop", TokenUsage.UNKNOWN, List.of());
    }

    public Completion stream(
        String model,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas deltas,
        BooleanSupplier abandoned) {
      return complete(model, messages, sampling, tools);
    }

    public Embeddings embed(String model, List<String> input) {
      throw new UnsupportedOperationException();
    }

    public void close() {}
  }
}
