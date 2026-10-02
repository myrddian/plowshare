package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.JobLog;
import io.aeyer.plowshare.server.archive.JobRecord;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A job id survives the process that minted it, and the run behind it is a row.
 *
 * <h2>What this file exists to fail on</h2>
 *
 * <p>{@code implementation rationale} §2, which records the defect precisely: {@code
 * JobStore} minted {@code "job_" + String.format("%06d", ids.incrementAndGet())}
 * from an in-process {@code AtomicLong}, so <b>ids reset to {@code job_000001}
 * on every restart</b> and two unrelated runs on two different days collided. A
 * probe script broke on it.
 *
 * <ul>
 *   <li><b>two stores do not mint the same id</b>, which is what a restart is
 *       from the identifier's point of view. {@link
 *       #two_stores_a_restart_apart_do_not_mint_the_same_id} is the one that
 *       fails the moment a counter comes back;
 *   <li><b>the row is written when the job starts and not only when it ends.</b>
 *       A record that appeared at the end would be no record at all of the case
 *       that motivated this — a twenty-six minute ingest whose process died;
 *   <li><b>the handle and the record are two different things.</b> A new store
 *       has no handle on a job the old one ran, and refuses one; the row is
 *       still there. That pairing is the whole design, and a test that asserted
 *       only half of it would pass over an implementation that had quietly made
 *       {@code JobStore.get} read the database;
 *   <li><b>a store with no {@link JobLog} still runs jobs.</b> Three dozen
 *       fixtures build one over a stub runtime and no database at all, and a
 *       durable record that could refuse a run would be worse than no record.
 * </ul>
 *
 * <h2>No model is reached, and none is needed — with one exception</h2>
 *
 * <p>Every job here comes through {@code JobStore.submit(String, Home,
 * Function)} — the door {@code Curator.pass} and {@code DocumentController} use
 * — which takes the work itself rather than a definition to run. So the {@link
 * JobRuntime} below is a real one over a transport that throws if anything ever
 * calls it, which is the assertion that this file does not depend on a model
 * rather than a comment claiming so.
 *
 * <p><b>{@code submitEvent} is the one door here that runs an {@link
 * AgentDefinition} rather than a bare {@code Function}</b>, and a definition is
 * run through {@link JobRuntime}, which genuinely dispatches to a model. {@code
 * an_event_run_reports_its_ending_once_with_the_conversation_it_ran_in} builds
 * its own {@link JobRuntime} over {@link Answering} — a transport that answers
 * at once rather than {@link Unreachable}'s throw — so that one test's own
 * scaffolding says plainly which of the two rules its run is under, instead of
 * quietly breaking the invariant above for the rest of the file.
 */
@Testcontainers
class DurableJobsTest {

    /** The pgvector image, not stock postgres:16: V1's first line is CREATE
     *  EXTENSION vector, and this class runs the whole migration chain. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    /** Long enough that a genuinely stuck job fails this file instead of
     *  hanging the suite, and never reached on a good run. */
    private static final int PATIENCE_SECONDS = 30;

    private static JdbcTemplate jdbc;

    private JobLog rows;
    private final List<JobStore> opened = new ArrayList<>();

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(dataSource()).load().migrate();
        jdbc = new JdbcTemplate(dataSource());
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeEach
    void fresh() {
        jdbc.execute("TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, jobs, projects CASCADE");
        rows = new JobLog(jdbc);
    }

    @AfterEach
    void close() {
        opened.forEach(JobStore::close);
    }

    // --- the id ------------------------------------------------------------------

    /**
     * <b>The defect.</b> Two stores over one database is what a restart looks
     * like to the table: the second process has no memory of the first, and
     * under a counter both first runs were called {@code job_000001}.
     */
    @Test
    void two_stores_a_restart_apart_do_not_mint_the_same_id() {
        JobStore before = storeWithARecord();
        String first = answered(before, "scribe");
        before.close();

        JobStore after = storeWithARecord();
        String second = answered(after, "scribe");

        assertNotEquals(first, second,
                "an id minted from a counter that restarts with the process is the defect");
        assertTrue(first.compareTo(second) < 0,
                "and the earlier run still sorts first — " + first + " then " + second);
        assertTrue(rows.find(first).isPresent(), "both runs are rows");
        assertTrue(rows.find(second).isPresent());
    }

    // --- the row -----------------------------------------------------------------

    /**
     * The row exists while the run is going, which is the case this whole change
     * is for: an ingest is twenty-six minutes, and a record written only at the
     * end is no record of the process that died in the middle.
     *
     * <p>Held open on a latch rather than on a duration, so nothing here is a
     * sleep and the assertion is made at a moment the test chose.
     */
    @Test
    void a_row_is_there_while_the_run_is_still_going() throws Exception {
        JobStore store = storeWithARecord();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        String id = store.submit("document_ingest", Home.global(), cancelled -> {
            running.countDown();
            await(release);
            return new Outcome(Outcome.Ending.ANSWERED, "done", 1, 1, "");
        });

        assertTrue(running.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the job never started");
        JobRecord midway = rows.find(id).orElseThrow(
                () -> new AssertionError("a run in flight has no durable record of itself"));
        assertFalse(midway.ended(), "and nothing has filed an outcome for it yet");
        assertEquals("document_ingest", midway.agent());

        release.countDown();
        awaitDone(store, id);
        assertTrue(rows.find(id).orElseThrow().ended(), "and then it has");
    }

    /**
     * <b>The handle and the record are two different things.</b> A store that had
     * quietly started answering {@code get} out of the database would pass every
     * other assertion in this file and would have thrown away the one property
     * {@code JobStore}'s javadoc is built on — that it is the authority on what
     * is running <em>now</em>, which no durable row can be after a restart.
     */
    @Test
    void a_new_store_has_no_handle_on_a_job_the_old_one_ran_and_the_row_is_still_there() {
        JobStore before = storeWithARecord();
        String id = answered(before, "promotion_judge");
        before.close();

        JobStore after = storeWithARecord();

        // NotFoundFault, not IllegalArgumentException: this assertion was
        // written before the faults slice gave JobStore.get a refusal that maps
        // to a 404 on both surfaces, and it is Testcontainers-backed, so it did
        // not run again between the change and now. The claim it makes is
        // unchanged -- a miss, not a handle.
        assertThrows(NotFoundFault.class, () -> after.get(id),
                "a handle does not survive the process, and it must not pretend to");
        JobRecord row = rows.find(id).orElseThrow();
        assertEquals(Outcome.Ending.ANSWERED, row.ending());
        assertEquals(2, row.steps());
        assertEquals(3, row.modelCalls());
    }

    /** The project is on the row as a reference, which is where {@code
     *  implementation rationale} §2 says it belongs — and not in the id, because a project
     *  moves. */
    @Test
    void a_job_records_the_project_it_ran_in() {
        JobStore store = storeWithARecord();

        String id = answered(store, "promotion_judge", Home.of("payments"));

        assertEquals(Home.of("payments"), rows.find(id).orElseThrow().home());
    }

    /** And a run that named no project is global, which is the ordinary shape of
     *  a caller who did not say which — V6's rule, applied to the same column. */
    @Test
    void a_job_that_named_no_project_is_recorded_as_global() {
        JobStore store = storeWithARecord();

        String id = answered(store, "scribe");

        assertTrue(rows.find(id).orElseThrow().home().isGlobal());
    }

    /**
     * A store with no {@link JobLog} runs jobs and writes nothing, which is what
     * three dozen fixtures over a stub runtime and no database are.
     *
     * <p>It still mints a durable-shaped id, and that matters: an id whose shape
     * depended on whether a database happened to be wired would be two id
     * schemes in one server, and the fixture's one would be the one nothing ever
     * tested.
     */
    @Test
    void a_store_with_no_record_runs_jobs_and_writes_none() {
        JobStore store = track(new JobStore(runtime(), JobEvents.NONE));

        String id = answered(store, "scribe");

        assertTrue(id.startsWith(JobLog.PREFIX), id);
        assertEquals(Optional.empty(), rows.find(id));
        assertEquals(Integer.valueOf(0),
                jdbc.queryForObject("SELECT count(*) FROM jobs", Integer.class));
    }

    // --- an event run --------------------------------------------------------------

    /**
     * The door a trigger starts a run through: no session, and the ending
     * reaches the caller once, carrying whichever conversation (or none) the
     * run was logged in.
     *
     * <p>This store is built with a {@code JobLog} and no {@code Compaction} —
     * the shape every scheduled-work fixture before this task ran under — so
     * {@code submitEvent}'s own {@code Transcript.NONE} fallback is what this
     * asserts, and {@link JobStore.EventRun#conversation()} is null because
     * there is no {@code Compaction} to open one.
     */
    @Test
    void an_event_run_reports_its_ending_once_with_the_conversation_it_ran_in()
            throws Exception {
        JobStore store = track(new JobStore(answeringRuntime(), JobEvents.NONE, null,
                new JobLog(jdbc)));
        List<Outcome> endings = new CopyOnWriteArrayList<>();
        JobStore.EventRun run = store.submitEvent(definition(), "summarise the night",
                Home.global(), 7, null, null, Speaker.harness(),
                (conversation, outcome) -> endings.add(outcome));
        awaitDone(store, run.id());
        assertEquals(1, endings.size());
        assertNull(run.conversation(), "no Compaction is wired, so there is no transcript");
    }

    // --- incoming: only a fresh utterance reaches TriggerNoticing ------------------

    /**
     * {@code submitEvent} is a trigger's own run and always says {@code incoming = true} to
     * {@link JobRuntime#run} — spec §6 names an event task beside a person's message as one of
     * the doors the trap watches, and this door has no caller that arrives any other way.
     */
    @Test
    void submit_event_reaches_the_runtime_as_an_incoming_utterance() {
        JobRuntime runtime = answeringRuntime();
        List<String> asked = new ArrayList<>();
        runtime.useTriggers((definition, utterance, home, session, conversation) -> {
            asked.add(utterance);
            return Optional.empty();
        });
        JobStore store = track(new JobStore(runtime, JobEvents.NONE, null, new JobLog(jdbc)));

        JobStore.EventRun run = store.submitEvent(grantingDefinition(), "summarise the night",
                Home.global(), 7, null, null, Speaker.harness(), (conversation, outcome) -> { });
        awaitDone(store, run.id());

        assertEquals(List.of("summarise the night"), asked);
    }

    /**
     * The pre-existing eight-argument {@code submit(definition, prompt, home, session, budget,
     * transcript, origin, ended)} predates the trap and states {@code incoming = false}, chaining
     * down through the nine-argument {@code cap} overload to the one that states it explicitly.
     * {@link Turn} never reaches this exact overload: its private {@code speak} reaches the
     * eleven-argument form directly for every public door, and {@link Turn#resume} reaches the
     * nine-argument {@code cap} overload instead — this test calls the eight-argument door
     * directly, to pin what a caller reaching only that far still gets.
     */
    @Test
    void the_pre_existing_eight_argument_submit_reaches_the_runtime_as_not_incoming() {
        JobRuntime runtime = answeringRuntime();
        List<String> asked = new ArrayList<>();
        runtime.useTriggers((definition, utterance, home, session, conversation) -> {
            asked.add(utterance);
            return Optional.empty();
        });
        JobStore store = track(new JobStore(runtime, JobEvents.NONE, null, new JobLog(jdbc)));
        List<Outcome> endings = new CopyOnWriteArrayList<>();

        String id = store.submit(grantingDefinition(), "hello", Home.global(), null,
                Budget.of(10), Transcript.NONE, Origin.TURN, endings::add);
        awaitDone(store, id);

        assertEquals(List.of(), asked);
    }

    // --- scaffolding -------------------------------------------------------------

    private JobStore storeWithARecord() {
        return track(new JobStore(runtime(), JobEvents.NONE, null, new JobLog(jdbc)));
    }

    private JobStore track(JobStore store) {
        opened.add(store);
        return store;
    }

    private String answered(JobStore store, String name) {
        return answered(store, name, Home.global());
    }

    private String answered(JobStore store, String name, Home home) {
        String id = store.submit(name, home,
                cancelled -> new Outcome(Outcome.Ending.ANSWERED, "done", 2, 3, ""));
        awaitDone(store, id);
        return id;
    }

    /**
     * Waits for a job to finish.
     *
     * <p>A poll and not a sleep: the loop asks the state it is waiting for
     * rather than guessing a duration, which is {@code UniversalLoggingTest}'s
     * arrangement and its reason. The row is written before {@code Job.finish},
     * so a job reporting DONE is a job whose record is already there — which is
     * what makes this a barrier and not a race.
     */
    private static void awaitDone(JobStore store, String id) {
        for (int attempt = 0; attempt < PATIENCE_SECONDS * 40; attempt++) {
            if (store.get(id).state() == Job.State.DONE) {
                return;
            }
            await(25);
        }
        throw new AssertionError("job " + id + " never finished");
    }

    private static void await(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for a job", stop);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(PATIENCE_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("a job was never released");
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted holding a job open", stop);
        }
    }

    private static JobRuntime runtime() {
        return new JobRuntime(
                new LlmDispatcher(
                        List.of(new LlmPool("unused", List.of("model-fast"), Map.of("fast", "model-fast"),
                                4, 1, Duration.ofSeconds(5), new Unreachable())),
                        new NoOpTokenLedger()),
                List.of());
    }

    /** The one job in this file that runs a real {@link AgentDefinition}, over a
     *  transport that answers rather than {@link Unreachable}'s throw. See the
     *  class javadoc's "one exception". */
    private static JobRuntime answeringRuntime() {
        return new JobRuntime(
                new LlmDispatcher(
                        List.of(new LlmPool("scripted", List.of("model-fast"),
                                Map.of("fast", "model-fast"), 4, 1, Duration.ofSeconds(5),
                                new Answering())),
                        new NoOpTokenLedger()),
                List.of());
    }

    /** What {@code submitEvent} runs: no tools, no callees, one short answer. */
    private static AgentDefinition definition() {
        return new AgentDefinition("night_summary", "a fixture agent for an event run", "fast",
                List.of(), List.of(), List.of(), 4, 20, "Summarise briefly.");
    }

    /** The same fixture agent, granted one orchestration — there is no frontmatter key for this,
     *  so it is built by hand off {@link #definition}, on {@code JobRuntimeTest#granting}'s
     *  pattern. */
    private static AgentDefinition grantingDefinition() {
        AgentDefinition plain = definition();
        return new AgentDefinition(plain.name(), plain.description(), plain.model(), plain.intent(),
                plain.sampling(), plain.tools(), plain.calls(), plain.scopes(), plain.maxTurns(),
                plain.maxModelCalls(), plain.prompt(), plain.exported(), plain.delegable(),
                plain.vision(), plain.bot(), plain.announcesInbox(), plain.fallback(),
                List.of("code_implementation"));
    }

    /** Every job in this file supplies its own work, so nothing here should ever
     *  reach a model — and this is what says so rather than a comment. */
    private static final class Unreachable implements LlmTransport {

        @Override
        public String poolName() {
            return "unused";
        }

        @Override
        public Completion complete(
                String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools) {
            throw new AssertionError("this file must not reach a model");
        }

        @Override
        public Completion stream(
                String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools, io.aeyer.plowshare.server.llm.dispatch.Deltas sink,
                java.util.function.BooleanSupplier abandoned) {
            throw new AssertionError("this file must not reach a model");
        }

        @Override
        public io.aeyer.plowshare.server.llm.dispatch.Embeddings embed(
                String wireModel, List<String> input) {
            throw new AssertionError("this file must not reach a model");
        }

        @Override
        public void close() {
        }
    }

    /** Answers at once, for the one job in this file that runs a real {@link
     *  AgentDefinition} rather than supplying its own work. */
    private static final class Answering implements LlmTransport {

        @Override
        public String poolName() {
            return "scripted";
        }

        @Override
        public Completion complete(
                String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools) {
            return new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
        }

        @Override
        public Completion stream(
                String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools, Deltas sink, BooleanSupplier abandoned) {
            return complete(wireModel, messages, sampling, tools);
        }

        @Override
        public Embeddings embed(String wireModel, List<String> input) {
            throw new UnsupportedOperationException("an event run does not embed");
        }

        @Override
        public void close() {
        }
    }
}
