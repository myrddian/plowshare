package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore.NewOrchestration;
import io.aeyer.plowshare.server.todos.StageRules;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The read both surfaces share: windows, kinds, reach, the root, and whose record it is. */
@Tag("full-db")
@Testcontainers
class RecordReadsTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-09-28T09:00:00Z");

  private static JdbcTemplate jdbc;

  private RecordStore records;
  private OrchestrationStore runs;
  private ConversationStore conversations;
  private RecordReads reads;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc = new JdbcTemplate(ds);
  }

  @BeforeEach
  void fresh() {
    jdbc.execute(
        "TRUNCATE TABLE orchestration_record, orchestration_messages, orchestrations,"
            + " entries, turns, conversations, admins CASCADE");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
    // Sequential appends within one test, so the real advisory-lock transaction RecordStore
    // asks for (RecordStoreTest's own setup) buys nothing here; RecordReads reads what
    // records writes and neither races the other.
    records = new RecordStore(jdbc, () -> T0, UnitOfWork.NONE);
    runs = new OrchestrationStore(jdbc, () -> T0);
    conversations = new ConversationStore(jdbc, () -> T0, null);
    reads = new RecordReads(records);
  }

  private OrchestrationRecord run(String parent, int depth) {
    String conductor =
        conversations
            .log(Origin.ORCHESTRATION, Home.of("story"), "code_implementation", null, Budget.of(40))
            .id();
    return runs.insert(
        new NewOrchestration(
            "code_implementation",
            Tier.PROJECT,
            "sha256:x",
            "---\nname: code_implementation\n---\nbody",
            "project",
            List.of(new StageRules.Stage("goal", List.of())),
            2,
            "story",
            conductor,
            null,
            "code_implementation",
            "enzo",
            "s-laptop",
            parent,
            depth));
  }

  /** Five rows: odd ordinals are stage moves, even ones tool lines. */
  private OrchestrationRecord recorded() {
    OrchestrationRecord root = run(null, 0);
    for (int n = 1; n <= 5; n++) {
      records.append(
          root.id(),
          root.id(),
          "conductor",
          n % 2 == 0 ? RecordKind.TOOL_CALL : RecordKind.STAGE_MOVED,
          "line " + n,
          null);
    }
    return root;
  }

  private static List<Integer> ordinals(RecordReads.Read read) {
    return read.page().rows().stream().map(RecordRow::ordinal).toList();
  }

  @Test
  void reads_the_tail_newest_first_then_earlier_then_what_came_after() {
    OrchestrationRecord root = recorded();

    RecordReads.Read tail = reads.read("enzo", root.id(), null, null, true, 2, null);
    assertEquals(List.of(5, 4), ordinals(tail));
    assertEquals(5, tail.page().through());
    assertEquals(4, tail.page().oldest());
    assertEquals(Boolean.TRUE, tail.page().more());
    assertEquals(2, tail.limit());

    assertEquals(List.of(3, 2), ordinals(reads.read("enzo", root.id(), null, 4, null, 2, null)));

    RecordReads.Read after = reads.read("enzo", root.id(), 3, null, null, null, null);
    assertEquals(List.of(4, 5), ordinals(after));
    assertNull(after.page().more());
    assertEquals(100, after.limit(), "the page cap every log read has");
  }

  @Test
  void narrows_to_the_kinds_asked_for_in_the_rows_and_the_count() {
    OrchestrationRecord root = recorded();

    RecordReads.Read milestones =
        reads.read("enzo", root.id(), null, null, true, null, List.of("stage_moved"));

    assertEquals(List.of(5, 3, 1), ordinals(milestones));
    assertEquals(3, milestones.page().total());
    assertEquals(5, milestones.page().through());
  }

  @Test
  void any_run_in_the_tree_reads_the_root_s_record() {
    OrchestrationRecord root = recorded();
    OrchestrationRecord child = run(root.id(), 1);

    RecordReads.Read read = reads.read("enzo", child.id(), null, null, true, null, null);

    assertEquals(root.id(), read.root());
    assertEquals(List.of(5, 4, 3, 2, 1), ordinals(read));
  }

  @Test
  void a_run_of_another_account_reads_as_one_that_does_not_exist() {
    OrchestrationRecord root = recorded();

    CallerFault theirs =
        assertThrows(
            CallerFault.class, () -> reads.read("ada", root.id(), null, null, true, null, null));
    CallerFault missing =
        assertThrows(
            CallerFault.class,
            () -> reads.read("enzo", "orc_nothing", null, null, true, null, null));

    assertEquals("No orchestration with that id is owned by this account.", theirs.getMessage());
    assertEquals(theirs.getMessage(), missing.getMessage());
  }

  @Test
  void refuses_an_unknown_kind_two_directions_and_no_account() {
    OrchestrationRecord root = recorded();

    assertTrue(
        assertThrows(
                CallerFault.class,
                () -> reads.read("enzo", root.id(), null, null, true, null, List.of("tool_calls")))
            .getMessage()
            .contains("no record kind is spelled 'tool_calls'"));
    assertThrows(CallerFault.class, () -> reads.read("enzo", root.id(), 2, 4, null, null, null));
    assertThrows(
        CallerFault.class, () -> reads.read(null, root.id(), null, null, true, null, null));
  }
}
