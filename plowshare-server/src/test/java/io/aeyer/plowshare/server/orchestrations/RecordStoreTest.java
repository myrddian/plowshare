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
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore.NewOrchestration;
import io.aeyer.plowshare.server.todos.StageRules;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The record's store against a real Postgres: numbering, one-line rows, settling, reads, trees. */
@Testcontainers
class RecordStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final Instant T0 = Instant.parse("2026-09-28T09:00:00Z");
    private static final List<StageRules.Stage> STAGES =
            List.of(new StageRules.Stage("goal", List.of()));

    private static JdbcTemplate jdbc;
    private static UnitOfWork work;

    private RecordStore records;
    private OrchestrationStore runs;
    private ConversationStore conversations;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        // The store's appends each take a transaction: one DataSource under the JdbcTemplate and
        // the transaction manager both, as OrchestrationsTest builds its UnitOfWork.
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(ds));
        work = new UnitOfWork() {
            @Override
            public <T> T inTransaction(Supplier<T> body) {
                return template.execute(status -> body.get());
            }
        };
    }

    @BeforeEach
    void fresh() {
        jdbc.execute("TRUNCATE TABLE orchestration_record, orchestration_messages, orchestrations,"
                + " entries, turns, conversations, admins CASCADE");
        jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
        records = new RecordStore(jdbc, () -> T0, work);
        runs = new OrchestrationStore(jdbc, () -> T0);
        conversations = new ConversationStore(jdbc, () -> T0, null);
    }

    private String conductor() {
        return conversations.log(Origin.ORCHESTRATION, Home.of("story"), "code_implementation",
                null, Budget.of(40)).id();
    }

    private OrchestrationRecord run(String conductor, String parent, int depth) {
        return runs.insert(new NewOrchestration("code_implementation", Tier.PROJECT, "sha256:x",
                "---\nname: code_implementation\n---\nbody", "project", STAGES, 2, "story",
                conductor, null, "code_implementation", "enzo", "s-laptop", parent, depth));
    }

    private static List<Integer> ordinals(RecordPage page) {
        return page.rows().stream().map(RecordRow::ordinal).toList();
    }

    @Test
    void numbers_each_tree_from_one_and_keeps_trees_apart() {
        assertEquals(1, records.append("orc_a", "orc_a", "conductor", RecordKind.RUN_STARTED,
                "a started", null));
        assertEquals(2, records.append("orc_a", "orc_a", "conductor", RecordKind.STAGE_MOVED,
                "goal: pending → in_progress", null));
        assertEquals(1, records.append("orc_b", "orc_b", "conductor", RecordKind.RUN_STARTED,
                "b started", null));
        assertEquals(2, records.through("orc_a"));
        assertEquals(0, records.through("orc_nothing"));
    }

    @Test
    void a_row_is_one_line_however_it_was_written() {
        records.append("orc_a", "orc_a", "coder", RecordKind.TOOL_CALL,
                "coder · run\n./gradlew\ttest", "x".repeat(900));

        RecordRow row = records.pageAfter("orc_a", 0, RecordKind.EVERY, 10).rows().get(0);
        assertEquals("coder · run ./gradlew test", row.text());
        assertEquals(400, row.detail().length());
        assertTrue(row.detail().endsWith("…"));
        assertEquals(T0, row.at());
    }

    /** V64: a row a person reads whole keeps its whole text beside its one line, newlines and
     *  all; every row written without one has none. */
    @Test
    void a_body_is_kept_whole_beside_the_line_and_is_null_unless_given() {
        records.append("orc_a", "orc_a", "conductor", RecordKind.QUESTION_ASKED,
                "asked: Which database?", null, null, "  Which database?\n\nPostgres or SQLite  ");
        records.append("orc_a", "orc_a", "conductor", RecordKind.STALLED, "quiet", null);
        records.append("orc_a", "orc_a", "conductor", RecordKind.STALLED, "quiet", null, null,
                " \n ");

        List<RecordRow> rows = records.pageAfter("orc_a", 0, RecordKind.EVERY, 10).rows();
        assertEquals("asked: Which database?", rows.get(0).text());
        assertEquals("Which database?\n\nPostgres or SQLite", rows.get(0).body());
        assertNull(rows.get(1).body(), "the overloads without a body write none");
        assertNull(rows.get(2).body(), "a blank body is no body");
    }

    @Test
    void a_body_past_its_bound_is_cut_as_a_line_is() {
        records.append("orc_a", "orc_a", "conductor", RecordKind.RUN_ENDED, "done", null, null,
                "y".repeat(20_000));

        String body = records.pageAfter("orc_a", 0, RecordKind.EVERY, 10).rows().get(0).body();
        assertEquals(RecordStore.MOST_BODY, body.length());
        assertTrue(body.endsWith("…"));
    }

    /** The table holds the bound itself, for a writer that is not this store. */
    @Test
    void the_table_refuses_an_empty_or_overlong_body() {
        for (String body : List.of("", "z".repeat(16_001))) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "INSERT INTO orchestration_record (root, ordinal, at, run, actor, kind, text,"
                            + " body) VALUES ('orc_x', 1, now(), 'orc_x', 'conductor', 'stalled',"
                            + " 'quiet', ?)", body), "a body of " + body.length());
        }
    }

    /**
     * Many writers at once on one tree, far more than the retry alone could absorb — it could
     * not, measured, with two: a row was lost one run in a few. Every append must land, each with
     * its own number and none skipped, and a writer's failure must fail this test rather than be
     * lost in its thread. A second tree written at the same time is numbered apart.
     */
    @Test
    void many_writers_racing_on_one_tree_never_share_an_ordinal_or_lose_a_row() throws Exception {
        int writers = 8;
        int each = 50;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> written = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> done = new ArrayList<>();
        for (int writer = 0; writer < writers; writer++) {
            done.add(pool.submit(() -> {
                go.await();
                for (int n = 0; n < each; n++) {
                    written.add(records.append("orc_race", "orc_race", "coder",
                            RecordKind.TOOL_CALL, "coder · file_read", null));
                }
                return null;
            }));
        }
        done.add(pool.submit(() -> {
            go.await();
            for (int n = 0; n < each; n++) {
                records.append("orc_other", "orc_other", "coder", RecordKind.TOOL_CALL,
                        "coder · file_read", null);
            }
            return null;
        }));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));
        for (Future<?> writer : done) {
            writer.get();
        }

        assertEquals(IntStream.rangeClosed(1, writers * each).boxed().toList(),
                written.stream().sorted().toList());
        assertEquals(writers * each, records.through("orc_race"));
        assertEquals(each, records.through("orc_other"));
    }

    @Test
    void a_tool_line_is_settled_once_and_a_milestone_never() {
        int call = records.append("orc_a", "orc_a", "coder", RecordKind.TOOL_CALL,
                "coder · run ./gradlew test", null);
        int moved = records.append("orc_a", "orc_a", "conductor", RecordKind.STAGE_MOVED,
                "code: pending → in_progress", null);

        assertEquals(OptionalInt.of(moved), records.settle("orc_a", call, "exit 1"),
                "set, naming the record's highest ordinal in the same statement");
        assertEquals(OptionalInt.empty(), records.settle("orc_a", call, "ok"), "settled already");
        assertEquals(OptionalInt.empty(), records.settle("orc_a", moved, "ok"),
                "a milestone has no outcome");
        assertEquals(List.of("exit 1", "null"), records.pageAfter("orc_a", 0, RecordKind.EVERY,
                10).rows().stream().map(row -> String.valueOf(row.detail())).toList());
    }

    @Test
    void a_tool_line_keeps_the_output_it_was_settled_with_as_its_body() {
        int call = records.append("orc_b", "orc_b", "coder", RecordKind.TOOL_CALL,
                "coder · run make test", null);
        int quiet = records.append("orc_b", "orc_b", "coder", RecordKind.TOOL_CALL,
                "coder · file_read a.py", null);

        records.settle("orc_b", call, "exit 2", "--- stdout ---\n1 failed");
        records.settle("orc_b", quiet, "ok", null);

        assertEquals(List.of("--- stdout ---\n1 failed", "null"), records.pageAfter("orc_b", 0,
                RecordKind.EVERY, 10).rows().stream().map(row -> String.valueOf(row.body()))
                .toList());
    }

    @Test
    void reads_forward_after_an_ordinal_and_backwards_from_the_end() {
        for (int n = 1; n <= 5; n++) {
            records.append("orc_a", "orc_a", "conductor", RecordKind.STAGE_MOVED, "line " + n,
                    null);
        }

        RecordPage after = records.pageAfter("orc_a", 3, RecordKind.EVERY, 10);
        assertEquals(List.of(4, 5), ordinals(after));
        assertEquals(2, after.total());
        assertEquals(5, after.through());
        assertNull(after.more());

        RecordPage tail = records.pageBefore("orc_a", RecordStore.FROM_THE_END, RecordKind.EVERY,
                2);
        assertEquals(List.of(5, 4), ordinals(tail), "newest first");
        assertEquals(4, tail.oldest());
        assertEquals(Boolean.TRUE, tail.more());
        assertEquals(3, tail.total(), "a full page counts one row past itself, not the record");

        RecordPage earlier = records.pageBefore("orc_a", 2, RecordKind.EVERY, 10);
        assertEquals(List.of(1), ordinals(earlier));
        assertEquals(Boolean.FALSE, earlier.more());
    }

    @Test
    void narrows_the_rows_and_the_count_to_the_kinds_asked_for() {
        records.append("orc_a", "orc_a", "conductor", RecordKind.RUN_STARTED, "started", null);
        records.append("orc_a", "orc_a", "coder", RecordKind.TOOL_CALL, "coder · file_read",
                null);
        records.append("orc_a", "orc_a", "conductor", RecordKind.STAGE_MOVED, "moved", null);

        RecordPage milestones = records.pageBefore("orc_a", RecordStore.FROM_THE_END,
                RecordKind.MILESTONES, 10);
        assertEquals(List.of(3, 1), ordinals(milestones));
        assertEquals(2, milestones.total());
        assertEquals(3, milestones.through(), "through is the whole record's");

        RecordPage tools = records.pageAfter("orc_a", 0, Set.of(RecordKind.TOOL_CALL), 10);
        assertEquals(List.of(2), ordinals(tools));
    }

    @Test
    void a_child_run_resolves_to_its_root_and_the_root_s_account() {
        OrchestrationRecord root = run(conductor(), null, 0);
        OrchestrationRecord child = run(conductor(), root.id(), 1);

        assertEquals(Optional.of(new RecordStore.Tree(root.id(), "enzo")),
                records.treeOfRun(child.id()));
        assertEquals(Optional.of(new RecordStore.Tree(root.id(), "enzo")),
                records.treeOfRun(root.id()));
        assertEquals(Optional.empty(), records.treeOfRun("orc_nothing"));
    }

    @Test
    void a_conversation_is_placed_in_the_run_it_works_for() {
        OrchestrationRecord root = run(conductor(), null, 0);
        OrchestrationRecord child = run(conductor(), root.id(), 1);
        String coder = conversations.log(Origin.DELEGATION, Home.of("story"), "coder",
                child.conductorConversation(), null).id();
        String loose = conversations.log(Origin.ORCHESTRATION, Home.of("story"), "coder", null,
                Budget.of(5)).id();

        assertEquals(Optional.of(new RecordStore.Place(root.id(), "enzo", child.id(), false)),
                records.placeOf(coder));
        assertEquals(Optional.of(new RecordStore.Place(root.id(), "enzo", child.id(), true)),
                records.placeOf(child.conductorConversation()));
        assertEquals(Optional.of(new RecordStore.Place(root.id(), "enzo", root.id(), true)),
                records.placeOf(root.conductorConversation()));
        assertEquals(Optional.empty(), records.placeOf(loose));
        assertEquals(Optional.empty(), records.placeOf("cnv_nothing"));
    }

    /** V60: {@code acceptance_ran} and {@code cap_continued} are known kinds now — the V59
     *  constraint refuses them until the migration is applied. */
    @Test
    void v60_s_two_new_kinds_are_accepted() {
        OrchestrationRecord root = run(conductor(), null, 0);

        records.append(root.id(), root.id(), "conductor", RecordKind.ACCEPTANCE_RAN,
                "acceptance ran: 2/2 commands passed", null);
        records.append(root.id(), root.id(), "conductor", RecordKind.CAP_CONTINUED,
                "cap continued (1/3)", "turn cap");

        assertEquals(List.of(RecordKind.ACCEPTANCE_RAN, RecordKind.CAP_CONTINUED),
                records.pageAfter(root.id(), 0, RecordKind.EVERY, 10).rows().stream()
                        .map(RecordRow::kind).toList());
    }

    /** V77: {@code concern} is a known kind — the acceptance checker's lines of the story. */
    @Test
    void v77_s_concern_kind_is_accepted() {
        OrchestrationRecord root = run(conductor(), null, 0);

        records.append(root.id(), root.id(), "acceptance_checker", RecordKind.CONCERN,
                "concern c1 raised: nothing starts the game", null);

        assertEquals(List.of(RecordKind.CONCERN),
                records.pageAfter(root.id(), 0, RecordKind.EVERY, 10).rows().stream()
                        .map(RecordRow::kind).toList());
    }

    @Test
    void reads_back_the_detail_of_a_run_s_first_row_of_a_kind() {
        records.append("orc_a", "orc_child", "conductor", RecordKind.PHASE_STARTED,
                "phase 02-parser started: orc_child (code_implementation)", "02-parser");

        assertEquals(Optional.of("02-parser"),
                records.detailOf("orc_a", "orc_child", RecordKind.PHASE_STARTED));
        assertEquals(Optional.empty(),
                records.detailOf("orc_a", "orc_other", RecordKind.PHASE_STARTED));
    }
}
