package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.JdbcFiringStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** A clean board over one migrated database, for the board's tests. */
final class BoardFixture {

  /** Everything a board test writes, and everything that references it. */
  static final String TRUNCATE =
      "TRUNCATE TABLE firings, board_seats, board_messages,"
          + " board_topics, user_inbox, triggers, schedules, digests, digest_children,"
          + " digest_memories, digest_spans, digest_revisions, memory_provenance,"
          + " conversations, turns, compactions, entries, citations, orchestrations,"
          + " orchestration_messages, admins CASCADE";

  static final SwarmDefinitions.SwarmDefinition TWO =
      new SwarmDefinitions.SwarmDefinition(List.of("researcher", "critic"), 20, Map.of(), "test");

  final JdbcTemplate jdbc;
  final UnitOfWork work;
  final ConversationStore conversations;
  final FiringStore firings;
  final BoardStore store;
  final List<String> drained = new ArrayList<>();

  /** Strictly advancing, so minted ids sort by arrival (DispatcherTest's reason). */
  final Supplier<Instant> clock;

  BoardFixture(DriverManagerDataSource source) {
    jdbc = new JdbcTemplate(source);
    jdbc.execute(TRUNCATE);
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
    work =
        new io.aeyer.plowshare.server.archive.ArchiveConfig()
            .unitOfWork(new DataSourceTransactionManager(source));
    AtomicLong ticks = new AtomicLong();
    Instant start = Instant.parse("2026-09-30T09:00:00Z");
    clock = () -> start.plusMillis(ticks.getAndIncrement());
    conversations = new ConversationStore(jdbc);
    firings = new JdbcFiringStore(jdbc);
    store = new BoardStore(jdbc, clock);
  }

  Board board(SwarmDefinitions.SwarmDefinition swarm) {
    return new Board(
        store,
        project -> List.of(swarm),
        conversations,
        firings,
        drained::add,
        work,
        () -> 10,
        clock);
  }

  /** A topic a bot opened from its person's conversation in the project "payments". */
  Board.Opened openByBot(Board board) {
    String chat = conversations.open(Home.of("payments"), Budget.of(10)).id();
    return board.open(
        new Board.Open(
            Home.of("payments"),
            "sync between devices",
            "BAD SPEC / NEED INFO",
            "What does sync mean here?",
            "enzo",
            BoardTopic.BY_BOT,
            "aristoxenus",
            chat,
            null));
  }
}
