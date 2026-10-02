package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.agents.curator.Passes;
import io.aeyer.plowshare.server.api.AgentController;
import io.aeyer.plowshare.server.api.ApiExceptionHandler;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.union.UnionRouting;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
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
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The session id's whole journey, and the capability it does not yet change.
 *
 * <p>Slice 3b built every part of the remote file path and could wire none of
 * it, for one reason: nothing carried a session id at submission. This is that
 * id travelling — {@code RunAgentRequest} to {@code JobStore.submit} to {@code
 * JobRuntime.run} to {@code RunProviders.forRun} — and nothing more. The
 * provider appended over it is {@code RemoteWiringTest}'s subject; what is
 * pinned here is that the id arrives unchanged, that its absence is an ordinary
 * answer, and that a child run is built for its parent's session rather than for
 * none.
 *
 * <h2>Nullable is the subject, not an omission</h2>
 *
 * <p>The design spec's sentence is that a job submitted by a scheduled tick
 * <em>"has a smaller set, not an empty capability"</em>. Three callers in {@code
 * main} submit with no session. Two are driven here: {@code POST /v1/curate},
 * which goes through {@code JobStore}'s other door and never names one, and a
 * {@code POST /v1/agents/&#123;name&#125;/runs} body that omits the key. The
 * third is {@code Curator.pass}'s own rulings, which call {@code JobRuntime}
 * directly with a null session; those are {@code CuratorTest}'s to drive and
 * are not re-driven here, because a pass built from this file's fixtures would
 * be a second, worse copy of that harness.
 *
 * <h2>What the last two tests can and cannot see today</h2>
 *
 * <p>They call the production {@code AgentsConfig.runProviders} seam and assert
 * that a run with a live session id is given exactly the providers a run with no
 * session is given. <b>When this was written that held because the wiring did
 * not read the id at all</b> — the seam took no {@link SessionRegistry} — and
 * these two were the fixture the append had to survive: a session that is real
 * and attached and is <em>not</em> a file provider. The second test builds a
 * registry holding one of each — a session whose only role is {@link
 * Role#LISTENER}, and a session whose role is {@link Role#FILE_PROVIDER} — so
 * that the question "does this session have a disk" has something to answer no
 * about and something to answer yes about, rather than being put to a registry
 * in which every answer is the same. <b>The append exists now</b>, it reads that
 * registry, and the same assertion holds for the reason it was written for
 * rather than by default; {@code RemoteWiringTest} is where the yes side is
 * driven.
 *
 * <p><b>Measured rather than asserted, twice — before the append and again
 * after it:</b> with {@code runProviders} changed to append a second provider
 * whenever the session id is non-null — the mistake of keying the append on a
 * session <em>existing</em> instead of on the role being attached — {@code
 * a_run_whose_session_holds_only_a_listener_is_given_exactly_the_same_providers}
 * fails and {@code a_run_with_no_session_is_given_the_providers_it_is_given_today}
 * still passes. So the pair discriminates the failure it is here for.
 */
class SessionSubmissionTest {

    // --- scaffolding -------------------------------------------------------------

    /** A transport that answers with what the test queued, in order. */
    private static final class Scripted implements LlmTransport {

        private final List<Completion> steps = new ArrayList<>();
        private final AtomicInteger at = new AtomicInteger();

        Scripted then(Completion step) {
            steps.add(step);
            return this;
        }

        @Override
        public String poolName() {
            return "scripted";
        }

        @Override
        public Completion complete(
                String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools) {
            int index = at.getAndIncrement();
            return index < steps.size()
                    ? steps.get(index)
                    : new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
        }

        @Override
        public Completion stream(
                String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools, Deltas sink,
                BooleanSupplier abandoned) {
            // The job runtime streams now. This double answers the same
            // thing either way, on purpose: reconciling two wire formats is the
            // transport's problem and OpenAiTransportTest is where it is
            // proved, so a fake that answered differently down this path would
            // only be testing itself. Delegating to complete(...) keeps every
            // assertion in this class — what a turn was offered, what it sent,
            // what came back — meaning exactly what it meant.
            Completion streamed = complete(wireModel, messages, sampling, tools);
            // Asked after the call, which is where a fake can honestly ask it:
            // the real transport asks once per chunk, and this one has exactly
            // one chunk. See LlmTransport.stream and CallerAbandonedException.
            if (abandoned.getAsBoolean()) {
                throw new CallerAbandonedException(poolName());
            }
            String content = streamed.content();
            if (content != null && !content.isEmpty()) {
                sink.answered(content);
            }
            return streamed;
        }

        @Override
        public Embeddings embed(String wireModel, List<String> input) {
            throw new UnsupportedOperationException("the job runtime does not embed");
        }

        @Override
        public void close() {
        }
    }

    /**
     * The seam under test, recording the session each run asked it for.
     *
     * <p>{@code FileWiringTest.Recording} records the home and the grants,
     * because those are its subject; this one records the third argument for the
     * same reason. A list that may hold nulls, deliberately: "no session" is a
     * value this seam is asked for and not a case to be filtered away before the
     * assertion sees it.
     */
    private static final class Recording implements RunProviders {

        private final List<String> sessions = Collections.synchronizedList(new ArrayList<>());

        @Override
        public List<FileProvider> forRun(Home home, List<Grant> grants, String sessionId, String owner) {
            sessions.add(sessionId);
            return List.of();
        }

        List<String> sessions() {
            synchronized (sessions) {
                return new ArrayList<>(sessions);
            }
        }
    }

    private static LlmDispatcher dispatcherOver(LlmTransport transport) {
        return new LlmDispatcher(
                List.of(new LlmPool("scripted", List.of("model-fast"), Map.of("fast", "model-fast"),
                        4, 1, Duration.ofSeconds(5), transport)),
                new NoOpTokenLedger());
    }

    private static Completion asking(String id, String name, String arguments) {
        return new Completion("", "tool_calls", TokenUsage.UNKNOWN,
                List.of(new ToolCall(id, name, arguments)));
    }

    private static Completion done() {
        return new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
    }

    private static Budget generous() {
        return Budget.of(100);
    }

    /** A definition written the way an operator writes one, loaded the way a
     *  boot loads it. */
    private static void write(Path dir, String name, String tools, String scopes, String calls) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".md"), "---\n"
                    + "name: " + name + "\n"
                    + "description: a fixture agent\n"
                    + "model: fast\n"
                    + "tools: " + tools + "\n"
                    + (scopes == null ? "" : "scopes: " + scopes + "\n")
                    + (calls == null ? "" : "calls: " + calls + "\n")
                    + "max-turns: 4\n"
                    + "max-model-calls: 8\n"
                    + "---\n"
                    + "You look at files.\n");
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the fixture " + name, e);
        }
    }

    /** A leaf that asks for {@code file_roots} once and then answers. */
    private static void writeLooker(Path dir) {
        write(dir, "looker", "[" + FileTools.ROOTS_NAME + "]", "[workspace:read]", null);
    }

    private static AgentDefinition agent(Path dir, String name, JobRuntime runtime) {
        return AgentRegistry.of(dir, runtime.knownTools()).get(name);
    }

    private static Outcome awaitOutcome(JobStore store, String id) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (store.get(id).state() == Job.State.DONE) {
                Outcome outcome = store.get(id).outcome().orElse(null);
                assertNotNull(outcome, "a DONE job with no outcome");
                return outcome;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("job " + id + " never finished");
    }

    // --- the run, and the seam its providers are built over -----------------------

    /**
     * A run started with no session builds its providers for no session.
     *
     * <p>The null is asserted rather than the list being empty: a seam asked for
     * {@code ""} or for the string {@code "null"} would satisfy "not a real
     * session" and would be a different value arriving at the wiring that has to
     * decide on it.
     */
    @Test
    void a_run_with_no_session_asks_the_seam_for_no_session(@TempDir Path dir) {
        Recording providers = new Recording();
        JobRuntime runtime = new JobRuntime(
                dispatcherOver(new Scripted().then(asking("1", FileTools.ROOTS_NAME, "{}"))),
                List.of(), null, providers);
        writeLooker(dir);

        runtime.run(agent(dir, "looker", runtime), "look", Home.of("plowshare"), generous(), null);

        assertEquals(1, providers.sessions().size(), providers.sessions().toString());
        assertNull(providers.sessions().get(0), providers.sessions().toString());
    }

    /** And a run started with one asks for exactly that one. */
    @Test
    void a_run_with_a_session_asks_the_seam_for_that_session(@TempDir Path dir) {
        Recording providers = new Recording();
        JobRuntime runtime = new JobRuntime(
                dispatcherOver(new Scripted().then(asking("1", FileTools.ROOTS_NAME, "{}"))),
                List.of(), null, providers);
        writeLooker(dir);

        runtime.run(agent(dir, "looker", runtime), "look", Home.of("plowshare"), generous(),
                "s-cli");

        assertEquals(List.of("s-cli"), providers.sessions());
    }

    /**
     * A delegated child is built for its parent's session.
     *
     * <p>The case that would break silently: a child that lost the session would
     * still run, still answer, and simply not be able to see the machine that
     * asked for the work — a smaller capability arriving by accident, which is
     * the one thing the spec's "smaller set, not an empty capability" sentence
     * is about. Two asks are expected because two runs each route a file tool,
     * and both must name the same session.
     */
    @Test
    void a_delegated_child_asks_the_seam_for_its_parents_session(@TempDir Path dir) {
        Recording providers = new Recording();
        Scripted transport = new Scripted()
                // The parent: look at files, then hand the work down.
                .then(asking("p1", FileTools.ROOTS_NAME, "{}"))
                .then(asking("p2", AgentRunTool.NAME, "{\"agent\":\"looker\",\"task\":\"look\"}"))
                // The child, on the parent's own thread: look at files, answer.
                .then(asking("c1", FileTools.ROOTS_NAME, "{}"))
                .then(done())
                // Back in the parent.
                .then(done());
        AtomicReference<AgentRegistry> graph = new AtomicReference<>();
        JobRuntime runtime = new JobRuntime(
                dispatcherOver(transport), List.of(), graph::get, providers);
        writeLooker(dir);
        write(dir, "boss", "[" + AgentRunTool.NAME + ", " + FileTools.ROOTS_NAME + "]",
                "[workspace:read]", "[looker]");
        graph.set(AgentRegistry.of(dir, runtime.knownTools()));

        runtime.run(graph.get().get("boss"), "delegate", Home.of("plowshare"), generous(),
                "s-cli");

        assertEquals(List.of("s-cli", "s-cli"), providers.sessions());
    }

    // --- the store ----------------------------------------------------------------

    /** The store carries the session from submission into the run it starts. */
    @Test
    void the_store_carries_the_session_into_the_run(@TempDir Path dir) throws Exception {
        Recording providers = new Recording();
        JobRuntime runtime = new JobRuntime(
                dispatcherOver(new Scripted().then(asking("1", FileTools.ROOTS_NAME, "{}"))),
                List.of(), null, providers);
        writeLooker(dir);

        try (JobStore store = new JobStore(runtime)) {
            String id = store.submit(
                    agent(dir, "looker", runtime), "look", Home.of("plowshare"), "s-cli");
            assertEquals(Outcome.Ending.ANSWERED, awaitOutcome(store, id).ending());
        }

        assertEquals(List.of("s-cli"), providers.sessions());
    }

    /**
     * And a submission with no session runs to an answer, which is the half a
     * nullable parameter is for.
     */
    @Test
    void the_store_submitting_with_no_session_still_runs_the_job(@TempDir Path dir)
            throws Exception {
        Recording providers = new Recording();
        JobRuntime runtime = new JobRuntime(
                dispatcherOver(new Scripted().then(asking("1", FileTools.ROOTS_NAME, "{}"))),
                List.of(), null, providers);
        writeLooker(dir);

        try (JobStore store = new JobStore(runtime)) {
            String id = store.submit(
                    agent(dir, "looker", runtime), "look", Home.of("plowshare"), null);
            assertEquals(Outcome.Ending.ANSWERED, awaitOutcome(store, id).ending());
        }

        assertEquals(1, providers.sessions().size(), providers.sessions().toString());
        assertNull(providers.sessions().get(0), providers.sessions().toString());
    }

    /**
     * A session id that is present and empty is refused where a caller hears it.
     *
     * <p>Null and blank are not the same statement. Null is a client that never
     * had a session; {@code ""} is a client that has one and sent it wrong, and
     * accepting it would hand that client the smaller capability silently — a
     * run that never reaches the disk it was submitted from and no error
     * anywhere. {@code SessionRegistry.attach} makes the same refusal for the
     * same reason from the other end.
     */
    @Test
    void the_store_refuses_a_session_that_cannot_name_anything(@TempDir Path dir) {
        JobRuntime runtime = new JobRuntime(
                dispatcherOver(new Scripted()), List.of(), null, new Recording());
        writeLooker(dir);
        AgentDefinition looker = agent(dir, "looker", runtime);

        try (JobStore store = new JobStore(runtime)) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> store.submit(looker, "look", Home.of("plowshare"), "  "));
            assertTrue(refused.getMessage().contains("session"), refused.getMessage());
        }
    }

    // --- the request body ---------------------------------------------------------

    /** The session in the body is the session the store is given. */
    @Test
    void the_session_in_the_request_body_reaches_the_store() throws Exception {
        JobStore jobs = mock(JobStore.class);
        when(jobs.submit(any(AgentDefinition.class), anyString(), any(), any(), any(), any(),
                anyBoolean(), any())).thenReturn("job_000001");

        mvc(jobs).perform(post("/v1/agents/echo/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"task": "look", "project": "payments", "session": "s-cli"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("job_000001"));

        verify(jobs).submit(any(AgentDefinition.class), eq("look"), eq(Home.of("payments")),
                eq("s-cli"), isNull(), eq(List.of()), eq(true), isNull());
    }

    /**
     * A body that names no session submits without one, and the run starts.
     *
     * <p>{@code isNull()} and not {@code any()}: a controller that passed {@code
     * ""} or the literal {@code "null"} would satisfy {@code any()} and would be
     * a different value reaching the wiring.
     */
    @Test
    void a_request_that_names_no_session_submits_without_one() throws Exception {
        JobStore jobs = mock(JobStore.class);
        when(jobs.submit(any(AgentDefinition.class), anyString(), any(), any(), any(), any(),
                anyBoolean(), any())).thenReturn("job_000001");

        mvc(jobs).perform(post("/v1/agents/echo/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"task": "look"}"""))
                .andExpect(status().isAccepted());

        verify(jobs).submit(any(AgentDefinition.class), eq("look"), eq(Home.global()), isNull(),
                isNull(), eq(List.of()), eq(true), isNull());
    }

    /** An empty session in the body is a 400 rather than a quietly smaller run. */
    @Test
    void a_request_naming_an_empty_session_is_refused() throws Exception {
        JobStore jobs = mock(JobStore.class);

        mvc(jobs).perform(post("/v1/agents/echo/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"task": "look", "session": "   "}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(jobs);
    }

    /**
     * The curator's door is untouched, and that is the point of the whole
     * nullable.
     *
     * <p>{@code POST /v1/curate} goes through {@code JobStore}'s other overload,
     * which takes a name and a function and no session at all. A pass really is
     * a run — it is polled by the same endpoint on the same virtual thread — and
     * it has no client to reach a disk on. This test is what says this task did
     * not quietly make it need one.
     */
    @Test
    void a_curator_pass_is_still_submitted_with_no_session_at_all() throws Exception {
        JobStore jobs = mock(JobStore.class);
        when(jobs.submit(anyString(), any(), any())).thenReturn("job_000007");

        mvc(jobs).perform(post("/v1/curate").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"project": "payments"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.agent").value(Curator.BY));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(jobs).submit(name.capture(), any(), any());
        assertEquals(Curator.BY, name.getValue());
        verify(jobs, never()).submit(any(AgentDefinition.class), any(), any(), any(), any(), any(),
                anyBoolean(), any());
    }

    // --- what a session does not change -------------------------------------------

    /**
     * The providers a run with no session is given: the server's own disk, and
     * the sentence saying nothing is rooting the project.
     *
     * <p>The heading above this section used to end "yet". It does not any more,
     * and the reason is presence rather than a sweep: a named project is served
     * by the session that <em>roots</em> it, so the session a run was submitted
     * under changes nothing about which machine it reaches. What a session still
     * decides is the global tier, which has no place to root.
     */
    @Test
    void a_run_with_no_session_is_given_the_servers_disk_and_is_told_why_that_is_all() {
        assertEquals(List.of("local", "presence"), names(wiring(new SessionRegistry()).forRun(
                Home.of("payments"), List.of(new Grant(Scope.WORKSPACE, Mode.READ)), null, null)));
    }

    /**
     * And a run whose session holds only a listener is given exactly those.
     *
     * <p>A session existing is not a file provider being attached. The registry
     * here holds one of each so the distinction is a fixture rather than a
     * sentence: {@code watching} is the browser of slice 4, live and attached
     * and holding no disk; {@code working} is the command-line client, which
     * does. A wiring that appended a remote provider for the first would be
     * handing a page on the internet a machine.
     */
    @Test
    void a_run_whose_session_holds_only_a_listener_is_given_exactly_the_same_providers() {
        SessionRegistry sessions = new SessionRegistry();
        sessions.attach("watching", Role.LISTENER, new Object());
        sessions.attach("working", Role.FILE_PROVIDER, new Object());

        assertEquals(List.of("local", "presence"), names(wiring(sessions).forRun(
                Home.of("payments"), List.of(new Grant(Scope.WORKSPACE, Mode.READ)), "watching", null)));
    }

    // --- helpers ------------------------------------------------------------------

    /** The production seam, built by the production method over the registry
     *  the caller wants it to read.
     *
     *  <p>The store is a mock because nothing here asks a provider for a file:
     *  {@code LocalProvider}'s constructor reads the grants and keeps the store
     *  for later, and {@code name()} touches neither. The channel is a mock for
     *  the same reason — a {@code RemoteProvider} is built over it in the case
     *  that appends one, and nothing here makes it speak. */
    private static RunProviders wiring(SessionRegistry sessions) {
        return new AgentsConfig().runProviders(
                mock(ProjectStore.class), mock(SessionChannel.class), sessions,
                // Empty, and that is the fixture: nothing here roots 'payments',
                // so the question these two tests ask -- whether a session is
                // what appends a machine -- is asked where the answer is now
                // always no. PresenceRoutingTest is where a declared presence is
                // driven.
                new PresenceRegistry(),
                // Names no picture, which is every deployment that keeps no data
                // directory: LocalProvider then behaves as it did before a file
                // read could name one, and nothing here reads a file at all.
                ImageStore.NONE,
                // Nothing here roots a union either, so this is the same "no" as
                // every other seam this fixture leaves empty.
                UnionRouting.NONE, org.mockito.Mockito.mock(io.aeyer.plowshare.server.archive.ProjectMembers.class));
    }

    private static List<String> names(List<FileProvider> providers) {
        return providers.stream().map(FileProvider::name).toList();
    }

    /** The controller, standalone, over a mocked store. The same harness shape
     *  {@code AgentControllerTest} builds, and the same Jackson settings, since
     *  a plain mapper differs from Spring Boot's auto-configured one. */
    private static MockMvc mvc(JobStore jobs) {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        AgentRegistry registry = new AgentRegistry(Map.of("echo", new AgentDefinition(
                "echo", "a fixture", "fast", List.of(), List.of(), List.of(), 2, 4,
                // Exported: this harness reaches the agent over
                // POST /v1/agents/echo/runs, which is one of the four doors that
                // key gates.
                "You do one thing.", true, true)));
        // DataLayout.NONE and an always-false projectExists: no request built
        // in this file names a project a row exists for, so every Caller
        // resolves with a null project id and DefinitionResolver.forCaller
        // answers `registry` before either is ever consulted -- AgentController
        // Test's own javadoc argues the same shape at length.
        DefinitionResolver resolver = new DefinitionResolver(
                registry, DataLayout.NONE, id -> false, Set.of(), Set.of(),
                mock(SessionChannel.class), session -> true, DefinitionChecks.NONE);
        // The services wired as the container wires them: one Callers, shared
        // by the two services that take one. The controller builds none of them.
        ProjectStore projects = mock(ProjectStore.class);
        Turn turns = mock(Turn.class);
        Callers callers = new Callers(resolver, projects, turns, org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class));
        return MockMvcBuilders
                .standaloneSetup(new AgentController(
                        jobs, resolver, projects, callers,
                        new Runs(callers, jobs, turns),
                        new Pictures(ImageStore.NONE),
                        new Passes(jobs, mock(Curator.class), new AgentsProperties()),
                        new Limits(jobs),
                        new Definitions(
                                mock(DefinitionWriter.class), resolver, projects, callers)))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }
}
