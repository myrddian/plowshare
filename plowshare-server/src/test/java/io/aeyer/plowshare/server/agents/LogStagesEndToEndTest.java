package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.LocalHookSetStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.events.InboxItem;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.hooks.ApprovalAnswer;
import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.Handover;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.StageDone;
import io.aeyer.plowshare.server.hooks.StageShown;
import io.aeyer.plowshare.server.hooks.StageStart;
import io.aeyer.plowshare.server.hooks.Summarised;
import io.aeyer.plowshare.server.hooks.Tier;
import io.aeyer.plowshare.server.hooks.script.HookEngine;
import io.aeyer.plowshare.server.hooks.script.HooksProperties;
import io.aeyer.plowshare.server.hooks.script.ScriptHooks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spec 2026-09-28-hooks-reach-the-log, slices 1 to 4, over real swc4j, GraalJS and Postgres: a
 * project's TypeScript hook file shapes a log's opening, notes its delivery and notifies its owner
 * (slice 1); gates a stage's start and its end inside the run's turn (slice 2); gates a question
 * before a person is asked and is told of the answer (slice 3); and reads a fold's summary, keeping
 * a marker only when the folder lost it (slice 4, amended 2026-09-29). Every decision is a HOOK
 * entry in the log it was taken for, read back here from the archive.
 */
@Testcontainers
class LogStagesEndToEndTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final Home LEDGER = Home.of("ledger");

    private static final String HOUSE = """
            import type { Hook } from '@plowshare/hooks'

            export default {
                name: 'house',
                stages: {
                    'log.open': {
                        origins: ['submission'],
                        handle(e) { return { add: 'House rules for ' + e.context.agent + '.' } },
                    },
                    'log.close': {
                        handle(e) { return { notify: e.context.log + ' ended ' + e.ending } },
                    },
                    'delivery.pre': {
                        handle(e) { return { note: '(from ' + e.source.origin + ')' } },
                    },
                },
            } satisfies Hook
            """;

    private static JdbcTemplate jdbc;

    @TempDir
    Path data;

    private ConversationStore conversations;
    private TurnStore turns;
    private EntryStore entries;
    private InboxStore inbox;
    private HookEngine engine;
    private ScriptHooks hooks;
    private HookedLogStages stages;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void fresh() throws Exception {
        jdbc.execute("TRUNCATE TABLE user_inbox, entries, turns, conversations, admins CASCADE");
        jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
        conversations = new ConversationStore(jdbc);
        turns = new TurnStore(jdbc);
        entries = new EntryStore(jdbc);
        inbox = new InboxStore(jdbc);
        Path directory = data.resolve("projects").resolve("7").resolve("hooks");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("10-house.ts"), HOUSE);
        engine = new HookEngine();
        HooksProperties properties = new HooksProperties();
        properties.setTimeout(Duration.ofSeconds(2));
        hooks = new ScriptHooks(name -> "ledger".equals(name) ? 7L : null,
                id -> data.resolve("projects").resolve(Long.toString(id)).resolve("hooks"),
                engine, properties, Instant::now);
        stages = new HookedLogStages(hooks, conversations, turns, conversation -> false, entries,
                (handle, kind, text) -> inbox.notice(handle, kind, text, Instant.now()),
                name -> false, Instant::now, properties::getTimeout, new WordsTokenizer());
    }

    @AfterEach
    void close() {
        hooks.close();
        engine.close();
    }

    @Test
    void a_project_hook_shapes_a_submission_s_log_from_open_to_delivery() {
        String log = conversations.log(Origin.SUBMISSION, LEDGER, "scribe", null, Budget.of(5),
                "enzo").id();

        stages.opened(new LogStages.LogOpened(log, Origin.SUBMISSION, LEDGER, "scribe", false, null));
        turns.record(log, "hi", "hello", Outcome.Ending.ANSWERED, null, "scribe", null);
        stages.closed(log, "answered");
        String delivered = stages.deliveryPre(log, Handover.INBOX, "hello");

        assertEquals(Optional.of("House rules for scribe."), conversations.opening(log));
        assertEquals("hello\n\n(from submission)", delivered);
        List<InboxItem> told = inbox.list("enzo", false, 0, 10);
        assertEquals(List.of("house: " + log + " ended answered"),
                told.stream().map(InboxItem::answer).toList());
        assertEquals(List.of(InboxStore.KIND_HOOK), told.stream().map(InboxItem::kind).toList());
        List<String> decisions = entries.forConversation(log).stream()
                .filter(entry -> entry.kind() == EntryKind.HOOK).map(EntryRecord::content).toList();
        assertEquals(3, decisions.size(), decisions.toString());
        assertTrue(decisions.get(0).contains("\"stage\":\"log.open\"")
                && decisions.get(0).contains("\"decision\":\"add\""), decisions.get(0));
        assertTrue(decisions.get(1).contains("\"stage\":\"log.close\"")
                && decisions.get(1).contains("\"decision\":\"notify\""), decisions.get(1));
        assertTrue(decisions.get(2).contains("\"stage\":\"delivery.pre\"")
                && decisions.get(2).contains("\"decision\":\"note\""), decisions.get(2));
    }

    /** Decision 6: a hook naming origins: ['submission'] does not fire for a delegation log. */
    @Test
    void a_hook_that_names_submission_does_not_open_a_delegated_child() {
        String parent = conversations.log(Origin.SUBMISSION, LEDGER, "scribe", null, Budget.of(5),
                "enzo").id();
        String child = conversations.log(Origin.DELEGATION, LEDGER, "scribe", parent, null).id();

        stages.opened(new LogStages.LogOpened(child, Origin.DELEGATION, LEDGER, "scribe", false,
                parent));

        assertEquals(Optional.empty(), conversations.opening(child));
        assertTrue(entries.forConversation(child).isEmpty(),
                "a hook not called for this origin records nothing");
    }

    // --- slices 2–4 ------------------------------------------------------------------------

    private static final String GATES = """
            import type { Hook } from '@plowshare/hooks'

            export default {
                name: 'gates',
                stages: {
                    'stage.pre': {
                        origins: ['orchestration'],
                        handle(e) {
                            return e.returning && e.returnsLeft === 0
                                ? { deny: 'no returns left after ' + e.stage.id }
                                : { note: 'starting: ' + e.stage.title }
                        },
                    },
                    'stage.post': {
                        origins: ['orchestration'],
                        handle(e) {
                            return e.summary.includes('.py')
                                ? { note: e.stage.id + ' names its files' }
                                : { deny: 'say which files changed' }
                        },
                    },
                    'approval.pre': {
                        handle(e) {
                            return e.argv[0] === 'rm'
                                ? { deny: 'never rm' }
                                : { note: 'assessed on the ' + e.context.environment.side + ' side' }
                        },
                    },
                    'approval.post': {
                        handle(e) { return { notify: e.approval + ' ' + e.decision + ' ' + e.scope } },
                    },
                    'fold.post': {
                        handle(e) {
                            return e.summary.includes('skill X@1')
                                ? { notify: 'the folder kept skill X@1 through turn ' + e.through }
                                : { keep: 'skill X@1 was loaded before turn ' + e.through }
                        },
                    },
                },
            } satisfies Hook
            """;

    private void gates() throws Exception {
        Files.writeString(data.resolve("projects").resolve("7").resolve("hooks")
                .resolve("20-gates.ts"), GATES);
    }

    /**
     * Spec 2026-09-28-hooks-reach-the-log §3, slices 2 and 3: the in-turn gates, in TypeScript. Each
     * gate's records are parked as the seams park them and written as the run loop writes them, so
     * what is asserted last is what the conductor's log holds.
     */
    @Test
    void a_project_hook_gates_a_stage_and_a_question_inside_the_run_s_turn() throws Exception {
        gates();
        String conductor = conversations.log(Origin.ORCHESTRATION, LEDGER, "conductor", null,
                Budget.of(5), "enzo").id();
        InTurnHooks run = new InTurnHooks(new HookContext("conductor", true, Set.of("todo_write"),
                "ledger", conductor, HookContext.SERVER).inLog("orchestration"), () -> hooks);
        HookContext.Orchestration about =
                new HookContext.Orchestration("orc_1", "code_implementation", "code");
        StageShown code = new StageShown("code", "write the code", 1, 3);
        HookContext.RunEnvironment server = new HookContext.RunEnvironment("server", "ask", false,
                "none");

        Gate starting = run.stagePre(about, new StageStart(code, false, 2));
        Gate lastReturn = run.stagePre(about, new StageStart(code, true, 0));
        Gate named = run.stagePost(about, new StageDone(code, "wrote main.py", null));
        Gate vague = run.stagePost(about, new StageDone(code, "done", null));
        Gate removing = run.approvalPre(server, null, new Approving(List.of("rm", "-rf", "build"),
                "/repo", null, true, List.of("once", "conversation", "project")));
        Gate making = run.approvalPre(server, null, new Approving(List.of("make"), "/repo", null,
                true, List.of("once", "conversation", "project")));

        assertEquals(List.of("starting: write the code"), starting.notes());
        assertEquals("'gates': no returns left after code", lastReturn.denied());
        assertEquals(List.of("code names its files"), named.notes());
        assertEquals("'gates': say which files changed", vague.denied());
        assertEquals("'gates': never rm", removing.denied());
        assertEquals("assessed on the server side", making.applyTo(null));
        assertOneProjectRecord(named, Stage.STAGE_POST, HookRecord.NOTE);
        assertOneProjectRecord(removing, Stage.APPROVAL_PRE, HookRecord.DENY);
        assertOneProjectRecord(starting, Stage.STAGE_PRE, HookRecord.NOTE);
        assertOneProjectRecord(lastReturn, Stage.STAGE_PRE, HookRecord.DENY);

        // Parked as StageHooks and the approval seams park them; drained into the log as
        // JobRuntime writes a tool call's records (Transcript.record of LoggedEntry.hook).
        for (Gate gate : List.of(starting, lastReturn, named, vague, removing, making)) {
            run.recorded(gate);
        }
        run.drain().forEach(record -> entries.append(conductor, 1, LoggedEntry.hook(record)));
        List<String> logged = entries.forConversation(conductor).stream()
                .filter(entry -> entry.kind() == EntryKind.HOOK).map(EntryRecord::content).toList();
        assertEquals(List.of(
                "stage.pre note", "stage.pre deny", "stage.post note", "stage.post deny",
                "approval.pre deny", "approval.pre note"),
                logged.stream().map(LogStagesEndToEndTest::stageAndDecision).toList(),
                logged.toString());
        assertTrue(logged.stream().allMatch(content -> content.contains("\"hook\":\"gates\"")
                && content.contains("\"tier\":\"project\"")), logged.toString());
    }

    private static void assertOneProjectRecord(Gate gate, Stage stage, String decision) {
        assertEquals(1, gate.records().size(), gate.records().toString());
        HookRecord only = gate.records().get(0);
        assertEquals("gates", only.hook());
        assertEquals(Tier.PROJECT, only.tier());
        assertEquals(stage, only.stage());
        assertEquals(decision, only.decision());
    }

    /** {@code "<stage> <decision>"} out of a HOOK entry's JSON, as the log holds it. */
    private static String stageAndDecision(String content) {
        try {
            JsonNode node = new ObjectMapper().readTree(content);
            return node.get("stage").asText() + " " + node.get("decision").asText();
        } catch (IOException unreadable) {
            throw new AssertionError("a HOOK entry is not JSON: " + content, unreadable);
        }
    }

    /**
     * Slices 3 and 4: approval.post and fold.post over the archive, recorded in the log. fold.post
     * reads the folder's summary (amended 2026-09-29), so the marker is kept only when the folder
     * lost it; when the folder kept it the hook notifies instead, and that notice waits for the
     * fold to say it was saved.
     */
    @Test
    void approval_post_and_fold_post_run_the_project_s_file_and_land_in_the_log()
            throws Exception {
        gates();
        String log = conversations.log(Origin.SUBMISSION, LEDGER, "scribe", null, Budget.of(5),
                "enzo").id();
        turns.record(log, "hi", "hello", Outcome.Ending.ANSWERED, null, "scribe", null);

        stages.approvalAnswered(log, "apr_1", ApprovalAnswer.ALLOW, "once");
        LogStages.Held lost = stages.foldPost(log, 1,
                new Summarised(1, 2, 900, "They agreed to roll back."));
        LogStages.Held survived = stages.foldPost(log, 1,
                new Summarised(1, 2, 900, "skill X@1 was loaded. They agreed to roll back."));

        assertEquals("skill X@1 was loaded before turn 1", lost.kept());
        assertEquals("", survived.kept(), "the folder kept the marker, so the hook did not");
        assertEquals(List.of("gates: apr_1 allow once"),
                inbox.list("enzo", false, 0, 10).stream().map(InboxItem::answer).toList(),
                "fold.post's notice waits for the fold to be saved");
        lost.tell().run();
        survived.tell().run();
        // Newest first, as the inbox lists.
        assertEquals(List.of("gates: the folder kept skill X@1 through turn 1",
                        "gates: apr_1 allow once"),
                inbox.list("enzo", false, 0, 10).stream().map(InboxItem::answer).toList());
        List<String> decisions = entries.forConversation(log).stream()
                .filter(entry -> entry.kind() == EntryKind.HOOK).map(EntryRecord::content).toList();
        assertEquals(List.of("approval.post notify", "fold.post keep", "fold.post notify"),
                decisions.stream().map(LogStagesEndToEndTest::stageAndDecision).toList(),
                decisions.toString());
    }

    // --- local hooks (spec 2026-09-30-local-hooks-are-served) --------------------------------

    private static final String MINE = """
            import type { Hook } from '@plowshare/hooks'

            export default {
                name: 'mine',
                stages: {
                    'log.open': {
                        handle(e) { return { add: 'My rules for ' + e.context.agent + '.' } },
                    },
                    'delivery.pre': {
                        handle(e) { return { note: '(mine)' } },
                    },
                },
            } satisfies Hook
            """;

    /**
     * Spec 2026-09-30-local-hooks-are-served decision 3 and plan choice 13: the local tier caches
     * a log's pin lookup, a miss included, so the pin has to land before {@code log.open} first
     * looks. A served {@code log.open} hook shapes its own log's opening, a later stage still sees
     * the set, and no lookup for the log ever read empty.
     */
    @Test
    void a_served_hook_sees_its_own_log_open_because_the_pin_lands_before_any_lookup() {
        LocalHookSetStore sets = new LocalHookSetStore(jdbc);
        List<Optional<String>> lookups = new CopyOnWriteArrayList<>();
        HooksProperties properties = new HooksProperties();
        properties.setTimeout(Duration.ofSeconds(2));
        FakeFiles laptop = new FakeFiles().withListing(".plowshare/hooks", List.of("10-mine.ts"))
                .withFile(".plowshare/hooks/10-mine.ts", MINE);
        PinnedLocalHooks pins = new PinnedLocalHooks(
                session -> new ChannelHooks(laptop, session).read(), "tui-1"::equals,
                (project, session) -> "ledger".equals(project),
                PinnedLocalHooks.Accounts.all(session -> Optional.of("enzo")), conversations, sets);
        try (ScriptHooks local = ScriptHooks.local(conversation -> {
            Optional<String> pin = conversations.localHooksOf(conversation);
            lookups.add(pin);
            return pin;
        }, conversations::ownerOf, sets::find, (log, session) -> false, engine, properties,
                Instant::now)) {
            HookedLogStages pinned = new HookedLogStages(local, conversations, turns,
                    conversation -> false, entries,
                    (handle, kind, text) -> inbox.notice(handle, kind, text, Instant.now()),
                    name -> false, Instant::now, properties::getTimeout, new WordsTokenizer(), pins);
            String log = conversations.log(Origin.SUBMISSION, LEDGER, "scribe", null,
                    Budget.of(5), "enzo").id();

            pinned.opened(new LogStages.LogOpened(log, Origin.SUBMISSION, LEDGER, "scribe", false,
                    null, "tui-1", null));
            String delivered = pinned.deliveryPre(log, Handover.INBOX, "hello");

            assertEquals(Optional.of("My rules for scribe."), conversations.opening(log));
            assertEquals("hello\n\n(mine)", delivered, "a later stage still sees the set");
            assertTrue(!lookups.isEmpty() && lookups.stream().allMatch(Optional::isPresent),
                    "a lookup before the pin would have cached a miss: " + lookups);
            List<String> decisions = entries.forConversation(log).stream()
                    .filter(entry -> entry.kind() == EntryKind.HOOK).map(EntryRecord::content)
                    .toList();
            assertTrue(decisions.get(0).contains("\"tier\":\"local\"")
                    && decisions.get(0).contains("\"stage\":\"log.open\"")
                    && decisions.get(0).contains("\"file\":\"10-mine.ts\""), decisions.toString());
        }
    }
}
