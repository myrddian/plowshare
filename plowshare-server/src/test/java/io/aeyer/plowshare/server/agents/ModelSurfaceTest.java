package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.files.RunProviders;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What each shipped agent is actually told, pinned to a file.
 *
 * <h2>What this is</h2>
 *
 * <p>For every agent in {@code src/main/resources/agents}, a file under {@code
 * src/test/resources/surface} holds the system message {@link JobRuntime} sends
 * and the tool schemas it offers, verbatim. An edit to any model-facing string —
 * a tool description, a line of an agent's body, the sentence {@code
 * AgentRunTool} builds out of a {@code calls:} list — moves bytes in one of
 * those files, so it arrives in review as a diff of what a model reads rather
 * than as a green build.
 *
 * <p><b>It renders what production renders and has no idea of its own about what
 * a prompt is.</b> Nothing here reads an agent file, assembles a message or
 * builds a schema. It boots the composition {@code AgentsConfig} wires, calls
 * {@link JobRuntime#run}, and records what came out the far end at {@link
 * LlmTransport} — the last layer before HTTP, holding exactly the {@code
 * messages} and {@code tools} the transport would serialise into a request body.
 * {@code opening} and {@code offeredTo} are private, which is right: they are
 * reached the way the model reaches them.
 *
 * <p><b>It pins the bytes and says nothing about whether they are good.</b>
 * That is what review is for, and making it reviewable is the whole point.
 *
 * <h2>Regenerating</h2>
 *
 * <p>When a change to what a model is told is intended, rewrite the files:
 *
 * <pre>{@code
 * ./gradlew :plowshare-server:test --tests '*ModelSurfaceTest' \
 *     -Dplowshare.surface.write=true
 * }</pre>
 *
 * <p>then read {@code git diff} — that diff is the review. The property is
 * forwarded into the test JVM by this module's build file, because a {@code -D}
 * on the command line reaches the Gradle daemon and stops there; it also changes
 * the test task's input fingerprint, so the run is not served from the build
 * cache. Both halves were run rather than assumed.
 *
 * <h2>Reading and writing by path, not through the classpath</h2>
 *
 * <p>The same measurement {@code InterlocutorDefinitionTest} records: both
 * source sets publish an {@code agents} directory, so {@code
 * getResource("/agents")} on a test classpath resolves to the fixtures under
 * {@code build/resources/test}. The pinned files are read by path for a second
 * reason as well — a regeneration has to land in the source tree, where git can
 * show it, and a golden read from {@code build/} would be compared against the
 * copy of itself made before the write.
 *
 * <h2>The one agent whose shipped path is not this one</h2>
 *
 * <p>{@code scribe} runs through {@code Scribe}, which builds {@code
 * ChatMessage.system(definition.prompt())} itself and never offers a tool. Its
 * system message is therefore the same string this file pins and its tool list
 * is empty in both, but the assembly is a different one, and the user message
 * {@code Scribe} composes out of a proposal is not covered here. Pinned anyway:
 * the prompt is the part that gets edited, and leaving one shipped agent out of
 * a completeness guard to preserve a distinction no diff can show would cost
 * more than it says.
 */
class ModelSurfaceTest {

    /**
     * The shipped definitions. By path, not classpath; see the class javadoc.
     *
     * <p><b>Two directories since a bot shipped, and the pinning does not care
     * which is which.</b> What is recorded here is what a model is told, and a
     * bot's prompt is model-facing text in exactly the sense an agent's is —
     * more so, since it is the text a person's ordinary conversation runs on.
     * The two directories are read as one set for the loader's own reason:
     * frontmatter says what a definition is and the directory is an operator's
     * filing.
     */
    private static final List<Path> SHIPPED = List.of(
            Path.of("src/main/resources/agents"), Path.of("src/main/resources/bots"));

    /** Where the pinned surfaces live. One file per agent, named for it. */
    private static final Path PINNED = Path.of("src/test/resources/surface");

    private static final String SUFFIX = ".txt";

    /** Set to {@code true} to rewrite the pinned files; see the class javadoc. */
    private static final String WRITE = "plowshare.surface.write";

    /**
     * The task each rendering is run with.
     *
     * <p>Deliberately not a plausible one. Only its <em>position</em> is a claim
     * this file makes — {@link JobRuntime} puts the utterance being answered
     * last, where a model looks for what it is being asked — and a realistic
     * sentence sitting in a pinned file invites a reader to take it for
     * something an agent is really sent.
     */
    private static final String TASK = "<the task this run was given>";

    /**
     * Pretty-printed with a fixed indenter and a fixed line separator.
     *
     * <p>Jackson's {@link DefaultPrettyPrinter} indents with {@code
     * DefaultIndenter.SYSTEM_LINEFEED_INSTANCE}, whose separator is {@code
     * System.lineSeparator()}. Left alone, every schema in every pinned file
     * would be written with CRLF on a Windows checkout and LF here, which is a
     * whole-file diff caused by the machine rather than by anybody's edit.
     *
     * <p>The spacing change is only legibility: Jackson's default writes {@code
     * "type" : "object"}, and a diff of JSON schemas is read often enough for
     * the space to be worth removing.
     */
    private static final ObjectWriter JSON = new ObjectMapper().writer(
            new DefaultPrettyPrinter()
                    .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                    .withArrayIndenter(new DefaultIndenter("  ", "\n"))
                    .withSeparators(Separators.createDefaultInstance()
                            .withObjectFieldValueSpacing(Separators.Spacing.AFTER)));

    // --- the boot ----------------------------------------------------------------

    /**
     * The transport, holding what one call was asked to send.
     *
     * <p>It answers immediately and with no tool calls, so the run ends after
     * one turn and one model call — which {@link #surfaceOf} asserts, because a
     * second call would mean the recording is of a turn this file cannot claim
     * to be rendering.
     */
    private static final class Capturing implements LlmTransport {

        private List<ChatMessage> messages;
        private List<ToolSchema> tools;
        private int calls;

        @Override
        public String poolName() {
            return "capturing";
        }

        @Override
        public Completion complete(
                String wireModel, List<ChatMessage> messages, Sampling sampling,
                List<ToolSchema> tools) {
            this.messages = messages;
            this.tools = tools;
            this.calls++;
            return new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
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
            throw new UnsupportedOperationException("a job does not embed");
        }

        @Override
        public void close() {
        }
    }

    /**
     * An archive nothing touches.
     *
     * <p>{@code MemoryTools.Recall} and {@code MemoryTools.Read} build their
     * schema in their constructor and hold the archive for {@code run}, which no
     * rendering here reaches: the recorded completion asks for no tool, and the
     * one-call assertion in {@link #surfaceOf} is what keeps that true. Wiring a
     * live archive would mean a Postgres container for a string that is a
     * constant in {@code MemoryTools}.
     */
    private static Archive untouched() {
        return new Archive(null, null, null, 0, 0, 0.0, Instant::now, null);
    }

    /**
     * The runtime {@code AgentsConfig} wires, and the registry it validates
     * against, over a transport that records.
     *
     * <p>The supplier-then-set is the construction cycle production has and not
     * a convenience: {@code AgentRegistry.load} validates every declared tool
     * name against {@link JobRuntime#knownTools()}, so the runtime has to exist
     * before the registry does, and the runtime needs the registry to build
     * {@code agent_run}. {@code AgentsConfig} breaks it with an {@code
     * ObjectProvider}; this breaks it with the same late resolution.
     */
    private record Boot(JobRuntime runtime, AgentRegistry agents, Capturing transport,
            LlmDispatcher dispatcher) implements AutoCloseable {

        @Override
        public void close() {
            dispatcher.close();
        }
    }

    /**
     * @param served the model specifiers the one pool answers to. <b>A pool that
     *     serves none is still enough to ask a runtime {@link
     *     JobRuntime#knownTools()}</b>, which is a fact about the tools wired
     *     into it and not about any pool — and that is the way out of the second
     *     cycle here: a pool cannot be built until the specifiers are known,
     *     they are only known from the definitions, and the definitions cannot
     *     be loaded without a runtime. {@link #shipped} and {@link #models} boot
     *     over a pool serving nothing to read them off; {@link #surfaceOf} then
     *     boots over one that serves them and runs.
     */
    private static Boot boot(Set<String> served) {
        Capturing transport = new Capturing();
        LlmDispatcher dispatcher = new LlmDispatcher(
                List.of(new LlmPool("capturing", List.copyOf(served), Map.of(), 1, 1,
                        Duration.ofSeconds(5), transport)),
                new NoOpTokenLedger(),
                // conversation_folder declares model: system.compaction, which
                // LlmDispatcher.resolveSpecifier never routes to a pool
                // directly — it asks this binding first, same as
                // LlmConfig.jobRuntime asks LlmProperties.systemSpecifier in
                // production. The two-argument constructor binds every such
                // type to "", which is "nothing is bound" and would end this
                // rendering UNAVAILABLE before it ever reached the transport.
                // "reasoning" is not a guess: most shipped agents declare it
                // directly, so it is always among `served` in a boot that
                // runs anything, and it is the class conversation_folder.md's
                // own comment asks for — long-form summarising, not a quick
                // reply.
                type -> "reasoning");
        AtomicReference<AgentRegistry> agents = new AtomicReference<>();
        // A run that renders a surface calls no tool, so nothing ever asks for a
        // provider. It throws rather than returning an empty list so that a
        // rendering which somehow did reach the disk says so instead of quietly
        // becoming a file tool over nothing.
        RunProviders files = (home, grants, sessionId, owner) -> {
            throw new UnsupportedOperationException(
                    "rendering a surface reaches no filesystem");
        };
        JobRuntime runtime = new JobRuntime(
                dispatcher,
                // The composition AgentsConfig.jobRuntime wires. The corpus is
                // mocked and never called for untouched()'s reason exactly: a
                // run that renders a surface calls no tool, and what is being
                // pinned is the SCHEMA the model is shown rather than any answer.
                List.of(new MemoryTools.Recall(untouched()), new MemoryTools.Read(untouched()),
                        // Never reached for the same reason as everything below it: a
                        // run that renders a surface calls no tool, so the scribe
                        // supplier is never invoked. What is pinned is the SCHEMA
                        // code_reviewer.md's and interlocutor.md's tools: lines name.
                        new MemoryTools.Write(untouched(), () -> null),
                        new ConversationTrajectoryTool(
                                org.mockito.Mockito.mock(io.aeyer.plowshare.server.archive.ConversationStore.class),
                                org.mockito.Mockito.mock(io.aeyer.plowshare.server.archive.EntryStore.class)),
                        new MemoryNavigateTool(() -> null, () -> 32),
                        new ConversationSearchTool(org.mockito.Mockito.mock(io.aeyer.plowshare.server.archive.EntryStore.class)),
                        new DocumentTools.Search(org.mockito.Mockito.mock(
                                io.aeyer.plowshare.server.documents.RetrievalService.class)),
                        // The listing beside it, mocked for the same reason and
                        // over the other corpus bean: DocumentsConfig binds both
                        // unconditionally, so a runtime that has one has both.
                        new DocumentTools.AgentList(org.mockito.Mockito.mock(
                                io.aeyer.plowshare.server.documents.DocumentStore.class)),
                        // Asking.NONE and a mocked corpus, for the two reasons
                        // above at once: a run that renders a surface calls no
                        // tool, so neither is ever reached, and what is pinned
                        // is the DESCRIPTION a model is shown rather than any
                        // answer. Wiring a real deliberation here would mean a
                        // registry, a runtime and a Postgres container for a
                        // string that is a constant in AskTool.
                        new AskTool(Asking.NONE, org.mockito.Mockito.mock(
                                io.aeyer.plowshare.server.documents.RetrievalService.class),
                                () -> io.aeyer.plowshare.server.documents.Deliberation.A_PASS),
                        new InformationTool(false, () -> null),
                        new InformationTool(true, () -> null),
                        new GetDateTool(java.time.Instant::now, java.time.ZoneId.systemDefault()),
                        // fetch and search, mocked for the same reason as the
                        // corpus above and never reached for the same one: a
                        // run that renders a surface calls no tool, so neither
                        // FetchService nor SearchService is ever asked
                        // anything, and what is pinned is the SCHEMA
                        // interlocutor.md's tools: line names, not an answer
                        // from the open web.
                        new FetchTool(org.mockito.Mockito.mock(
                                io.aeyer.plowshare.server.fetch.FetchService.class)),
                        new SearchTool(org.mockito.Mockito.mock(
                                io.aeyer.plowshare.server.search.SearchService.class))),
                agents::get,
                files);
        // The todo board AgentsConfig.jobRuntime requires, mocked and never called: a run that
        // renders a surface writes no list, and without one the todo tools' SCHEMAS would be missing
        // from every surface whose definition declares them.
        runtime.useTodos(org.mockito.Mockito.mock(io.aeyer.plowshare.server.todos.TodoLists.class));
        // Merged rather than layered: the two shipped directories are one set
        // at the same tier, so a name in both is a mistake and not an override.
        // AgentRegistry's map constructor validates the merged graph, which is
        // the check a per-directory load could not make.
        // READ AS ONE SET, WHICH IS WHAT THE PARAGRAPH ABOVE ALWAYS MEANT.
        //
        // This loaded each directory in its own call and merged the maps
        // afterwards, which cannot work for a definition that calls across the
        // two: `AgentRegistry.load` validates callees per directory with every
        // name required, so `aristoxenus` calling `code_reviewer` failed the
        // read of `bots/` before any merging happened. The merge was never
        // reached and the map constructor never got to do the check this
        // comment credits it with.
        Map<String, AgentDefinition> definitions = new TreeMap<>(
                AgentRegistry.read(ShippedDefinitions.asOneSet(), runtime.knownTools(),
                        AgentsConfig.REQUIRED).enabled());
        AgentRegistry registry = new AgentRegistry(definitions);
        agents.set(registry);
        return new Boot(runtime, registry, transport, dispatcher);
    }

    /**
     * What the context surface prices is exactly what a run is offered.
     *
     * <p><b>The honesty test for the one number the token breakdown can give.</b>
     * {@code ContextView.Prefix} reports how many characters an agent's tool
     * block is, and that claim is only worth anything if the schemas it measures
     * are the schemas a request carries. {@code JobRuntime.schemasOfferedTo}
     * builds them the way {@code run} does, through the same private {@code
     * offeredTo}; this asserts it against what the transport was really handed
     * during a real run, for every shipped agent, which is the same instrument
     * the pinned files use and the strongest available here.
     *
     * <p>It matters most for {@code agent_run}, whose description is composed
     * out of the definition's {@code calls:} list — a second assembly would
     * describe a different tool and would price it wrongly — and for the file
     * tools, which are built per run over a router this rendering deliberately
     * cannot reach.
     */
    @ParameterizedTest
    @MethodSource("shippedAgents")
    void what_a_context_prices_is_what_a_run_is_offered(String agent) {
        try (Boot boot = boot(models())) {
            AgentDefinition definition = boot.agents().get(agent);
            boot.runtime().run(definition, TASK, Home.global(), Budget.of(1), null);

            assertEquals(boot.transport().tools, boot.runtime().schemasOfferedTo(definition),
                    "the block '" + agent + "' would be priced on is not the block it is"
                            + " offered; a measurement of a separately assembled list is a"
                            + " measurement of something no model reads");
        }
    }

    // --- the rendering -----------------------------------------------------------

    /**
     * One agent's whole model-visible surface, as text.
     *
     * <p>Every string below came off the transport. The headings and the order
     * of the sections are this file's; the messages, their order, the tools,
     * their order, and every character of a description or a schema are {@link
     * JobRuntime}'s.
     */
    private static String surfaceOf(String agent) {
        try (Boot boot = boot(models())) {
            AgentDefinition definition = boot.agents().get(agent);
            Outcome outcome = boot.runtime().run(
                    definition, TASK, Home.global(), Budget.of(1), null);

            // Both of these are about the recording rather than about the
            // agent. An ending other than ANSWERED means the run stopped
            // somewhere this file has not accounted for, and more than one call
            // means the capture is of a later turn — one with tool results in
            // it, which is a conversation and not an opening.
            assertEquals(Outcome.Ending.ANSWERED, outcome.ending(),
                    "rendering '" + agent + "' did not reach the transport cleanly");
            assertEquals(1, boot.transport().calls,
                    "rendering '" + agent + "' made more than the opening call");

            StringBuilder out = new StringBuilder();
            out.append(header(agent));
            out.append("model specifier: ").append(definition.model()).append("\n");
            for (ChatMessage message : boot.transport().messages) {
                out.append("\n=== ").append(message.role().wireName())
                        .append(" message ===\n");
                out.append(message.content()).append("\n");
            }
            if (boot.transport().tools.isEmpty()) {
                out.append("\n=== no tools ===\n");
            }
            for (ToolSchema tool : boot.transport().tools) {
                out.append("\n=== tool: ").append(tool.name()).append(" ===\n");
                out.append(tool.description()).append("\n");
                out.append("\nparameters:\n").append(json(tool.parameters())).append("\n");
            }
            return normalised(out.toString());
        }
    }

    /**
     * The note at the top of every pinned file, for whoever opens one without
     * having read this class.
     */
    private static String header(String agent) {
        return """
                # What the model is told when the agent '%s' runs.
                #
                # GENERATED, and a diff of it is a change to what a model reads. Every
                # line below was recorded at the transport during a real run of this
                # agent through JobRuntime; see ModelSurfaceTest for how, and for how to
                # regenerate this file when the change is meant.
                #
                # The user message is the placeholder that rendering was run with. Only
                # its position is a claim.

                """.formatted(agent);
    }

    private static String json(Map<String, Object> parameters) {
        try {
            return JSON.writeValueAsString(parameters);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    /**
     * LF everywhere, whatever the checkout did to the agent files.
     *
     * <p>An agent's body reaches this rendering as the bytes {@code
     * AgentRegistry} read off disk. A CRLF checkout would put carriage returns
     * through every prompt line and make the whole file differ for a reason that
     * is not an edit.
     */
    private static String normalised(String rendered) {
        return rendered.replace("\r\n", "\n").replace("\r", "\n");
    }

    // --- what is pinned ----------------------------------------------------------

    /**
     * The agents, from the directory rather than from a list somebody keeps in
     * step.
     *
     * <p>This is the completeness guard's near half: a new {@code .md} file
     * becomes a case here on its own, and its first run fails saying there is
     * nothing pinned for it. {@link
     * #the_pinned_surfaces_are_exactly_the_shipped_agents} is the far half, and
     * catches the pinned file that outlives the agent it was rendered from.
     */
    private static Stream<String> shippedAgents() {
        return shipped().stream();
    }

    /**
     * Every agent this server ships, in a fixed order — and no bot.
     *
     * <p><b>A bot is left unpinned on purpose.</b> A bot is a character its
     * owner writes and rewrites; its prompt changing is the ordinary life of the
     * file rather than a regression to be caught in review, so a golden copy of
     * it is a test that fails every time somebody does what bots are for. The
     * agents pinned here are the machinery, whose wording other code relies on.
     * A bot is still booted with the rest, so it still has to load.
     */
    private static Set<String> shipped() {
        try (Boot boot = boot(Set.of())) {
            return boot.agents().names().stream()
                    .filter(name -> !boot.agents().get(name).bot())
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }

    /** Every model specifier the shipped set names, so one pool can serve them
     *  all. Derived, so an agent that moves to another class needs no edit here. */
    private static Set<String> models() {
        try (Boot boot = boot(Set.of())) {
            Set<String> specifiers = new TreeSet<>();
            for (String name : boot.agents().names()) {
                specifiers.add(boot.agents().get(name).model());
            }
            return specifiers;
        }
    }

    private static Set<String> pinnedFiles() {
        if (!Files.isDirectory(PINNED)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(PINNED)) {
            Set<String> names = new TreeSet<>();
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(SUFFIX))
                    .map(name -> name.substring(0, name.length() - SUFFIX.length()))
                    .forEach(names::add);
            return names;
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static boolean rewriting() {
        return Boolean.parseBoolean(System.getProperty(WRITE, "false"));
    }

    // --- the tests ---------------------------------------------------------------

    /**
     * The regeneration, and it happens before any assertion rather than inside
     * one.
     *
     * <p>Measured, on the run that first wrote these files: rewriting from
     * inside the per-agent test left {@link
     * #the_pinned_surfaces_are_exactly_the_shipped_agents} red, because JUnit
     * had already run it against a directory the writes had not reached yet. A
     * regeneration that reports a failure it just fixed is a regeneration
     * somebody runs twice and then stops trusting.
     *
     * <p>Writing every agent here also means the switch does one thing: after
     * it, the assertions below run against the tree exactly as they would on any
     * other run, which is what makes a regeneration self-checking rather than a
     * way of skipping the check.
     *
     * <p>The note below goes where a test's standard error goes, which — measured
     * on this build, whose {@code testLogging} names only passed, skipped and
     * failed — is the HTML and XML test reports and not the console. It is there
     * for whoever opens one; the instrument a regeneration is read with is {@code
     * git diff}.
     */
    @BeforeAll
    static void rewriteThePinnedFilesIfAsked() throws IOException {
        if (!rewriting()) {
            return;
        }
        Files.createDirectories(PINNED);
        for (String agent : shipped()) {
            Files.writeString(PINNED.resolve(agent + SUFFIX), surfaceOf(agent));
        }
        System.err.println(
                "ModelSurfaceTest rewrote the pinned surfaces in " + PINNED + " — read `git"
                        + " diff` before committing: it is the change to what a model is told."
                        + " A file for an agent that no longer ships has to be deleted by"
                        + " hand.");
    }

    /**
     * Every shipped agent's surface is the one on record.
     *
     * <p>The failure is the point of the whole file, so it is written for
     * somebody who did not expect it: it says which agent, which file, that the
     * subject is what a model is told, where the first difference is, and how to
     * accept the change if it was meant.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("shippedAgents")
    void the_model_visible_surface_of_a_shipped_agent_is_what_is_pinned(String agent)
            throws IOException {
        String rendered = surfaceOf(agent);
        Path pinned = PINNED.resolve(agent + SUFFIX);

        assertTrue(Files.isRegularFile(pinned),
                () -> "The agent '" + agent + "' ships in " + SHIPPED + " and nothing records"
                        + " what it tells a model. Every shipped agent's system message and"
                        + " tool schemas are pinned under " + PINNED + " so that an edit to"
                        + " any of them arrives in review as a diff. Write the missing file"
                        + " with -D" + WRITE + "=true and read what it says.");

        String expected = normalised(Files.readString(pinned));
        assertEquals(expected, rendered, () -> difference(agent, pinned, expected, rendered));
    }

    /**
     * A shipped agent with no pinned surface, and a pinned surface with no
     * shipped agent, are both failures.
     *
     * <p>The first direction is already covered by the parameterized test above
     * — a new agent arrives as a case of its own with no file to compare to —
     * and is asserted again here because this is the assertion somebody reads
     * when they add an agent. The second direction is only here: a file that
     * outlives the agent it was rendered from is a record of what nothing is
     * told, and it would sit in the tree being reviewed as if it meant
     * something.
     */
    @Test
    void the_pinned_surfaces_are_exactly_the_shipped_agents() {
        assertEquals(shipped(), pinnedFiles(),
                "The agents in " + SHIPPED + " and the surfaces pinned in " + PINNED + " have"
                        + " drifted apart. An agent with no pinned surface can have every"
                        + " word of its prompt rewritten with nothing to show for it in"
                        + " review; a pinned surface with no agent is a record of what"
                        + " nothing is told. Regenerate with -" + "D" + WRITE + "=true and"
                        + " delete what no longer has an agent.");
    }

    /**
     * The same agent rendered twice is the same bytes.
     *
     * <p>A golden file that varies between runs is a flaky test, and a flaky
     * test is a deleted test. This renders every agent twice in one JVM, which
     * catches an iteration order taken from a hash set — the failure mode {@code
     * ToolSchema} records for {@code Map.copyOf}, where insertion order came
     * back 3 times in 20 — and nothing about a fresh JVM. Two runs of {@code
     * check} are what covers that, and the pinned files are the instrument
     * either way.
     */
    @Test
    void a_surface_rendered_twice_is_the_same_surface() {
        Map<String, String> first = new TreeMap<>();
        List<String> agents = new ArrayList<>(shipped());
        for (String agent : agents) {
            first.put(agent, surfaceOf(agent));
        }
        for (String agent : agents) {
            assertEquals(first.get(agent), surfaceOf(agent),
                    "the surface of '" + agent + "' changed between two renderings in one"
                            + " JVM; something in it is not deterministic and the pinned file"
                            + " cannot hold");
        }
    }

    /**
     * What to tell somebody whose build just went red on a prompt edit.
     *
     * <p>The line number and the two lines are the whole reason this is not a
     * bare {@code assertEquals}: a system prompt is thousands of characters, and
     * "expected: &lt;...&gt; but was: &lt;...&gt;" over two of those is a wall
     * nobody reads to the end of.
     */
    private static String difference(
            String agent, Path pinned, String expected, String rendered) {
        List<String> was = expected.lines().toList();
        List<String> now = rendered.lines().toList();
        int at = 0;
        while (at < was.size() && at < now.size() && was.get(at).equals(now.get(at))) {
            at++;
        }
        String pinnedLine = at < was.size() ? was.get(at) : "(the pinned file ends here)";
        String renderedLine = at < now.size() ? now.get(at) : "(the rendering ends here)";
        return "What the agent '" + agent + "' tells a model has changed.\n\n"
                + "This is not a broken test: " + pinned + " holds the system message and\n"
                + "tool schemas this agent is given, and one of them now differs. If the\n"
                + "change was meant, it is the diff of that file that review needs to see.\n\n"
                + "First difference, at line " + (at + 1) + ":\n"
                + "  pinned:    " + pinnedLine + "\n"
                + "  rendered:  " + renderedLine + "\n\n"
                + "To accept it:\n"
                + "  ./gradlew :plowshare-server:test --tests '*ModelSurfaceTest'"
                + " -D" + WRITE + "=true\n"
                + "then read `git diff` — that diff is what the model stopped and started\n"
                + "seeing.\n";
    }
}
