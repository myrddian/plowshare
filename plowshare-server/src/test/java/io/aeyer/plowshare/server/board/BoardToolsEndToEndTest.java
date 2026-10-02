package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.delivery.PersonDelivery;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A bot's request reaches the board and returns as a resolution through the real runtime. */
@Testcontainers
class BoardToolsEndToEndTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static DriverManagerDataSource source;

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
    }

    /** Answers every call "noted." and counts them. */
    private static final class Answering implements LlmTransport {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger seatCalls = new AtomicInteger();
        private final java.util.function.IntSupplier persisted;

        Answering(java.util.function.IntSupplier persisted) {
            this.persisted = persisted;
        }
        final java.util.concurrent.CopyOnWriteArrayList<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        private static Completion call(String name, String args) {
            return new Completion("", "tool_calls", TokenUsage.UNKNOWN,
                    List.of(new ToolCall("call_" + java.util.UUID.randomUUID(), name, args)));
        }

        @Override
        public String poolName() {
            return "spark";
        }

        @Override
        public Completion complete(String wireModel, List<ChatMessage> messages,
                Sampling sampling, List<ToolSchema> tools) {
            calls.incrementAndGet();
            String system = messages.stream().filter(m -> m.role() == ChatMessage.Role.SYSTEM)
                    .map(ChatMessage::content).reduce("", (a,b) -> a + b);
            int lastUser = 0;
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).role() == ChatMessage.Role.USER) { lastUser = i; }
            }
            String user = messages.get(lastUser).content();
            received.add(system + "|" + user);
            List<ChatMessage> results = messages.subList(lastUser + 1, messages.size()).stream()
                    .filter(m -> m.role() == ChatMessage.Role.TOOL).toList();
            boolean seat = tools.stream().anyMatch(t -> t.name().equals("board_read"));
            if (seat) {
                assertTrue(persisted.getAsInt() >= seatCalls.incrementAndGet(),
                        "every seat request must already have its charge committed");
            }
            if (system.contains("You are the opener.")) {
                if (user.contains("is closed. Its resolution")) {
                    return new Completion("Thanks — I have the resolution.", "stop", TokenUsage.UNKNOWN, List.of());
                }
                if (!seat) {
                    if (results.isEmpty()) return call("board_open", "{\"title\":\"sync\",\"label\":\"BAD SPEC\",\"body\":\"What does sync mean?\"}");
                    return new Completion("Opened.", "stop", TokenUsage.UNKNOWN, List.of());
                }
                if (results.isEmpty()) return call("board_read", "{}");
                return call("board_close", "{\"resolution\":\"Sync means two devices converge.\"}");
            }
            if (system.contains("You are the researcher.")) {
                if (results.isEmpty()) return call("board_read", "{}");
                if (results.size() == 1) {
                    var id = java.util.regex.Pattern.compile("bdm_[A-Za-z0-9]+").matcher(results.getFirst().content());
                    if (!id.find()) throw new IllegalStateException("missing opening in " + results.getFirst().content());
                    return call("board_post", "{\"body\":\"Prior art: sync/merge.py converges replicas.\",\"reply_to\":\"" + id.group() + "\"}");
                }
                return new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
            }
            if (system.contains("You are the critic.")) return call("board_pass", "{\"reason\":\"Nothing to add.\"}");
            throw new IllegalStateException("unknown actor: " + system);
        }

        @Override
        public Completion stream(String wireModel, List<ChatMessage> messages,
                Sampling sampling, List<ToolSchema> tools, Deltas sink,
                BooleanSupplier abandoned) {
            return complete(wireModel, messages, sampling, tools);
        }

        @Override
        public Embeddings embed(String wireModel, List<String> input) {
            throw new UnsupportedOperationException("the board does not embed");
        }

        @Override
        public void close() {
        }
    }

    /** No trigger fires here; a trigger firing reaching this would be a wiring mistake. */
    private static final Dispatcher.Runner NO_TRIGGERS = new Dispatcher.Runner() {
        @Override
        public boolean busy(TriggerRecord trigger) {
            return false;
        }

        @Override
        public String start(TriggerRecord trigger, String utterance,
                BiConsumer<String, Outcome> ended) {
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
    void a_bot_opens_members_read_post_and_pass_and_the_resolution_returns_once()
            throws Exception {
        BoardFixture fixture = new BoardFixture(source);
        BoardStore boardStore = fixture.store;

        Answering transport = new Answering(() -> fixture.jdbc.queryForObject(
                "SELECT COALESCE(sum(pot_spent), 0) FROM board_topics WHERE parent IS NULL",
                Integer.class));
        LlmPool spark = new LlmPool("spark", List.of("model-fast"), Map.of("fast", "model-fast"),
                4, 1, Duration.ofSeconds(5), transport, Set.of(), 2);
        LlmDispatcher llm = new LlmDispatcher(List.of(spark), new NoOpTokenLedger());
        JobRuntime runtime = new JobRuntime(llm, List.of());
        runtime.useLogOwners(fixture.conversations::ownerOf);
        jobs = new JobStore(runtime);
        Compaction compaction = new Compaction(llm, BoardToolsEndToEndTest::folder,
                new TurnStore(fixture.jdbc), new CompactionStore(fixture.jdbc),
                new EntryStore(fixture.jdbc), 1_000_000);
        Turn turn = new Turn(jobs, fixture.conversations, new TurnStore(fixture.jdbc),
                compaction);

        SwarmScheduler scheduler = new SwarmScheduler(new DispatcherPools(llm), () -> 4,
                () -> Duration.ofMinutes(10), Clock.systemUTC(), Duration.ofMillis(10));
        // Counted, because nothing else here could tell a scheduled seat run from an unscheduled
        // one: JobRuntime logs a share that could not be decided and runs the turn anyway, and a
        // run that was never scheduled leaves the pool's use at zero just as a released one does.
        // Only member seats use swarm slots. The opener and the person's chat run unscheduled.
        Function<RunExtras.Context, Optional<SwarmScheduler.Share>> shareOf =
                BoardConfig.shareOf(boardStore);
        AtomicInteger scheduled = new AtomicInteger();
        runtime.useScheduling(new SwarmScheduling(scheduler, context -> {
            Optional<SwarmScheduler.Share> share = shareOf.apply(context);
            share.ifPresent(found -> scheduled.incrementAndGet());
            return share;
        }));

        Dispatcher dispatcher = new Dispatcher(fixture.firings, new TriggerStore(fixture.jdbc),
                NO_TRIGGERS, new Inbox(new InboxStore(fixture.jdbc), AccountPushes.NONE,
                        Instant::now), Instant::now);
        turn.whenFree(conversation -> dispatcher.drain("conversation:" + conversation));

        for (String name : List.of("opener", "researcher", "critic")) {
            Files.writeString(agents.resolve(name + ".md"), "---\nname: " + name
                    + "\ndescription: " + name + "\nmodel: fast\ntools: []\n"
                    + "max-turns: 12\nmax-model-calls: 40\n"
                    + (name.equals("opener") ? "bot: true\nboard: true\n" : "")
                    + "---\nYou are the " + name + ".\n");
        }
        AgentRegistry registry = AgentRegistry.of(agents, Set.of());
        Board board = new Board(boardStore, project -> BoardFixture.TWO, fixture.conversations,
                fixture.firings, dispatcher::drain, fixture.work, () -> 10, fixture.clock);
        BoardPot pot = new BoardPot(boardStore);
        SeatRunner seats = new SeatRunner(boardStore, board, pot, new SeatRunner.Voice() {
            @Override
            public boolean isSpeaking(String conversation) {
                return turn.isSpeaking(conversation);
            }

            @Override
            public String speakToSeat(String conversation, AgentDefinition member,
                    String utterance, Budget lease, TurnCap wakeCap, Speaker speaker,
                    Consumer<Outcome> ended) {
                return turn.speakToSeat(conversation, member, utterance, lease, wakeCap, speaker,
                        ended);
            }
        }, (agent, home) -> registry.get(agent), () -> 12, fixture.clock,
                dispatcher::drain, new DispatcherPools(llm));
        dispatcher.useWakes(seats);


        runtime.useRunExtras(new BoardRunExtras(boardStore, board, fixture.conversations));
        var people = new PersonDelivery(fixture.conversations, new PersonDelivery.Voice() {
            public boolean isSpeaking(String conversation) { return turn.isSpeaking(conversation); }
            public void speak(String conversation, String agent, String text, Speaker speaker) {
                turn.deliver(conversation, registry.get(agent), text, speaker, outcome -> { });
            }
        }, (handle, kind, text, about) -> { throw new AssertionError("expected delivery to the conversation"); });
        BoardDelivery delivery = new BoardDelivery(boardStore, people, turn::isSpeaking);
        board.useResolutions(delivery::resolved);
        turn.whenFree(delivery::drainFor);
        String chat = fixture.conversations.open(Home.of("payments"), Budget.of(40), null, "enzo").id();
        turn.speak(chat, registry.get("opener"), "Please derisk what 'sync' means.", null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String id;
        while (true) {
            var topics = fixture.jdbc.queryForList("SELECT id FROM board_topics", String.class);
            id = topics.isEmpty() ? null : topics.getFirst();
            if (id != null && boardStore.topic(id).orElseThrow().isClosed()
                    && !boardStore.resolutionUndelivered(id) && !turn.isSpeaking(chat)
                    && transport.received.stream().anyMatch(m -> m.contains("is closed. Its resolution"))) break;
            assertTrue(System.nanoTime() < deadline, "unfinished board flow: " + transport.received);
            Thread.sleep(25);
        }
        awaitAllWakesFinished(fixture, id);
        BoardTopic topic = boardStore.topic(id).orElseThrow();
        assertEquals(BoardTopic.CLOSED, topic.state());
        assertEquals(transport.seatCalls.get(), topic.potSpent(),
                "final settlement must not charge the model requests a second time");
        assertTrue(boardStore.message(topic.resolution()).orElseThrow().body().contains("converge"));
        assertFalse(boardStore.resolutionUndelivered(id));
        assertTrue(boardStore.seat(id, "critic").orElseThrow().passed());
        assertTrue(boardStore.messages(id).stream().anyMatch(m ->
                m.author().equals("researcher") && m.body().contains("sync/merge.py")));
        assertTrue(transport.received.stream().anyMatch(m -> m.contains("is closed. Its resolution")));
        assertEquals(0, pot.leased(id));
        assertEquals(0, scheduler.snapshot().pools().getFirst().used());
        delivery.drainAll();
        assertEquals(1, transport.received.stream().filter(m -> m.contains("is closed. Its resolution")).count());
    }

    private static AgentDefinition folder() {
        return new AgentDefinition(Compaction.FOLDER, "a fixture folder", "fast",
                List.of(), List.of(), List.of(), 1, 1,
                "You summarise a span of a recorded conversation.");
    }

    private static void awaitAllWakesFinished(BoardFixture fixture, String topic)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            Integer open = fixture.jdbc.queryForObject("SELECT count(*) FROM firings WHERE"
                    + " topic = ? AND (status = 'queued' OR (status = 'started'"
                    + " AND finished_at IS NULL))", Integer.class, topic);
            if (open != null && open == 0) {
                return;
            }
            assertTrue(System.nanoTime() < deadline, "wakes still open: " + open);
            Thread.sleep(25);
        }
    }
}
