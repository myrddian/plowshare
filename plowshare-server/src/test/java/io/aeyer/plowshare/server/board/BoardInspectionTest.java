package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import io.aeyer.plowshare.server.ws.Asking;
import io.aeyer.plowshare.server.ws.BoardInspectionFrames;
import io.aeyer.plowshare.server.ws.FrameTypes;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("full-db")
@Testcontainers
class BoardInspectionTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  BoardFixture f;
  BoardInspectionFrames frames;
  BoardTopic topic;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void fresh() {
    f = new BoardFixture(source);
    var pools =
        new SwarmScheduler.Pools() {
          public List<String> serving(String specifier) {
            return List.of("test");
          }

          public int slots(String pool) {
            return 1;
          }

          public List<String> all() {
            return List.of("test");
          }
        };
    var scheduler =
        new SwarmScheduler(
            pools, () -> 4, () -> Duration.ofMinutes(10), Clock.systemUTC(), Duration.ofMillis(10));
    frames = new BoardInspectionFrames(f.store, scheduler);
    topic = f.openByBot(f.board(BoardFixture.TWO)).topic();
  }

  Object read(String type, Map<String, Object> body, String handle) throws Exception {
    return frames.frames().get(type).handle(body, new Asking("test", handle)).payload();
  }

  @Test
  void listing_and_detail_include_whole_messages_and_do_not_consume_agent_reads() throws Exception {
    var list =
        (BoardInspectionFrames.Topics) read(FrameTypes.BOARD_TOPICS, Map.of("limit", 1), "enzo");
    assertEquals(1, list.topics().size());
    assertFalse(list.more());
    assertEquals(1, list.topics().getFirst().messages());
    var child = f.store.openChild(topic, "Child question", "Research", "researcher");
    var request =
        f.store.post(
            new BoardStore.NewMessage(
                topic.id(),
                null,
                BoardMessage.BY_MEMBER,
                "researcher",
                f.store.seat(topic.id(), "researcher").orElseThrow().conversation(),
                null,
                BoardMessage.REQUEST,
                "Child question",
                "Whole body\n\n" + "x".repeat(5000),
                false,
                List.of("critic")));
    f.store.decide(request.id(), true, "Investigate", child.id());
    var before = f.store.seats(topic.id());
    var detail =
        (BoardInspectionFrames.Messages)
            read(FrameTypes.BOARD_MESSAGES, Map.of("topic", topic.id()), "enzo");
    assertEquals(topic, detail.root());
    assertEquals(request.body(), detail.messages().getLast().body());
    assertEquals(child.id(), detail.decisions().getFirst().child());
    assertEquals(before, f.store.seats(topic.id()));
    assertEquals(3, detail.seats().size());
    assertTrue(
        detail.seats().stream()
            .filter(s -> !s.seat().isOpener())
            .allMatch(s -> "owed".equals(s.state())));
    list = (BoardInspectionFrames.Topics) read(FrameTypes.BOARD_TOPICS, Map.of("limit", 1), "enzo");
    assertTrue(list.more());
    assertEquals(
        1,
        ((BoardInspectionFrames.Topics)
                read(FrameTypes.BOARD_TOPICS, Map.of("limit", 1, "offset", 1), "enzo"))
            .topics()
            .size());
  }

  @Test
  void account_scope_hides_topics_seats_and_queue_and_requires_signin() throws Exception {
    f.jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('other', 'h')");
    assertTrue(
        ((BoardInspectionFrames.Topics) read(FrameTypes.BOARD_TOPICS, Map.of(), "other"))
            .topics()
            .isEmpty());
    var swarm = (BoardInspectionFrames.Swarm) read(FrameTypes.SWARM_STATUS, Map.of(), "other");
    assertTrue(swarm.topics().isEmpty());
    assertTrue(swarm.seats().isEmpty());
    assertTrue(swarm.ready().isEmpty());
    assertEquals(1, swarm.pools().size());
    var denied =
        assertThrows(
            CallerFault.class,
            () -> read(FrameTypes.BOARD_MESSAGES, Map.of("topic", topic.id()), "other"));
    var missing =
        assertThrows(
            CallerFault.class,
            () -> read(FrameTypes.BOARD_MESSAGES, Map.of("topic", "missing"), "other"));
    assertEquals(denied.getMessage(), missing.getMessage());
    for (String type : frames.frames().keySet())
      assertThrows(CallerFault.class, () -> read(type, Map.of(), null));
    assertThrows(
        CallerFault.class, () -> read(FrameTypes.BOARD_TOPICS, Map.of("limit", 201), "enzo"));
  }

  @Test
  void live_firings_and_exhausted_allowance_have_distinct_states() throws Exception {
    var seat = f.store.seat(topic.id(), "researcher").orElseThrow();
    var firing = f.firings.oldestWaiting("conversation:" + seat.conversation()).orElseThrow();
    f.firings.claimStart(firing.id(), f.clock.get());
    f.firings.startedAs(firing.id(), "job-inspect");
    f.firings.owe(topic.id(), "conversation:" + seat.conversation(), "{}", f.clock.get());
    var swarm = (BoardInspectionFrames.Swarm) read(FrameTypes.SWARM_STATUS, Map.of(), "enzo");
    assertEquals(
        "running",
        swarm.seats().stream()
            .filter(s -> s.seat().equals(seat))
            .findFirst()
            .orElseThrow()
            .state());
    assertEquals(
        "job-inspect",
        swarm.seats().stream().filter(s -> s.seat().equals(seat)).findFirst().orElseThrow().job());
    f.firings.finish(firing.id(), f.clock.get());
    f.store.spend(topic.id(), 18);
    swarm = (BoardInspectionFrames.Swarm) read(FrameTypes.SWARM_STATUS, Map.of(), "enzo");
    assertEquals(
        "held",
        swarm.seats().stream()
            .filter(s -> s.seat().equals(seat))
            .findFirst()
            .orElseThrow()
            .state());
    f.store.close(topic.id(), "Done");
    assertTrue(
        ((BoardInspectionFrames.Swarm) read(FrameTypes.SWARM_STATUS, Map.of(), "enzo"))
            .topics()
            .isEmpty());
    assertEquals(
        1,
        ((BoardInspectionFrames.Topics) read(FrameTypes.BOARD_TOPICS, Map.of(), "enzo"))
            .topics()
            .size());
  }

  @Test
  void scheduler_queue_details_are_account_scoped_and_joined_to_their_seat() throws Exception {
    var scheduler = org.mockito.Mockito.mock(SwarmScheduler.class);
    org.mockito.Mockito.when(scheduler.snapshot())
        .thenReturn(
            new SwarmScheduler.Snapshot(
                List.of(new SwarmScheduler.PoolUse("shared", 3, 2)),
                List.of(
                    new SwarmScheduler.Waiting(
                        new SwarmScheduler.Share("other", "foreign-topic", "private-member"),
                        "private-model",
                        1,
                        Duration.ofSeconds(20),
                        false),
                    new SwarmScheduler.Waiting(
                        new SwarmScheduler.Share("enzo", topic.id(), "researcher"),
                        "reasoning",
                        2,
                        Duration.ofMinutes(11),
                        true))));
    frames = new BoardInspectionFrames(f.store, scheduler);
    var swarm = (BoardInspectionFrames.Swarm) read(FrameTypes.SWARM_STATUS, Map.of(), "enzo");
    assertEquals(1, swarm.ready().size());
    assertEquals(2, swarm.ready().getFirst().position());
    assertEquals("researcher", swarm.ready().getFirst().member());
    var seat =
        swarm.seats().stream()
            .filter(v -> v.seat().occupant().equals("researcher"))
            .findFirst()
            .orElseThrow();
    assertEquals("ready", seat.state());
    assertTrue(seat.overdue());
    assertEquals(660000L, seat.waitedMillis());
    assertEquals(2, swarm.pools().getFirst().used());
  }
}
