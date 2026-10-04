package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.events.TriggerRecord;
import io.aeyer.plowshare.server.events.TriggerStore;
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
import io.aeyer.plowshare.server.swarm.DispatcherPools;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import io.aeyer.plowshare.server.swarm.SwarmScheduling;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A topic end to end, through the pieces a running server wires together: the real dispatcher
 * starts each owed wake through the seat runner, the real {@code Turn} speaks it, and the real
 * swarm scheduler admits every model call it makes as the seat's share (spec 2026-09-29 §5–§9).
 * Only the model is a stand-in — one that answers every call "noted." and says nothing on the
 * board, so every wake here is a silent one and each is counted as such.
 */
@Tag("full-db")
@Testcontainers
class BoardEndToEndTest {

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

  /** Answers every call "noted." and counts them. */
  private static final class Answering implements LlmTransport {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public String poolName() {
      return "spark";
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      calls.incrementAndGet();
      return new Completion("noted.", "stop", TokenUsage.UNKNOWN, List.of());
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      return complete(wireModel, messages, sampling, tools);
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      throw new UnsupportedOperationException("the board does not embed");
    }

    @Override
    public void close() {}
  }

  /** No trigger fires here; a trigger firing reaching this would be a wiring mistake. */
  private static final Dispatcher.Runner NO_TRIGGERS =
      new Dispatcher.Runner() {
        @Override
        public boolean busy(TriggerRecord trigger) {
          return false;
        }

        @Override
        public String start(
            TriggerRecord trigger, String utterance, BiConsumer<String, Outcome> ended) {
          throw new IllegalStateException("no trigger belongs in this test");
        }
      };

  @TempDir Path agents;

  private JobStore jobs;

  @AfterEach
  void stop() {
    if (jobs != null) {
      jobs.close();
    }
  }

  @Test
  void a_topic_wakes_its_members_through_the_scheduler_and_a_mention_wakes_one_again()
      throws Exception {
    BoardFixture fixture = new BoardFixture(source);
    BoardStore boardStore = fixture.store;

    Answering transport = new Answering();
    LlmPool spark =
        new LlmPool(
            "spark",
            List.of("model-fast"),
            Map.of("fast", "model-fast"),
            4,
            1,
            Duration.ofSeconds(5),
            transport,
            Set.of(),
            2);
    LlmDispatcher llm = new LlmDispatcher(List.of(spark), new NoOpTokenLedger());
    JobRuntime runtime = new JobRuntime(llm, List.of());
    jobs = new JobStore(runtime);
    Compaction compaction =
        new Compaction(
            llm,
            BoardEndToEndTest::folder,
            new TurnStore(fixture.jdbc),
            new CompactionStore(fixture.jdbc),
            new EntryStore(fixture.jdbc),
            1_000_000);
    Turn turn = new Turn(jobs, fixture.conversations, new TurnStore(fixture.jdbc), compaction);

    SwarmScheduler scheduler =
        new SwarmScheduler(
            new DispatcherPools(llm),
            () -> 4,
            () -> Duration.ofMinutes(10),
            Clock.systemUTC(),
            Duration.ofMillis(10));
    // Counted, because nothing else here could tell a scheduled seat run from an unscheduled
    // one: JobRuntime logs a share that could not be decided and runs the turn anyway, and a
    // run that was never scheduled leaves the pool's use at zero just as a released one does.
    // Every seat that runs here is a member's — the topic is a person's, so it has no opener
    // seat, whose runs shareOf leaves unscheduled — so each run counted is a member's.
    Function<RunExtras.Context, Optional<SwarmScheduler.Share>> shareOf =
        BoardConfig.shareOf(boardStore);
    AtomicInteger scheduled = new AtomicInteger();
    runtime.useScheduling(
        new SwarmScheduling(
            scheduler,
            context -> {
              Optional<SwarmScheduler.Share> share = shareOf.apply(context);
              share.ifPresent(found -> scheduled.incrementAndGet());
              return share;
            }));

    Dispatcher dispatcher =
        new Dispatcher(
            fixture.firings,
            new TriggerStore(fixture.jdbc),
            NO_TRIGGERS,
            new Inbox(new InboxStore(fixture.jdbc), AccountPushes.NONE, Instant::now),
            Instant::now);
    turn.whenFree(conversation -> dispatcher.drain("conversation:" + conversation));

    for (String name : List.of("researcher", "critic")) {
      Files.writeString(
          agents.resolve(name + ".md"),
          "---\nname: "
              + name
              + "\ndescription: "
              + name
              + "\nmodel: fast\ntools: []\n"
              + "max-turns: 4\nmax-model-calls: 8\n---\nYou help.\n");
    }
    AgentRegistry registry = AgentRegistry.of(agents, Set.of());
    Board board =
        new Board(
            boardStore,
            project -> BoardFixture.TWO,
            fixture.conversations,
            fixture.firings,
            dispatcher::drain,
            fixture.work,
            () -> 10,
            fixture.clock);
    BoardPot pot = new BoardPot(boardStore);
    SeatRunner seats =
        new SeatRunner(
            boardStore,
            board,
            pot,
            new SeatRunner.Voice() {
              @Override
              public boolean isSpeaking(String conversation) {
                return turn.isSpeaking(conversation);
              }

              @Override
              public String speakToSeat(
                  String conversation,
                  AgentDefinition member,
                  String utterance,
                  Budget lease,
                  TurnCap wakeCap,
                  Speaker speaker,
                  Consumer<Outcome> ended) {
                return turn.speakToSeat(
                    conversation, member, utterance, lease, wakeCap, speaker, ended);
              }
            },
            (agent, home) -> registry.get(agent),
            () -> 12,
            fixture.clock,
            dispatcher::drain,
            new DispatcherPools(llm));
    dispatcher.useWakes(seats);

    Board.Opened opened =
        board.open(
            new Board.Open(
                Home.of("payments"),
                "sync",
                "BAD SPEC / NEED INFO",
                "What does sync mean?",
                "enzo",
                BoardTopic.BY_PERSON,
                "enzo",
                null,
                null));

    awaitAllWakesFinished(fixture, opened.topic().id());
    assertEquals(2, transport.calls.get(), "each member answers its first wake once");
    assertEquals(2, boardStore.topic(opened.topic().id()).orElseThrow().potSpent());
    for (String member : List.of("researcher", "critic")) {
      assertEquals(
          1,
          boardStore.seat(opened.topic().id(), member).orElseThrow().silentWakes(),
          member + " said nothing and it was not counted");
    }
    assertEquals(2, scheduled.get(), "each seat run was scheduled as its seat's share");
    assertEquals(0, scheduler.snapshot().pools().get(0).used());
    assertEquals(0, pot.leased(opened.topic().id()));

    board.post(
        new Board.Post(
            opened.topic().id(),
            BoardMessage.BY_PERSON,
            "enzo",
            null,
            null,
            BoardMessage.POST,
            null,
            "@critic, anything to add?",
            null,
            List.of("critic"),
            false));
    awaitAllWakesFinished(fixture, opened.topic().id());
    assertEquals(3, transport.calls.get(), "a mention woke someone besides the one named");
    assertEquals(2, boardStore.seat(opened.topic().id(), "critic").orElseThrow().silentWakes());
    assertEquals(3, scheduled.get());
  }

  private static AgentDefinition folder() {
    return new AgentDefinition(
        Compaction.FOLDER,
        "a fixture folder",
        "fast",
        List.of(),
        List.of(),
        List.of(),
        1,
        1,
        "You summarise a span of a recorded conversation.");
  }

  private static void awaitAllWakesFinished(BoardFixture fixture, String topic)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (true) {
      Integer open =
          fixture.jdbc.queryForObject(
              "SELECT count(*) FROM firings WHERE"
                  + " topic = ? AND (status = 'queued' OR (status = 'started'"
                  + " AND finished_at IS NULL))",
              Integer.class,
              topic);
      if (open != null && open == 0) {
        return;
      }
      assertTrue(System.nanoTime() < deadline, "wakes still open: " + open);
      Thread.sleep(25);
    }
  }
}
