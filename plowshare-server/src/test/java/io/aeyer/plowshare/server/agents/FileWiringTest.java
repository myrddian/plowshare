package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.files.Scope;
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
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a run reaches a filesystem: the seam {@link JobRuntime} is wired with, and
 * the tools it produces per run.
 *
 * <p><b>Why this is a per-run seam rather than a set of registered tools.</b> A
 * {@link io.aeyer.plowshare.server.files.LocalProvider} carries one job's tier
 * <em>and one agent's grants</em>, and {@code AgentTool} requires a registered
 * tool to be safe to call from every job at once — so they cannot be built at
 * wiring time the way the memory tools are. {@link AgentRunTool} is the
 * precedent and the shape is copied from it: the runtime is handed the
 * mechanism, builds the tool inside {@code offeredTo}, and names it in {@link
 * JobRuntime#knownTools()} exactly when it holds the mechanism to serve it.
 *
 * <p>The fixtures here are written into a {@link TempDir} rather than added to
 * {@code src/test/resources/agents}, and that is not tidiness: {@code
 * AgentRegistry.load} validates a whole directory against one known-tool set, so
 * a fixture declaring {@code file_read} in the shared directory would refuse
 * every load in {@code JobRuntimeTest} and {@code DelegationTest} — which is the
 * same interlock that makes task 6 run last.
 */
class FileWiringTest {

    // --- scaffolding -------------------------------------------------------------

    /** A transport that answers with what the test queued, in order, and keeps
     *  every conversation it was sent so a tool's result can be read back. */
    private static final class Scripted implements LlmTransport {

        private final List<Completion> steps = new ArrayList<>();
        private final List<List<ChatMessage>> conversations =
                Collections.synchronizedList(new ArrayList<>());
        private final List<List<ToolSchema>> offered =
                Collections.synchronizedList(new ArrayList<>());
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
            conversations.add(List.copyOf(messages));
            offered.add(List.copyOf(tools));
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

        /** The names of the tools the {@code index}-th request offered the model. */
        List<String> toolNames(int index) {
            return offered.get(index).stream().map(ToolSchema::name).toList();
        }

        /** Every tool result in the last conversation this transport was sent. */
        String toolResults() {
            List<ChatMessage> last = conversations.get(conversations.size() - 1);
            return last.stream()
                    .filter(message -> message.role() == ChatMessage.Role.TOOL)
                    .map(ChatMessage::content)
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        }
    }

    /**
     * The seam under test, recording what each run asked it for.
     *
     * <p>It records the {@link Home} and the grants together, because the whole
     * of the wiring's job is that those two arrive from the run rather than from
     * whatever built the runtime.
     */
    private static final class Recording implements RunProviders {

        record Ask(Home home, List<Grant> grants) {}

        private final List<Ask> asks = Collections.synchronizedList(new ArrayList<>());
        private final List<FileProvider> answer;

        Recording(FileProvider... providers) {
            this.answer = List.of(providers);
        }

        // The session is taken and dropped: what arrives in it is {@code
        // SessionSubmissionTest}'s subject, and recording it here as well would
        // be a second place to keep in step with no assertion behind it.
        @Override
        public List<FileProvider> forRun(Home home, List<Grant> grants, String sessionId, String owner) {
            asks.add(new Ask(home, List.copyOf(grants)));
            return answer;
        }

        List<Ask> asks() {
            synchronized (asks) {
                return List.copyOf(asks);
            }
        }
    }

    /** A provider that advertises roots and refuses everything else, which is
     *  all {@code file_roots} needs of one. */
    private record Stub(String name, List<Path> roots) implements FileProvider {

        @Override
        public Span read(Path path, Window window) {
            throw new UnsupportedOperationException("this fixture only advertises roots");
        }

        @Override
        public Span stat(Path path) {
            throw new UnsupportedOperationException("this fixture only advertises roots");
        }

        @Override
        public List<Path> glob(String pattern) {
            throw new UnsupportedOperationException("this fixture only advertises roots");
        }

        @Override
        public Found grep(Needle needle, Path path) {
            throw new UnsupportedOperationException("this fixture only advertises roots");
        }

        @Override
        public io.aeyer.plowshare.server.files.Changed write(Path path, String content) {
            throw new UnsupportedOperationException("this fixture only advertises roots");
        }

        @Override
        public io.aeyer.plowshare.server.files.Changed create(Path path, String content) {
            throw new UnsupportedOperationException("this fixture only advertises roots");
        }

        @Override
        public io.aeyer.plowshare.server.files.Changed edit(Path path, String old, String replacement) {
            throw new UnsupportedOperationException("this fixture only advertises roots");
        }

        @Override
        public io.aeyer.plowshare.server.files.Changed delete(Path path) {
            throw new UnsupportedOperationException("this fixture only advertises roots");
        }

        @Override
        public io.aeyer.plowshare.server.files.Changed move(Path from, Path to) {
            throw new UnsupportedOperationException("this fixture only advertises roots");
        }

        @Override
        public io.aeyer.plowshare.protocol.CommandRunner.Outcome run(Path cwd, List<String> argv,
                io.aeyer.plowshare.protocol.EnvironmentFile.Side side, java.time.Duration timeout,
                java.util.function.BooleanSupplier cancelled) {
            throw new UnsupportedOperationException("no test here runs a command through a fake");
        }
    }

    /** A registered tool under whatever name the test gives it, which is all the
     *  shadowing guard needs to see. */
    private record Named(String name) implements AgentTool {

        @Override
        public ToolSchema schema() {
            return new ToolSchema(name, "a fixture tool",
                    Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public String run(String argumentsJson, Home home) {
            return "nothing";
        }
    }

    private static LlmDispatcher dispatcherOver(LlmTransport transport) {
        return new LlmDispatcher(
                List.of(new LlmPool("scripted", List.of("model-fast"), Map.of("fast", "model-fast"),
                        4, 1, Duration.ofSeconds(5), transport)),
                new NoOpTokenLedger());
    }

    private static Completion done() {
        return new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
    }

    private static Completion asking(String id, String name) {
        return new Completion("", "tool_calls", TokenUsage.UNKNOWN,
                List.of(new ToolCall(id, name, "{}")));
    }

    private static Budget generous() {
        return Budget.of(100);
    }

    /** A definition written the way an operator writes one, loaded the way a
     *  boot loads it: through the registry, against the runtime's own set. */
    private static void write(Path dir, String name, String tools, String scopes) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".md"), "---\n"
                    + "name: " + name + "\n"
                    + "description: a fixture agent\n"
                    + "model: fast\n"
                    + "tools: " + tools + "\n"
                    + (scopes == null ? "" : "scopes: " + scopes + "\n")
                    + "max-turns: 4\n"
                    + "max-model-calls: 8\n"
                    + "---\n"
                    + "You look at files.\n");
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the fixture " + name, e);
        }
    }

    private static AgentDefinition agent(Path dir, String name, JobRuntime runtime) {
        return AgentRegistry.of(dir, runtime.knownTools()).get(name);
    }

    // --- what the boot knows -----------------------------------------------------

    /**
     * The file tools' names are in the known set exactly when the runtime holds
     * something to build them from.
     *
     * <p>Both directions, for {@code knownTools}' own reason: a set that is too
     * broad lets an agent boot naming a tool it will never be offered, and it
     * then simply cannot do what its prompt describes with nothing failing
     * anywhere. This is {@code agent_run}'s rule applied to a second mechanism.
     *
     * <p>Written out rather than taken from {@link FileTools#NAMES}, which is
     * what {@code knownTools} builds its answer from: deriving both sides from
     * one constant would leave this asserting that a field equals itself. The
     * cost is that a tool added to the set has to be added here too, and that is
     * the point — <b>this list is where a new file tool becomes a decision
     * somebody made rather than a name that appeared.</b>
     */
    @Test
    void the_file_tools_are_known_exactly_when_a_run_can_reach_a_filesystem() {
        LlmDispatcher dispatcher = dispatcherOver(new Scripted());

        JobRuntime without = new JobRuntime(dispatcher, List.of());
        JobRuntime with = new JobRuntime(dispatcher, List.of(), null, new Recording());

        // The two result tools are in both and are gated by nothing -- see
        // JobRuntime.knownTools. What this test is about is the six that are
        // gated, and they are still exactly the ones a filesystem brings.
        assertEquals(Set.of(ResultTools.READ_NAME, ResultTools.LIST_NAME),
                without.knownTools());
        // And the name the boot serves is the name the tool registers itself
        // under, read off the tool rather than off the constant. A declaration
        // is checked against this set at load and against
        // AgentDefinition.canRedeem at projection time, so a drift between the
        // two is either a boot failure nobody expected or -- worse, because it
        // is silent -- references handed to an agent offered no tool.
        assertTrue(without.knownTools().contains(
                        new ResultTools.Read(Transcript.NONE).schema().name()),
                "the boot serves a name the tool does not register itself under: "
                        + without.knownTools());
        assertEquals(
                Set.of(FileTools.READ_NAME, FileTools.STAT_NAME, FileTools.GLOB_NAME,
                        FileTools.GREP_NAME, FileTools.EDIT_NAME, FileTools.DELETE_NAME,
                        FileTools.MOVE_NAME, FileTools.ROOTS_NAME, RunTool.NAME,
                        ResultTools.READ_NAME, ResultTools.LIST_NAME),
                with.knownTools());
    }

    /**
     * Every name the boot serves is one the factory builds, and neither list is
     * written out beside the other. A tool added to one and not the other is a
     * name a definition may declare and never be offered.
     *
     * <p>This covers one direction only — a name in the set that {@code of}
     * cannot build. The other, a tool the factory builds under a name the set
     * does not carry, is invisible from here because nothing can enumerate a
     * {@code switch}: {@code FileToolsTest.every_tool_names_itself_and_no_two_share_a_name}
     * is where it is caught, against a list of constructors written out by hand.
     */
    @Test
    void every_known_file_tool_name_builds_the_tool_of_that_name() {
        ProviderRouter router = new ProviderRouter(home -> List.of());

        for (String name : FileTools.NAMES) {
            assertEquals(name, FileTools.of(name, router).schema().name(),
                    "FileTools.of built something other than " + name);
        }
    }

    /** A name outside the set is a wiring bug in the runtime, not a model's
     *  mistake, so it is an exception and not a tool result. */
    @Test
    void a_name_that_is_not_a_file_tool_is_refused() {
        ProviderRouter router = new ProviderRouter(home -> List.of());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> FileTools.of("memory_read", router));

        assertTrue(refused.getMessage().contains("memory_read"), refused.getMessage());
        assertTrue(refused.getMessage().contains(FileTools.READ_NAME), refused.getMessage());
    }

    // --- what a run is given -----------------------------------------------------

    /**
     * The grants a provider is built from are the running definition's own.
     *
     * <p>Two agents whose {@code scopes:} differ, so a wiring that handed over a
     * constant, or the grants of whichever definition ran first, is a different
     * answer rather than the same one. That is the whole of the non-escalation
     * this slice inherits: a job reaches the files its own definition asked for.
     */
    @Test
    void a_run_reaches_the_filesystem_through_the_grants_its_own_definition_declared(
            @TempDir Path dir) {
        // Four calls across two runs: each asks for the tool once, then answers.
        // The seam is asked when a tool actually routes, so a run that never
        // called one would record nothing and prove nothing.
        Scripted transport = new Scripted()
                .then(asking("1", FileTools.ROOTS_NAME))
                .then(done())
                .then(asking("2", FileTools.ROOTS_NAME))
                .then(done());
        Recording providers = new Recording();
        JobRuntime runtime = new JobRuntime(
                dispatcherOver(transport), List.of(), null, providers);
        write(dir, "librarian", "[" + FileTools.ROOTS_NAME + "]", "[workspace:read]");
        write(dir, "editor", "[" + FileTools.ROOTS_NAME + "]", "[workspace:write]");

        runtime.run(agent(dir, "librarian", runtime), "look", Home.of("plowshare"), generous(), null);
        runtime.run(agent(dir, "editor", runtime), "look", Home.of("plowshare"), generous(), null);

        assertEquals(
                List.of(
                        List.of(new Grant(Scope.WORKSPACE, Mode.READ)),
                        List.of(new Grant(Scope.WORKSPACE, Mode.WRITE))),
                providers.asks().stream().map(Recording.Ask::grants).distinct().toList());
    }

    /**
     * And the tier is the run's own, which is the rule {@code AgentTool} states
     * and no file tool may let a model choose.
     */
    @Test
    void the_home_a_run_s_providers_are_built_for_is_the_run_s_own(@TempDir Path dir) {
        Scripted transport = new Scripted().then(asking("1", FileTools.ROOTS_NAME));
        Recording providers = new Recording();
        JobRuntime runtime = new JobRuntime(
                dispatcherOver(transport), List.of(), null, providers);
        write(dir, "librarian", "[" + FileTools.ROOTS_NAME + "]", "[workspace:read]");

        runtime.run(agent(dir, "librarian", runtime), "look", Home.of("excalibur"), generous(), null);

        assertEquals(List.of(Home.of("excalibur")),
                providers.asks().stream().map(Recording.Ask::home).distinct().toList());
    }

    /**
     * The tool the model is offered really is built over the seam: what {@code
     * file_roots} answers is what the provider this run was given advertises.
     *
     * <p>Without this the two tests above would hold for a runtime that asked
     * the seam and threw the answer away.
     */
    @Test
    void what_a_file_tool_answers_comes_from_the_provider_the_run_was_given(@TempDir Path dir) {
        Scripted transport = new Scripted().then(asking("1", FileTools.ROOTS_NAME));
        Recording providers = new Recording(
                new Stub("local", List.of(Path.of("/srv/repo"))));
        JobRuntime runtime = new JobRuntime(
                dispatcherOver(transport), List.of(), null, providers);
        write(dir, "librarian", "[" + FileTools.ROOTS_NAME + "]", "[workspace:read]");

        runtime.run(agent(dir, "librarian", runtime), "look", Home.of("plowshare"), generous(), null);

        assertEquals("local: /srv/repo", transport.toolResults());
    }

    /**
     * A definition may declare fewer than four, and is offered exactly those.
     *
     * <p>The same rule {@code offeredTo} already applies to every other tool:
     * the list offered and the list dispatched against are one map, so an agent
     * cannot reach a capability its definition withholds by naming it.
     */
    @Test
    void a_definition_is_offered_only_the_file_tools_it_declared(@TempDir Path dir) {
        Scripted transport = new Scripted();
        JobRuntime runtime = new JobRuntime(
                dispatcherOver(transport), List.of(), null, new Recording());
        write(dir, "librarian",
                "[" + FileTools.ROOTS_NAME + ", " + FileTools.READ_NAME + "]",
                "[workspace:read]");

        runtime.run(agent(dir, "librarian", runtime), "look", Home.of("plowshare"), generous(), null);

        assertEquals(List.of(FileTools.ROOTS_NAME, FileTools.READ_NAME),
                transport.toolNames(0));
    }

    /**
     * A runtime with no filesystem does not offer a file tool a definition
     * declares, and the run goes on without it.
     *
     * <p>{@code offeredTo}'s existing rule, reached from the new direction: this
     * is the honest case rather than a drift, since a definition validated
     * against a boot that <em>had</em> the seam can be run by one that does not —
     * a curator pass built from a bare runtime, say.
     */
    @Test
    void a_file_tool_is_not_offered_by_a_runtime_with_no_filesystem(@TempDir Path dir) {
        Scripted transport = new Scripted();
        JobRuntime wired = new JobRuntime(
                dispatcherOver(new Scripted()), List.of(), null, new Recording());
        write(dir, "librarian", "[" + FileTools.ROOTS_NAME + "]", "[workspace:read]");
        AgentDefinition librarian = agent(dir, "librarian", wired);
        JobRuntime bare = new JobRuntime(dispatcherOver(transport), List.of());

        Outcome outcome = bare.run(librarian, "look", Home.of("plowshare"), generous(), null);

        assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
        assertEquals(List.of(), transport.toolNames(0));
    }

    // --- what no agent may ever hold ---------------------------------------------

    /**
     * The three names, written out, because this module cannot see them.
     *
     * <p>They are registered in {@code client/tools/ProjectTools}, and {@code
     * plowshare-server} does not and must not depend on {@code
     * plowshare-client}. A literal list in a test is the price of that
     * direction; what stops it going stale is that the day one of them is
     * renamed, the tool disappears from the client's surface and {@code
     * EndToEndTest.tools_list_advertises_the_whole_surface} — which drives the
     * real registry — fails on the name.
     */
    private static final List<String> WORKSPACE_MANAGEMENT =
            List.of("project_define", "project_workspace_set", "project_forget");

    /**
     * Workspace management is not a tool an agent can be granted, and a runtime
     * that reaches a filesystem still serves none of the three.
     *
     * <p>The three live on the client's MCP surface, where the caller is a person
     * deciding what their machine exposes. A definition naming one does not get
     * refused at run time — the tool is <b>never built and never offered</b>,
     * which is the mechanism the spec asks for: an agent that could move its own
     * project's workspace could grant itself the server's configuration, and one
     * that could redefine the directory its own definitions load from could
     * grant itself any tool at the next boot.
     *
     * <p><b>This asks the strict door, which is why it still throws.</b> {@code
     * AgentRegistry.of(dir, knownTools)} is {@code load}: every agent treated as
     * depended upon, and the first fault a refusal. A boot comes through {@code
     * read} instead, where the same fault costs the tool name and not the agent
     * — {@code AgentsConfigTest.no_boot_binds_a_workspace_management_tool} is
     * that half, and the security claim is the same one either way, because it
     * rests on {@code knownTools()} and not on what happens to the file.
     *
     * <p><b>Two claims, and only one of them needs three exemplars.</b> The
     * <em>refusal</em> is one line in {@code AgentRegistry.parse} — {@code
     * !knownTools.contains(tool)} — which cannot distinguish these names from any
     * other, so loading a second and a third definition would measure the same
     * branch three times and hold nothing the first does not. The set this
     * runtime serves is a different claim and is asserted over all three, because
     * {@code knownTools()} is assembled per mechanism and a future one could add
     * a name without touching the others.
     *
     * <p><b>What this cannot see, and where it is seen instead.</b> The hazard of
     * somebody <em>registering</em> {@code project_define} as a shared agent tool
     * lives in {@code AgentsConfig.jobRuntime}, which this test never builds — the
     * runtime here is constructed with an empty tool list by this file. {@code
     * AgentsConfigTest.the_runtime_binds_the_memory_tools_delegation_and_the_file_tools}
     * asserts the boot's set exactly and is what would fail; the
     * assertion below is about this class's own derivation, not about that.
     */
    @Test
    void no_runtime_binds_a_workspace_management_tool(@TempDir Path dir) {
        JobRuntime runtime = new JobRuntime(
                dispatcherOver(new Scripted()), List.of(), null, new Recording());
        write(dir, "sneaky", "[project_workspace_set]", "[workspace:read]");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> AgentRegistry.of(dir, runtime.knownTools()));

        assertTrue(refused.getMessage().contains("project_workspace_set"), refused.getMessage());
        assertEquals(List.of(), WORKSPACE_MANAGEMENT.stream()
                        .filter(runtime.knownTools()::contains).toList(),
                "no runtime may serve a workspace-management tool: "
                        + new TreeSet<>(runtime.knownTools()));
    }

    /**
     * A shared tool registered under a file tool's name is refused, exactly as
     * one registered under {@code agent_run} is.
     *
     * <p>The asymmetry this closes was found by review rather than by a test, and
     * it is worth naming: {@code offeredTo} consults {@link FileTools#NAMES}
     * <em>before</em> it looks a name up among the registered tools, so on a
     * runtime that reaches a filesystem a shared {@code file_read} would be
     * shadowed and never reachable — and <b>{@code knownTools()} could not reveal
     * it</b>, because the registered name and the served name collapse into one
     * entry of the same {@code TreeSet}. That is the failure {@code
     * DelegationTest.a_registered_tool_named_agent_run_is_refused_when_delegation_is_wired}
     * already refuses for the other per-run mechanism.
     *
     * <p>Both halves, because a guard that always fired would be the same bug
     * with the opposite sign: without a filesystem there is nothing to shadow,
     * and the registered tool is served.
     */
    @Test
    void a_registered_tool_named_after_a_file_tool_is_refused_when_a_filesystem_is_wired() {
        LlmDispatcher dispatcher = dispatcherOver(new Scripted());
        List<AgentTool> tools = List.of(new Named(FileTools.READ_NAME));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new JobRuntime(dispatcher, tools, null, new Recording()));

        assertTrue(refused.getMessage().contains(FileTools.READ_NAME), refused.getMessage());
        assertEquals(Set.of(FileTools.READ_NAME, ResultTools.READ_NAME, ResultTools.LIST_NAME),
                new JobRuntime(dispatcher, tools).knownTools());
    }
}
