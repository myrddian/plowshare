package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.ws.Asking;
import io.aeyer.plowshare.server.ws.BoardPostingFrames;
import io.aeyer.plowshare.server.ws.FrameTypes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real JSONB mapping, transactional snapshots and durable receipt identity require PostgreSQL. */
@Tag("full-db")
@Testcontainers
class NamedSwarmPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  BoardFixture f;
  AtomicReference<List<SwarmDefinitions.SwarmDefinition>> definitions;
  Board board;
  BoardPostingFrames frames;

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
    definitions =
        new AtomicReference<>(
            List.of(
                definition("privacy", "researcher", "a"), definition("incident", "critic", "b")));
    board = service();
    f.conversations.open(
        io.aeyer.plowshare.protocol.Home.of("payments"),
        io.aeyer.plowshare.server.agents.Budget.of(10));
    var members = new JdbcProjectMembers(f.jdbc);
    members.add("payments", "enzo");
    frames =
        new BoardPostingFrames(
            board, f.store, members, f.work, new JdbcBoardPostRepository(f.jdbc));
  }

  private SwarmDefinitions.SwarmDefinition definition(String name, String member, String revision) {
    var selection =
        new SwarmSelection(name, revision.repeat(64), "Description", List.of(member), 20);
    return new SwarmDefinitions.SwarmDefinition(
        name, selection.members(), 20, Map.of(), "swarm/" + name + ".md", selection);
  }

  private Board service() {
    return new Board(
        f.store,
        project -> definitions.get(),
        f.conversations,
        f.firings,
        f.drained::add,
        f.work,
        () -> 10,
        f.clock);
  }

  private BoardPostingFrames.OpenReceipt open(UUID request, String swarm) throws Exception {
    return (BoardPostingFrames.OpenReceipt)
        frames
            .frames()
            .get(FrameTypes.BOARD_OPEN)
            .handle(
                Map.of(
                    "project",
                    "payments",
                    "swarm",
                    swarm,
                    "title",
                    "Review",
                    "label",
                    "Discussion",
                    "body",
                    "Review this evidence",
                    "requestId",
                    request.toString()),
                new Asking("test", "enzo"))
            .payload();
  }

  @Test
  void roots_and_children_retain_the_selected_definition_across_edits_and_service_restart()
      throws Exception {
    var privacy = open(UUID.randomUUID(), "privacy");
    var incident = open(UUID.randomUUID(), "incident");
    assertEquals(List.of("researcher"), privacy.topic().swarm().members());
    assertEquals(List.of("critic"), incident.topic().swarm().members());
    assertEquals(
        List.of("researcher"),
        f.store.seats(privacy.topic().id()).stream().map(BoardSeat::occupant).toList());
    var child =
        f.work.inTransaction(
            () -> f.store.openChild(privacy.topic(), "Child", "Discussion", "researcher"));
    definitions.set(List.of(definition("privacy", "critic", "c")));
    var reloaded = new BoardStore(f.jdbc, f.clock).topic(child.id()).orElseThrow();
    assertEquals(privacy.topic().swarm(), reloaded.swarm());
    board = service();
    assertThrows(
        Board.Refused.class,
        () ->
            board.post(
                new Board.Post(
                    privacy.topic().id(),
                    "person",
                    "enzo",
                    null,
                    null,
                    "post",
                    null,
                    "Changed membership?",
                    null,
                    List.of("critic"),
                    false)));
    assertEquals(
        List.of("researcher"),
        board
            .post(
                new Board.Post(
                    privacy.topic().id(),
                    "person",
                    "enzo",
                    null,
                    null,
                    "post",
                    null,
                    "Continue",
                    null,
                    List.of("researcher"),
                    false))
            .woken()
            .stream()
            .map(WakeRules.Wake::occupant)
            .toList());
  }

  @Test
  void identical_receipt_recovery_survives_deleted_configuration_but_changed_type_is_refused()
      throws Exception {
    UUID request = UUID.randomUUID();
    var receipt = open(request, "privacy");
    int wakes = f.drained.size();
    definitions.set(List.of());
    assertEquals(receipt, open(request, "privacy"));
    assertEquals(wakes, f.drained.size());
    assertThrows(CallerFault.class, () -> open(request, "incident"));
    assertThrows(Board.Refused.class, () -> open(UUID.randomUUID(), "privacy"));
  }

  @Test
  void retry_uses_retained_members_and_budget_after_the_type_changes() throws Exception {
    var opened = open(UUID.randomUUID(), "privacy");
    var before = f.store.seat(opened.topic().id(), "researcher").orElseThrow();
    f.store.recordFailure(opened.topic().id(), "researcher", "TURN_CAP");
    definitions.set(List.of(definition("privacy", "critic", "c")));
    board = service();
    var retry = board.retry(opened.topic().id(), "researcher", "enzo", 12);
    assertEquals(List.of("researcher"), retry.message().mentions());
    assertEquals(
        before.conversation(),
        f.store.seat(opened.topic().id(), "researcher").orElseThrow().conversation());
    assertEquals(opened.topic().swarm(), f.store.topic(opened.topic().id()).orElseThrow().swarm());
    assertEquals(
        opened.topic().potTotal(), f.store.topic(opened.topic().id()).orElseThrow().potTotal());
  }
}
