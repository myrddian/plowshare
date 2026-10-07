package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.JobEvent;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.agents.learner.Learner;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.JobLog;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.PromotionQueue;
import io.aeyer.plowshare.server.archive.ProposalStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.data.DataConfig;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.fetch.FetchService;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.FileStores;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.SessionCloseListener;
import io.aeyer.plowshare.server.files.WorkspaceProperties;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.ToolPre;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.SamplingProfiles;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.UnknownSpecifierException;
import io.aeyer.plowshare.server.search.SearchService;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.union.UnionRouting;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the agent layer starts with, and what it refuses to start with.
 *
 * <p>The same place and the same instinct as {@code LlmConfigTest}: {@link AgentRegistry} already
 * refuses a bad set, but a refusal nothing calls is a check nobody runs. These tests are what say
 * the refusals reach the boot, naming the file an operator has to edit.
 *
 * <p><b>The asymmetry is the design and is driven in both directions.</b> A <em>missing</em>
 * definitions directory starts the server, because a write must never be lost over a scribe that is
 * not deployed — it is filed flat and says so in its own reason, which travels back through {@code
 * memory_write}. A <em>malformed</em> one stops the boot, because a definition somebody wrote and
 * got wrong is a thing they can be told about at the one moment they are watching.
 */
class AgentsConfigTest {

  private static final MemoryProposal PROPOSAL =
      new MemoryProposal(
          "The retry budget is 4 attempts",
          "calling payments",
          "Four, since the timeout change.",
          "claude",
          "a session");

  /**
   * Everything the agent layer needs from the rest of the server, mocked.
   *
   * <p>{@code Archive} and the queue are mocked rather than built: nothing here asks what the
   * archive holds, only whether the beans that need one were given one and whether the boot
   * survives it.
   */
  private ApplicationContextRunner runner(LlmDispatcher dispatcher) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        // DataConfig alongside, because AgentsConfig's ProjectStore now
        // takes DataLayout: the fence names the tree this server owns,
        // and it takes the layout's own already-resolved root rather
        // than re-reading the key. Every context here leaves
        // plowshare.data.dir blank, so the layout is NONE and no
        // directory is created beside the module this suite runs in --
        // which is DataProperties' whole argument for the blank.
        .withUserConfiguration(AgentsConfig.class, DataConfig.class)
        .withBean(FileStores.class, () -> FileStores.NONE)
        .withBean(LlmDispatcher.class, () -> dispatcher)
        // THE SHIPPED PROFILES, and read(null) is what a server with no
        // sampling directory gets -- which is the state most of these
        // contexts are in and the state every one of them should be
        // asserted against. LlmConfig builds this in production; it is
        // named here because AgentsConfig.agentRegistry is where a
        // profile is layered under a definition, and a context missing
        // one would be testing a boot that cannot happen.
        .withBean(SamplingProfiles.class, () -> SamplingProfiles.read(null))
        .withBean(Archive.class, () -> mock(Archive.class))
        .withBean(ProposalStore.class, () -> mock(ProposalStore.class))
        .withBean(PromotionQueue.class, () -> mock(PromotionQueue.class))
        // What AgentsConfig.turn reads. Mocked like the three above and
        // unlike ProjectStore below, because nothing here asks it a
        // question: an unconditional bean in production (ArchiveConfig),
        // named here so that this context is the agent layer's wiring
        // over a stubbed archive rather than a context missing one.
        .withBean(ConversationStore.class, () -> mock(ConversationStore.class))
        // The three stores a turn's transcript is kept in, all wired by
        // ArchiveConfig in production and mocked here for the same
        // reason ConversationStore above is: what this file drives is
        // the agent layer's wiring, and a real store would need a real
        // Postgres in every one of these contexts.
        .withBean(TurnStore.class, () -> mock(TurnStore.class))
        .withBean(CompactionStore.class, () -> mock(CompactionStore.class))
        .withBean(EntryStore.class, () -> mock(EntryStore.class))
        // The durable record of every run, wired by ArchiveConfig in
        // production and mocked here for the reason the four above are.
        // A JobStore built without one would still run jobs, so this is
        // not standing in for something optional: it is here so that the
        // context this file asserts on is the one a boot builds.
        .withBean(JobLog.class, () -> mock(JobLog.class))
        .withBean(
            io.aeyer.plowshare.server.access.ProjectAuthorization.class,
            () -> mock(io.aeyer.plowshare.server.access.ProjectAuthorization.class))
        // The todo board, required by AgentsConfig.jobRuntime because every boot binds the
        // todo tools. TodosConfig in production; mocked for the stores' reason above.
        .withBean(
            io.aeyer.plowshare.server.todos.TodoBoard.class,
            () -> mock(io.aeyer.plowshare.server.todos.TodoBoard.class))
        // A real ProjectStore over a mocked JdbcTemplate, not a mocked
        // store: the configuration file and the sampling directory it
        // is constructed with are what
        // the_mandatory_exclusions_name_this_deployment_s_own_files
        // is about, and a mock would answer for neither.
        .withBean(
            io.aeyer.plowshare.server.archive.ProjectMembers.class,
            () -> mock(io.aeyer.plowshare.server.archive.ProjectMembers.class))
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        // The file channel and the session registry, which
        // AgentsConfig.runProviders needs in order to decide whether a
        // run reaches the machine that submitted it. Both are
        // unconditional beans in production — FileChannelConfig and
        // EventChannelConfig — and neither configuration is imported
        // here, because what this file drives is the agent layer's own
        // wiring and importing a WebSocket config to reach it would put
        // a Tomcat in every one of these contexts.
        .withBean(SessionChannel.class, () -> mock(SessionChannel.class))
        .withBean(SessionRegistry.class, SessionRegistry::new)
        // And which session roots which project, which is what decides
        // WHICH machine rather than whether there is one. Empty in every
        // context here: nothing in this file opens a socket, so nothing
        // declares a presence, and a run in a named project is one whose
        // project is rooted nowhere.
        .withBean(PresenceRegistry.class, PresenceRegistry::new)
        // Where a picture in a project's own file is NAMED, which
        // AgentsConfig.runProviders hands to LocalProvider. NONE, and
        // that is the honest fixture: every context here leaves
        // plowshare.data.dir blank, so the layout is NONE and a real
        // ImagesConfig would build exactly this. A server that names no
        // picture refuses one as it always did.
        .withBean(ImageStore.class, () -> ImageStore.NONE)
        // Whether a project is a union this server mirrors, which
        // AgentsConfig.runProviders and .projectWhereabouts both take.
        // An unconditional bean in production -- UnionConfig -- and
        // supplied directly here for the reason the channels above are:
        // importing that configuration would put a git servlet and a
        // JdbcTemplate-backed store in every one of these contexts to
        // reach a wiring this file does not drive. NONE routes nothing,
        // which is the honest fixture: nothing here is a union.
        .withBean(UnionRouting.class, () -> UnionRouting.NONE)
        // What AgentsConfig.compaction reads one number off:
        // plowshare.llm.default-context-length, the bottom tier of
        // context-length resolution and the reason a fold cannot be
        // switched off by a node that will not say how big it is. Bound
        // by LlmConfig in production; that configuration is not imported
        // here for the reason the channels above are not, so the bean is
        // supplied directly. The value is arbitrary and deliberately not
        // 64 000 — nothing in this file takes a turn, so nothing folds,
        // and a fixture repeating the shipped number would read as a
        // second place it lives.
        .withBean(
            LlmProperties.class,
            () -> {
              LlmProperties llm = new LlmProperties();
              llm.setDefaultContextLength(50_000);
              return llm;
            })
        // The corpus, which document_search is built over. An
        // unconditional bean in production — DocumentsConfig — and
        // supplied directly here for the reason the channels above are:
        // importing that configuration would put a JdbcTemplate and an
        // EmbeddingClient in every one of these contexts to reach a
        // wiring this file does not drive. Mocked and never called: what
        // these tests read off the runtime is which tool NAMES it binds.
        .withBean(RetrievalService.class, () -> mock(RetrievalService.class))
        // The corpus as rows, which document_list is built over. The
        // same bean GET /v1/documents reads and the same unconditional
        // DocumentsConfig binds it, supplied directly here for the
        // reason above and mocked for it too: what these tests read off
        // the runtime is which tool NAMES it binds.
        .withBean(DocumentStore.class, () -> mock(DocumentStore.class))
        .withBean(
            io.aeyer.plowshare.server.documents.CitationStore.class,
            () -> mock(io.aeyer.plowshare.server.documents.CitationStore.class))
        // What fetch is built over. An unconditional bean in
        // production -- FetchConfig -- and supplied directly here for
        // the same reason the corpus is: importing that configuration
        // would put an OkHttpClient and a Postgres-backed store in
        // every one of these contexts to reach a wiring this file does
        // not drive. Mocked and never called, for the corpus's own
        // reason.
        .withBean(FetchService.class, () -> mock(FetchService.class))
        // What search is built over. An unconditional bean in
        // production -- SearchConfig -- and supplied directly here for
        // the same reason FetchService above is: importing that
        // configuration would put an OkHttpClient and a
        // Postgres-backed ladder in every one of these contexts to
        // reach a wiring this file does not drive. Mocked and never
        // called, for the corpus's own reason.
        .withBean(SearchService.class, () -> mock(SearchService.class));
  }

  private ApplicationContextRunner runner() {
    return runner(mock(LlmDispatcher.class));
  }

  /**
   * {@code runner()}, named the way the brief for the shipped-floor tests names it. No different
   * wiring -- see {@link #runner()}.
   */
  private ApplicationContextRunner contextRunner() {
    return runner();
  }

  /**
   * {@code runner()} with {@code plowshare.data.dir} pointed at {@code data}, which is what makes
   * {@code AgentsConfig.agentRegistry} layer {@code global/agents/} and {@code global/bots/} under
   * the shipped seed instead of booting on the seed alone.
   */
  private ApplicationContextRunner contextRunnerWithDataDir(Path data) {
    return runner().withPropertyValues("plowshare.data.dir=" + data.toAbsolutePath());
  }

  /**
   * A valid {@code librarian.md} carrying a body nobody shipped, so a test can write it into {@code
   * global/agents/} and assert that it -- and not the real shipped file of the same name -- is what
   * the registry serves.
   *
   * <p>{@code librarian} rather than an arbitrary name: it is a real shipped agent, so this
   * exercises {@link LayeredDefinitions}' "first layer to name something wins it" rule against the
   * actual file it would otherwise shadow, not a name that was never shipped in the first place.
   * Only the fields {@code AgentRegistry.load} actually validates are here -- the shipped file's
   * own tool grant, so the name-collision boot does not fail for an unrelated reason.
   */
  private static String shippedLibrarianWithBody(String body) {
    return "---\n"
        + "name: librarian\n"
        + "description: a fixture agent\n"
        + "model: reasoning\n"
        + "tools: [document_search, document_list, result_read, result_list]\n"
        + "calls: []\n"
        + "max-turns: 2\n"
        + "max-model-calls: 4\n"
        + "---\n"
        + body
        + "\n";
  }

  // --- what starts ---------------------------------------------------------------

  @Test
  void the_required_builder_is_checked_and_served_at_boot() {
    LlmDispatcher dispatcher = mock(LlmDispatcher.class);
    runner(dispatcher)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var builder =
                  context
                      .getBean(OrchestrationResolver.class)
                      .bootSet()
                      .get("design_orchestration");
              assertNotNull(builder);
              assertEquals("system.authoring", builder.conductor().model());
              verify(dispatcher).requireServed("system.authoring");
            });
  }

  @Test
  void an_unserved_system_authoring_model_stops_boot() {
    LlmDispatcher dispatcher = mock(LlmDispatcher.class);
    doThrow(new UnknownSpecifierException("system.authoring", "fast, reasoning"))
        .when(dispatcher)
        .requireServed("system.authoring");
    runner(dispatcher)
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String said = trail(context.getStartupFailure());
              assertTrue(
                  said.contains("required system orchestration 'design_orchestration'"), said);
              assertTrue(said.contains("system.authoring"), said);
            });
  }

  @Test
  void a_broken_global_builder_stops_boot_instead_of_disabling_authoring(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Path definitions = layout.orchestrationsFor(null);
    Files.createDirectories(definitions);
    Files.writeString(definitions.resolve("design_orchestration.md"), "bad global builder\n");
    contextRunnerWithDataDir(data)
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String said = trail(context.getStartupFailure());
              assertTrue(
                  said.contains("required system orchestration 'design_orchestration'"), said);
              assertTrue(said.contains(definitions.toString()), said);
            });
  }

  @Test
  void a_valid_definitions_tier_starts(@TempDir Path dir) {
    write(dir, "echo.md", agent("echo", "fast", "[]", null));

    runner()
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              AgentRegistry registry = context.getBean(AgentRegistry.class);
              assertNotNull(registry);
              // Not an exact List.of("echo") any more: the shipped classpath
              // seed is always layered underneath global/agents, so "echo" joins
              // the shipped set rather than replacing it.
              assertTrue(registry.names().contains("echo"), registry.names().toString());
            });
  }

  /**
   * The learner is wired, and the compaction that rings its bell is wired over it.
   *
   * <p><b>Not a bean nobody reaches.</b> The whole of what starts a learning pass is a fold calling
   * {@link Learning#thereIsMaterial()}, so a context holding a {@code Learner} that no {@code
   * Compaction} was given is a server that would never mine anything and would look perfectly
   * healthy doing it. {@code AgentsConfig.compaction} takes the hook through an {@code
   * ObjectProvider} and falls back to {@link Learning#NONE}, which is what makes that failure
   * silent — so it is asserted here rather than assumed.
   */
  @Test
  void the_learner_is_wired_and_is_what_a_fold_tells() {
    // No directory property to set: the now-retired directory key was
    // already inert for the registry, which always has the shipped seed
    // regardless of any directory named. This fixture sets nothing, so
    // it does not imply a key that no longer exists does something.
    runner()
        .run(
            context -> {
              assertNotNull(context.getBean(Learner.class));
              assertSame(
                  context.getBean(Learner.class),
                  context.getBean(Learning.class),
                  "the learner is the Learning this context holds, so a fold that"
                      + " rings the bell reaches it");
              assertNotNull(context.getBean(Compaction.class));
            });
  }

  /**
   * The definitions this repository ships are a set this boot accepts.
   *
   * <p>The one test that could catch a shipped file drifting away from the tools the wiring
   * actually binds — {@code promotion_judge.md} declares {@code memory_read}, and {@code
   * AgentRegistry.load} refuses a definition naming a tool this boot does not register. Read by
   * path from {@code src/main/resources} rather than through the classpath, measured by Task 8 and
   * repeated here: both source sets publish an {@code agents} directory, so {@code
   * getResource("/agents")} in a test resolves to {@code build/resources/test/agents} and would
   * validate the fixtures instead.
   */
  @Test
  void the_shipped_definitions_are_a_set_this_boot_accepts() {
    // No directory property: see the_learner_is_wired_and_is_what_a_fold_tells.
    runner()
        .run(
            context -> {
              AgentRegistry shipped = context.getBean(AgentRegistry.class);
              assertTrue(shipped.names().contains(Scribe.AGENT), shipped.names().toString());
              assertTrue(shipped.names().contains(Curator.AGENT), shipped.names().toString());
            });
  }

  /**
   * The exact inventory this repository ships, pinned once, in one place.
   *
   * <p><b>The one exact tripwire left in this file.</b> Every other test that touches a booted
   * {@code AgentRegistry} now asserts {@code contains} or {@code containsAll}, because the shipped
   * classpath seed is always layered into every context and a booted registry's {@code names()} is
   * therefore never an isolated set to pin exactly -- see {@link #shippedNames()}'s own javadoc.
   * That is the right call for a booted registry, and it is also exactly the gap a shipped agent
   * quietly added or removed could hide in: nothing anywhere would fail. This test closes it by
   * asserting over the shipped inventory directly, read by path rather than through a boot, so it
   * does not need {@code containsAll} to stay honest about contamination it is not subject to.
   */
  @Test
  void the_shipped_inventory_is_exactly_these_thirty_two_names() {
    assertEquals(
        List.of(
            "acceptance_checker",
            "ask_critic",
            "ask_proposer",
            "ask_reviewer",
            "ask_synthesiser",
            "call_validator",
            "chapter_summariser",
            "close_reader",
            "code_reviewer",
            "coder",
            "command_judge",
            "conversation_folder",
            "critic",
            "diagnosis_verifier",
            "document_summariser",
            "image_reader",
            "information_tag_grouper",
            "information_tagger",
            "interlocutor",
            "learner",
            "librarian",
            "paragraph_summariser",
            "promotion_judge",
            "research_analyst",
            "research_critic",
            "researcher",
            "schedule_reader",
            "scribe",
            "section_summariser",
            "span_summariser",
            "spec_writer",
            "stuck_advisor",
            "test_designer"),
        List.copyOf(shippedNames()));
  }

  /** The call validator a context provides is the one every run consults (spec 2026-09-28 §5). */
  @Test
  void the_runtime_consults_the_call_validator_the_context_provides() {
    CallValidator validator =
        (question, cancelled) ->
            new CallValidator.Verdict(CallValidator.Verdict.UNSURE, null, "a test's");

    runner()
        .withBean(CallValidator.class, () -> validator)
        .run(context -> assertSame(validator, context.getBean(JobRuntime.class).validator()));
  }

  /** And a context that provides none runs with none, rather than failing to boot. */
  @Test
  void a_context_with_no_call_validator_runs_with_none() {
    runner()
        .run(
            context ->
                assertSame(CallValidator.NONE, context.getBean(JobRuntime.class).validator()));
  }

  /**
   * No shipped agent's turn cap is the thing that stops a run on its own allowance.
   *
   * <p><b>This assertion is the inverse of the one it replaces, and the inversion is the change
   * rather than an accident of it.</b> It used to read {@code maxModelCalls >= maxTurns}, argued
   * this way: {@code JobRuntime}'s loop checks {@code turns >= maxTurns} <em>before</em> it spends,
   * so a run ending at its cap has made exactly {@code maxTurns} calls, and a smaller budget was a
   * number the file asserted and the runtime disproved. Every word of that mechanism is still true.
   * What changed is which of the two is <em>supposed</em> to bind.
   *
   * <p>Both looping agents shipped with the two numbers equal, so {@code TURN_CAP} and {@code
   * CALL_BUDGET} fired at the same moment for the same run — while {@code Outcome} argued they were
   * distinct because the fix differs, one being an agent that needs more room and the other a tree
   * spending more than it was given. True in principle and indistinguishable in practice. The turn
   * cap is now a runaway guard above the budget, so what this holds is that <b>a run started on an
   * agent's own allowance is bounded by cost and never by count</b>: it cannot reach its cap
   * without first spending everything it was given. A definition that broke this would have a
   * backstop that is really its binding constraint, which is the fault the whole slice was written
   * against.
   *
   * <p>Equality still satisfies it, and two shipped definitions still have it: {@code
   * promotion_judge.md} and {@code scribe.md} are not looping agents — four turns and one — and
   * their own comments argue those numbers. What equality means for them is that the two bounds
   * coincide, which is honest for a run that ends in two turns; it is only misleading for an agent
   * whose cap is meant to be a backstop.
   *
   * <p><b>The history is why it is worth a test at all.</b> {@code promotion_judge.md} shipped with
   * {@code max-model-calls: 3} against a cap of 4, contradicted by {@code
   * CuratorTest.a_judge_that_hits_its_turn_cap_leaves_one_candidate_undecided} — a passing test in
   * the same commit. Its numbers are inert for a curator pass, where a shared {@code Budget}
   * governs and this one is never read; this is what keeps the file honest anyway.
   *
   * <p><b>One owner, walking the directory.</b> The assertion once stood in two places — {@code
   * CuratorTest} and {@code CodeReviewerDefinitionTest} — with the same message word for word, each
   * reading one definition, which left {@code scribe.md} covered by neither. Walking every
   * definition the boot loaded covers all of them and holds the next one for free.
   *
   * <p><b>Deliberately not a rule in the loader</b>, and that was checked rather than assumed:
   * {@code src/test/resources/agents/frugal.md} declares {@code max-turns: 5} with {@code
   * max-model-calls: 1} and {@code spendthrift.md} 10 against 2, both on purpose — and both are now
   * the shape this test asks the shipped set for rather than the shape it used to forbid. It is a
   * property of what <em>this repository ships</em>, which is a test's job and not a parser's.
   */
  @Test
  void no_shipped_agent_reaches_its_turn_cap_before_it_has_spent_its_budget() {
    runner()
        .run(
            context -> {
              AgentRegistry shipped = context.getBean(AgentRegistry.class);
              // Walked over shippedNames() and not shipped.names(): the
              // registry now always layers the classpath seed, which in
              // this module's own test JVM aggregates
              // src/test/resources/agents too, and frugal.md /
              // spendthrift.md in there declare this exact violation on
              // purpose -- see this method's own javadoc. Asserted
              // non-empty rather than assumed, for trap 5's reason: a
              // walk over an empty set passes without looking at
              // anything.
              Set<String> names = shippedNames();
              assertTrue(shipped.names().containsAll(names), shipped.names().toString());
              assertFalse(names.isEmpty());
              for (String name : names) {
                AgentDefinition definition = shipped.get(name);
                assertTrue(
                    definition.maxModelCalls() <= definition.maxTurns(),
                    name
                        + " has max-model-calls "
                        + definition.maxModelCalls()
                        + " above max-turns "
                        + definition.maxTurns()
                        + ", so its turn cap is what stops a run on its own"
                        + " allowance rather than its budget");
              }
            });
  }

  /**
   * A shipped agent's resolved sampling is what its request carries, only the eight named files
   * declare an intent, and none of them asserts a number.
   *
   * <h2>Three claims in one walk, because they are the three halves of this key being useful</h2>
   *
   * <p>The first is that the thread is connected: {@link JobRuntime#requestFor} is the one place a
   * definition becomes a request, so what the loader resolved is what reaches the wire, and a
   * definition whose value stopped between the two would fail here rather than at whatever an
   * operator noticed months later.
   *
   * <p>The second is <b>what the shipped set actually declares</b>. {@link #DECLARED} is the whole
   * of it — the deliberation's three, the ingest cascade's five and the per-document reader — and a
   * sixteenth agent quietly warming itself fails here.
   *
   * <p>The third is that <b>no shipped agent asserts a number of its own</b>. {@code temperature:}
   * survives as an override and is the right key for an agent with a real model-specific need, but
   * a file that declares an intent and then overrides it is a file where the vocabulary looks
   * connected and does nothing.
   *
   * <h2>Why this cannot pass against a severed thread</h2>
   *
   * <p><b>Every definition is also asked the question at a value nothing ships</b>, which is stage
   * 6's technique and the reason it exists: under this context the dispatcher is a mock, no wire
   * model resolves, and every agent's sampling is {@link Sampling#NONE} — so asserting that the
   * definition and the request agree would pass just as happily against a {@code requestFor} that
   * dropped sampling on the floor, since {@code ChatRequest.of} builds at {@code NONE} too. Trap 5:
   * an instrument that cannot record the thing it is cited for.
   *
   * <p>So each definition is asked again carrying a temperature of 0.7 and a {@code top_k} of 11,
   * neither of which appears in any file or any profile in this repository. Measured rather than
   * argued: with the {@code withSampling} call deleted from {@code requestFor}, this test fails on
   * the first agent the walk reaches.
   *
   * <p><b>Two parameters and not one</b>, which is new. A single temperature would pass against a
   * {@code requestFor} that had been "helpfully" narrowed to carry only a temperature across —
   * which is precisely the shape of the bug this design is about, a layer keeping one half of a
   * recommendation and dropping the other.
   */
  @Test
  void a_shipped_agents_resolved_sampling_is_what_its_request_carries() {
    runner()
        .run(
            context -> {
              AgentRegistry shipped = context.getBean(AgentRegistry.class);
              // Walked over shippedNames() and not shipped.names(), for
              // no_shipped_agent_reaches_its_turn_cap_before_it_has_spent_its_budget's
              // reason: the registry now always layers the classpath
              // seed, which aggregates src/test/resources/agents too in
              // this module's own test JVM. Asserted non-empty rather
              // than assumed, for the reason the walk above asserts it:
              // an empty set passes without looking.
              Set<String> names = shippedNames();
              assertTrue(shipped.names().containsAll(names), shipped.names().toString());
              assertFalse(names.isEmpty());
              for (String name : names) {
                AgentDefinition definition = shipped.get(name);
                assertEquals(
                    DECLARED.getOrDefault(name, AgentDefinition.DEFAULT_INTENT),
                    definition.intent(),
                    name
                        + " declares the sampling intent "
                        + definition.intent().declared()
                        + ", which is not what"
                        + " this repository ships it at. Only the nine named"
                        + " in DECLARED name the key, and every other file's"
                        + " silence is what says its sampling has not been"
                        + " reasoned about and must not move");
                // THE SAMPLING PARAMETERS, AND NOT THE WHOLE RECORD.
                //
                // This compared against Sampling.NONE until 2026-09-07,
                // when `schema:` arrived on an agent and this caught
                // image_reader. It was right about its rule and wrong
                // about its reach: `responseFormat` rides on Sampling
                // because `carries()` is a Set<Sampling.Parameter> and
                // that is the bag a transport filters on -- but a schema
                // is NOT a model fact. It is a contract, true on every
                // endpoint, and stating it is the whole point of an
                // agent that answers in a shape.
                //
                // What the rule is actually about is the five values a
                // profile resolves: a file naming a temperature has
                // decided something about a model it cannot see, and
                // silently outranks the profile its own intent chose.
                // Those are asserted; the schema is not one of them.
                Sampling declared = definition.sampling();
                assertEquals(
                    Sampling.NONE,
                    new Sampling(
                        declared.temperature(),
                        declared.topP(),
                        declared.topK(),
                        declared.maxTokens(),
                        declared.reasoningEffort(),
                        java.util.Optional.empty()),
                    name
                        + " asserts an explicit sampling value as well as an"
                        + " intent. That is a model fact stated by a file that"
                        + " cannot see the model, and it silently overrides"
                        + " whatever profile the intent resolved through");
                assertEquals(
                    definition.sampling(),
                    JobRuntime.requestFor(definition, List.of(ChatMessage.user("ask"))).sampling(),
                    name
                        + " resolved to "
                        + definition.sampling().described()
                        + " and the request built for it carries something"
                        + " else, so what its file says about sampling is not"
                        + " what reaches the endpoint");
                assertEquals(
                    UNSHIPPED,
                    JobRuntime.requestFor(
                            at(definition, UNSHIPPED), List.of(ChatMessage.user("ask")))
                        .sampling(),
                    name
                        + " resolved to a temperature of 0.7 and a top_k of 11"
                        + " still builds a request that does not carry both, so"
                        + " the assertion above is agreeing that two empty"
                        + " values happen to match rather than that a"
                        + " resolution reaches the wire");
              }
            });
  }

  /**
   * A configuration no file and no profile in this repository produces.
   *
   * <p>Two parameters on purpose; see the walk above for why one would not be an instrument.
   */
  private static final Sampling UNSHIPPED = Sampling.NONE.withTemperature(0.7d).withTopK(11);

  /**
   * The whole of what this repository ships a <em>declared</em> sampling intent for.
   *
   * <p>Two groups and they are declared for opposite reasons. Anchor's {@code ask.temperatures.*}
   * spread sits on the three agents of the per-document deliberation, ported as the ordering it
   * always was — 0.3 / 0.0 / 0.2 is relative, and correct only on whatever model Anchor was tuned
   * against. The five summarisers of the ingest cascade are all {@code precise}, and that one is a
   * correction rather than a port: they inherited {@code ChatRequest.of}'s hardcoded 0.0 because
   * until stage 6 there was no key to write, and {@code implementation rationale} measured greedy
   * decoding driving that cascade into reproducible repetition loops on a model tuned for sampling.
   *
   * <p>Every other shipped file names no {@code sampling:} key at all and takes {@link
   * AgentDefinition#DEFAULT_INTENT} — the same value and a different statement: a file that has not
   * written one has not decided.
   */
  private static final java.util.Map<String, Sampling.Intent> DECLARED =
      // ofEntries and not of: `Map.of` takes ten pairs and the eleventh
      // arrived with image_reader, as a compile error rather than a
      // failure -- the same conversion AgentRegistry.WITHHELD needed for
      // the same reason. Nothing was dropped in it; the count below is
      // what would say so.
      java.util.Map.ofEntries(
          java.util.Map.entry("ask_proposer", Sampling.Intent.EXPLORATORY),
          java.util.Map.entry("ask_critic", Sampling.Intent.PRECISE),
          java.util.Map.entry("ask_reviewer", Sampling.Intent.PRECISE),
          java.util.Map.entry("ask_synthesiser", Sampling.Intent.BALANCED),
          java.util.Map.entry("paragraph_summariser", Sampling.Intent.PRECISE),
          java.util.Map.entry("span_summariser", Sampling.Intent.PRECISE),
          java.util.Map.entry("section_summariser", Sampling.Intent.PRECISE),
          java.util.Map.entry("chapter_summariser", Sampling.Intent.PRECISE),
          java.util.Map.entry("document_summariser", Sampling.Intent.PRECISE),
          java.util.Map.entry("information_tagger", Sampling.Intent.PRECISE),
          java.util.Map.entry("information_tag_grouper", Sampling.Intent.PRECISE),
          // The ninth, and the one whose argument is about COPYING
          // rather than about thinking: what this agent must reproduce
          // character for character is a paragraph id and a quotation
          // that has already been checked against that paragraph, so a
          // sampled token in either turns a verified citation into a
          // wrong one under a heading saying it was verified.
          java.util.Map.entry("close_reader", Sampling.Intent.PRECISE),
          // The eleventh, and the first whose reason is not about the
          // words: a picture is read once and the reading is the whole
          // answer, so a sampled token is a detail nobody can check
          // against the frame the way a quotation is checked against
          // its paragraph.
          java.util.Map.entry("image_reader", Sampling.Intent.PRECISE));

  /**
   * One shipped definition, sampled as nothing ships.
   *
   * <p>{@link AgentDefinition#sampling(Sampling)} rather than a fixture built from nothing, so that
   * what is being asked about is a definition the boot actually produced with one field moved — and
   * so that it is the same copy method the loader itself uses to layer a profile.
   */
  private static AgentDefinition at(AgentDefinition definition, Sampling sampling) {
    return definition.sampling(sampling);
  }

  /**
   * The layering, end to end, on a dispatcher that resolves a real model.
   *
   * <h2>This is the test that says the behaviour moved</h2>
   *
   * <p>Every other assertion in this file about sampling holds under a mocked dispatcher, where
   * nothing resolves and every agent sends nothing. That is the honest default and it is worth
   * pinning, but it cannot show that a profile does anything. Here the dispatcher answers {@code
   * qwen3.5-9b} — which is what {@code application.yml} ships as the model for <em>both</em> the
   * {@code fast} and the {@code reasoning} class — and the shipped mapping sends that to the {@code
   * qwen3} profile in thinking mode.
   *
   * <p>So {@code ask_critic}, which asked for the most reproducible thing it could have, no longer
   * sends {@code temperature 0.0}. It sends the model's own recommended temperature with the
   * nucleus pulled in, which is what "reproducible" means on a model whose vendor forbids greedy
   * decoding in as many words.
   */
  @Test
  void a_declared_intent_resolves_through_the_profile_for_the_model_in_use() {
    LlmDispatcher serving = mock(LlmDispatcher.class);
    when(serving.wireModelFor(anyString())).thenReturn("qwen3.5-9b");

    // No directory property: see
    // the_learner_is_wired_and_is_what_a_fold_tells.
    runner(serving)
        .run(
            context -> {
              AgentRegistry shipped = context.getBean(AgentRegistry.class);
              Sampling critic = shipped.get("ask_critic").sampling();

              assertEquals(
                  java.util.OptionalDouble.of(0.6d),
                  critic.temperature(),
                  "ask_critic used to send temperature 0.0 — the configuration"
                      + " measured returning 3 997 reasoning tokens and empty"
                      + " content, on the family whose vendor forbids greedy"
                      + " decoding by name");
              assertEquals(java.util.OptionalDouble.of(0.8d), critic.topP());
              assertEquals(java.util.OptionalInt.of(20), critic.topK());

              // And the request built from it carries all three, which is
              // the thread the walk above can only test at NONE.
              assertEquals(
                  critic,
                  JobRuntime.requestFor(shipped.get("ask_critic"), List.of(ChatMessage.user("ask")))
                      .sampling());

              // An agent that declares nothing still resolves, because the
              // absent key means `balanced` rather than "leave me alone".
              assertEquals(
                  java.util.OptionalDouble.of(0.95d),
                  shipped.get("scribe").sampling().topP(),
                  "an agent with no sampling: key takes the middle of the"
                      + " vocabulary, and the middle is still a row in a"
                      + " profile");
            });
  }

  /**
   * And for the agents that loop, the two numbers are not the same number.
   *
   * <p>Separate from the walk above because it is a different claim about a smaller set. Equality
   * passes that one and is exactly the state this slice exists to leave: a run in which {@code
   * TURN_CAP} and {@code CALL_BUDGET} are one event with two names, so an operator reading either
   * learns nothing about which fix to reach for. It is only meaningful for an agent whose cap is
   * meant to be a backstop, which is why it names those three rather than walking the directory.
   *
   * <p><b>{@code librarian} is the third, and its cap is doing more work than the other two's.</b>
   * What makes a hundred safe on those is {@code JobRuntime.Repeats}, which ends a run that keeps
   * asking for one <em>identical</em> call. The librarian's runaway is a rephrase loop — {@code
   * document_search}'s own empty answer invites one — and every call in it carries different
   * arguments, so {@code Repeats} never sees it and the cap is the only thing that ends it. That is
   * the reason its two numbers are twenty and twelve rather than a hundred and forty, and it is why
   * the separation asserted here matters more for it than for either of the others.
   */
  @Test
  void the_shipped_agents_that_loop_do_not_have_one_number_doing_both_jobs() {
    // No directory property: see the_learner_is_wired_and_is_what_a_fold_tells.
    runner()
        .run(
            context -> {
              AgentRegistry shipped = context.getBean(AgentRegistry.class);
              for (String name : List.of("interlocutor", "code_reviewer", "librarian")) {
                AgentDefinition definition = shipped.get(name);
                assertTrue(
                    definition.maxTurns() > definition.maxModelCalls(),
                    name
                        + " caps turns at "
                        + definition.maxTurns()
                        + " and calls at "
                        + definition.maxModelCalls()
                        + ", so both endings fire at the same moment for the"
                        + " same run");
              }
            });
  }

  /**
   * The set the tests assemble by hand is the set this boot derives.
   *
   * <p><b>{@code BoundTools.boundByThisServer()} is a second derivation of {@code
   * JobRuntime.knownTools()}, and this is the only thing standing between the two.</b> {@code
   * knownTools()} builds its answer from the tools actually registered on the runtime plus two
   * flags; {@code BoundTools} reassembles the composition rule from the tool layer's constants.
   * Three tests hand that hand-made set to {@code AgentRegistry.load} over the shipped directory,
   * and {@code load}'s own javadoc says at length why a set that is <em>too broad</em> is the
   * dangerous direction: every name in it is a name the unknown-tool refusal waves through.
   *
   * <p>They agree today. They can stop agreeing without anything else failing: the day {@code
   * AgentsConfig} registers a third shared tool the way {@code MemoryTools.Recall} was added,
   * {@code knownTools()} grows and {@code BoundTools} does not, and three tests go on validating
   * the shipped directory against a set that is now missing a name. That is a quiet narrowing
   * rather than a loud one, so it gets an equality.
   *
   * <p>Distinct from {@code the_runtime_binds_the_memory_tools_delegation_and_the_file_tools},
   * which pins {@code knownTools()} against a written-out list: that one says what the boot binds,
   * this one says the test fixture kept up with it.
   */
  @Test
  void the_known_tool_set_the_tests_assemble_is_the_set_the_boot_binds(@TempDir Path dir) {
    write(dir, "echo.md", agent("echo", "fast", "[]", null));

    runner()
        .withPropertyValues()
        .run(
            context ->
                assertEquals(
                    BoundTools.boundByThisServer(),
                    context.getBean(JobRuntime.class).knownTools()));
  }

  /**
   * The reason a registry bean exists at all, from Task 9: {@code promotion_judge.md} declares
   * {@code memory_read}, and {@code AgentRegistry.load} refuses a definition naming a tool this
   * boot does not bind. So the wiring has to register the tool <em>and</em> hand the same set to
   * the loader, and this is the test that both happened.
   */
  @Test
  void an_agent_declaring_a_memory_tool_loads_because_this_boot_binds_it(@TempDir Path dir) {
    write(dir, "reader.md", agent("reader", "fast", "[memory_read, memory_recall]", null));

    runner()
        .withPropertyValues()
        .run(context -> assertNotNull(context.getBean(AgentRegistry.class)));
  }

  @Test
  void shipped_definitions_offer_the_new_reads_only_through_their_declared_grants() {
    runner()
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var runtime = context.getBean(JobRuntime.class);
              var registry = context.getBean(AgentRegistry.class);
              var offered =
                  runtime.schemasOfferedTo(registry.get("interlocutor")).stream()
                      .map(io.aeyer.plowshare.server.llm.dispatch.ToolSchema::name)
                      .toList();
              assertTrue(offered.containsAll(RetrievalTools.NAMES));
              assertTrue(offered.containsAll(ArchiveReadTools.NAMES));
              assertTrue(offered.contains(ConversationSearchTool.NAME));
              assertTrue(offered.contains(ConversationContextTool.NAME));
              assertTrue(
                  runtime.schemasOfferedTo(registry.get("librarian")).stream()
                      .map(io.aeyer.plowshare.server.llm.dispatch.ToolSchema::name)
                      .toList()
                      .containsAll(RetrievalTools.NAMES));
              for (String name :
                  List.of(
                      "coder",
                      "code_reviewer",
                      "interlocutor",
                      "spec_writer",
                      "test_designer",
                      "acceptance_checker",
                      "researcher",
                      "critic",
                      "diagnosis_verifier",
                      "aristoxenus",
                      "farnsworth",
                      "daedalus")) {
                var definition = registry.get(name);
                assertTrue(
                    definition.scopes().stream().anyMatch(grant -> grant.allows(Mode.READ)), name);
                assertTrue(
                    runtime.schemasOfferedTo(definition).stream()
                        .anyMatch(schema -> schema.name().equals(CodeMapTool.NAME)),
                    name + " must be offered code navigation");
                assertTrue(
                    definition.prompt().contains("`code_map`"),
                    name + " needs navigation guidance");
              }
              assertTrue(
                  runtime
                      .schemasOfferedTo(CapabilityReadToolsTest.definition(List.of()))
                      .isEmpty());
            });
  }

  @Test
  void every_mcp_capability_has_a_ws_mapping_and_an_explicit_model_adapter_or_boundary()
      throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var manifest =
        json.readTree(
            Files.readString(Path.of("../test-support/contracts/client-capabilities.json")));
    var fixture =
        json.readTree(
            Files.readString(Path.of("../test-support/contracts/mcp-compatibility.json")));
    java.util.Set<String> mcp = new java.util.TreeSet<>(), mapped = new java.util.TreeSet<>();
    fixture
        .path("toolsList")
        .path("result")
        .path("tools")
        .forEach(tool -> mcp.add(tool.path("name").asText()));
    java.util.Set<String> ws = new java.util.TreeSet<>();
    manifest.path("websocketInventory").forEach(row -> ws.add(row.path("frame").asText()));
    runner()
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var known = context.getBean(JobRuntime.class).knownTools();
              int adapters = 0, boundaries = 0;
              for (var row : manifest.path("legacyMcp")) {
                String name = row.path("id").asText();
                assertTrue(mapped.add(name), name);
                var frame = row.path("transport").path("frame");
                if (!name.equals("client_root_project_here"))
                  assertTrue(ws.contains(frame.asText()), name);
                var model = row.path("internalAgent");
                if (model.path("adapter").asText().equals("registered")) {
                  adapters++;
                  assertTrue(known.contains(name), name);
                  assertTrue(model.path("shippedGrants").size() > 0, name);
                } else {
                  boundaries++;
                  if (name.equals("information")) {
                    assertTrue(known.contains("information_read"));
                    assertTrue(known.contains("information_write"));
                  }
                  assertFalse(known.contains(name), name);
                  assertFalse(model.path("reason").asText().isBlank(), name);
                }
              }
              assertEquals(20, adapters);
              assertEquals(15, boundaries);
            });
    assertEquals(mcp, mapped);
  }

  /**
   * The whole list: the diagnostic read, memory tools, delegation, and the file tools.
   *
   * <p><b>This assertion used to end at the memory tools, and the sentence above it said an agent
   * in this slice "is structurally unable to touch a filesystem".</b> That was true of 3a and of
   * every task in this slice before the wiring, and it is the claim that change deliberately
   * retired — the file tools are what the whole slice was built to serve. The set is still the
   * containment statement, and it is still exact rather than a {@code containsAll}: a boot binding
   * a name nobody wrote down here is a capability an agent may declare, and the list is the only
   * place that decision is visible.
   *
   * <p>{@code file_stat} joined it before any shipped definition declared the name, which is the
   * direction {@code AgentRegistry.load} argues for: a known set that is too narrow refuses a
   * definition at boot and is loud, while one that is too broad is silent — the agent starts and is
   * never offered the tool. Adding the name first leaves only the loud failure available.
   *
   * <p><b>{@code result_read} and {@code result_list} are in it unconditionally, which are the two
   * names here gated by nothing.</b> {@code agent_run} needs a graph and the file tools need a
   * filesystem, and both are wirings a deployment can decline; every run has a {@link Transcript}
   * by signature — {@code JobRuntime.run} requires one — so the tool can always be built and is
   * always offered to a definition that declares it. That is what keeps it out of the superset trap
   * {@code AgentRegistry.load} warns about: the trap is a name waved through that will never be
   * offered, and this one always is. On a run in no conversation it is offered and answers that
   * there is nothing stored to redeem, which is what {@code Transcript.NONE} means — a state that
   * survives for {@code JobRuntime.schemasOfferedTo} and for fixtures now that every run which
   * actually runs an agent has a conversation.
   *
   * <p>Nothing outside this list, in particular: {@code no_boot_binds_a_workspace_management_tool}
   * is the same fact asserted from the side that matters, since the tools a person's session holds
   * must never be reachable from a definition.
   */
  @Test
  void the_runtime_binds_the_memory_tools_delegation_and_the_file_tools(@TempDir Path dir) {
    write(dir, "echo.md", agent("echo", "fast", "[]", null));

    runner()
        .withPropertyValues()
        .run(
            context ->
                assertEquals(
                    List.of(
                        "agent_run",
                        "code_map",
                        "conversation_chat",
                        "conversation_context",
                        "conversation_list",
                        "conversation_search",
                        "conversation_trajectory",
                        "document_ask",
                        "document_citations",
                        "document_list",
                        "document_outline",
                        "document_rank",
                        "document_retrieve",
                        "document_search",
                        "fetch",
                        "file_delete",
                        "file_edit",
                        "file_glob",
                        "file_grep",
                        "file_move",
                        "file_read",
                        "file_roots",
                        "file_stat",
                        "get_date",
                        "information_read",
                        "information_write",
                        "memory_index",
                        "memory_navigate",
                        "memory_read",
                        "memory_recall",
                        "memory_write",
                        "outgoing_cancel",
                        "outgoing_peers",
                        "outgoing_read",
                        "outgoing_send",
                        "result_list",
                        "result_read",
                        "run",
                        "search",
                        "send_message",
                        "todo_read",
                        "todo_write"),
                    List.copyOf(context.getBean(JobRuntime.class).knownTools())));
  }

  /**
   * A definition naming a file tool starts this boot, which is the interlock task 6 waits on.
   *
   * <p>{@code AgentRegistry.load} validates the whole directory against the exact set this boot
   * binds, so the first shipped definition declaring {@code file_read} would have refused the boot
   * until this wiring existed. Declared with the {@code scopes:} such an agent needs, because a
   * file tool with no grant is a tool that refuses everything and would make the load pass for a
   * reason that is not the one under test.
   */
  @Test
  void an_agent_declaring_a_file_tool_loads_because_this_boot_binds_it(@TempDir Path dir) {
    write(
        dir,
        "librarian.md",
        withScopes(agent("librarian", "fast", "[file_read, file_glob]", null), "[workspace:read]"));

    runner()
        .withPropertyValues(dataDir(dir))
        .run(
            context ->
                assertTrue(context.getBean(AgentRegistry.class).names().contains("librarian")));
  }

  /**
   * TestWorkspace management is never a tool an agent can hold, and the boot is where that is
   * enforced.
   *
   * <p>{@code project_define}, {@code project_workspace_set}, {@code project_move} and {@code
   * project_forget} live on the client's MCP surface, where the caller is a person deciding what
   * their own machine exposes. An agent that could move its project's workspace could point it at
   * the directory holding the model API key; one that could redefine the agents directory could
   * grant itself any tool at the next boot.
   *
   * <h2>What is asserted, and why it is the same security claim</h2>
   *
   * <p>This test has now said three things in turn: a startup failure, then a disabled agent, and
   * now a served agent that does not hold the tool. <b>The property that matters has never
   * moved</b>, and it is the last assertion below: the guard is a membership test against {@code
   * knownTools()}, which is derived from the tools this boot actually registered, and these four
   * are not in it for anybody. The tool is never constructed, so it is never offered, so no model
   * can call it — that, and not the fate of the file that named it, is what stops an agent pointing
   * a workspace at the directory holding the model API key.
   *
   * <p>What changed each time is only what the <em>author</em> pays. A boot failure punished every
   * user of the server for one stranger's file; a disablement punished the author for a line their
   * agent could never have used; a dropped item costs exactly the line. The alarm is paid for in
   * the reporting, which is asserted here too: the tool is named, the file is named, and the
   * sentence is on {@code GET /v1/agents} and in the boot log — the same debt the disable rule took
   * on one rung up.
   *
   * <p><b>All four, and not one standing for the family.</b> The guard is name-agnostic, so one
   * case really does prove the mechanism — but what {@code ProjectController} claims is that no
   * <em>particular</em> one of these four is in that set, and a tool quietly added to it would be
   * caught by nothing else. {@code project_move} is the one that would matter most: it is the only
   * one that can carry a project's whole archive somewhere.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {"project_define", "project_workspace_set", "project_move", "project_forget"})
  void no_boot_binds_a_workspace_management_tool(String tool, @TempDir Path dir) {
    write(dir, "sneaky.md", agent("sneaky", "fast", "[memory_read, " + tool + "]", null));

    runner()
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              AgentRegistry registry = context.getBean(AgentRegistry.class);
              assertTrue(
                  registry.names().contains("sneaky"),
                  "one unusable line is not what a bot costs its author");
              String why = registry.withheldTools().get("sneaky: " + tool);
              assertNotNull(why, "and it must not be merely absent: " + registry.withheldTools());
              assertTrue(why.contains(tool), why);
              assertTrue(why.contains("sneaky.md"), why);
              assertFalse(
                  registry.get("sneaky").tools().contains(tool),
                  "the agent must not hold a tool that could move a workspace");
              assertEquals(List.of("memory_read"), registry.get("sneaky").tools());
            });
  }

  /**
   * The same fault on an agent this code depends on is still the boot failure the spec asked for,
   * which is where the loudness went.
   */
  @Test
  void a_required_agent_naming_a_workspace_management_tool_stops_the_boot(@TempDir Path dir) {
    write(dir, "scribe.md", agent("scribe", "fast", "[project_move]", null));

    runner()
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  trail(context.getStartupFailure()).contains("project_move"),
                  trail(context.getStartupFailure()));
            });
  }

  /**
   * The provider a run is handed is the server's own disk, leashed by the {@code projects} table —
   * and, for a project nothing roots, a sentence saying so.
   *
   * <p>The seam is a bean of its own so that this is assertable at all: what {@code JobRuntime}
   * does with it is {@code FileWiringTest}'s subject, and what <em>this</em> boot puts behind it is
   * this one's. A wiring that handed back nothing would leave every file tool answering "this job
   * has no filesystem" with nothing failing anywhere.
   *
   * <p><b>The second name is what presence added.</b> No socket is open in this context, so {@code
   * payments} is rooted nowhere, and the boot's answer for that is the local provider plus an
   * {@code AbsentPresence} — a smaller set with a reason in it, rather than a smaller set and
   * silence.
   */
  @Test
  void a_run_in_a_project_is_given_the_servers_own_disk(@TempDir Path dir) {
    write(dir, "echo.md", agent("echo", "fast", "[]", null));

    runner()
        .withPropertyValues()
        .run(
            context ->
                assertEquals(
                    List.of("local", "presence"),
                    context
                        .getBean(RunProviders.class)
                        .forRun(
                            Home.of("payments"),
                            List.of(new Grant(Scope.WORKSPACE, Mode.READ)),
                            null,
                            null)
                        .stream()
                        .map(FileProvider::name)
                        .toList()));
  }

  /**
   * And the paths no project may reach are the ones this deployment actually has.
   *
   * <p>{@code ProjectStore.mandatoryExclusions} is the rule; this is the only place that says which
   * files <em>this wiring</em> gives it. The rule's own third component, the working directory, is
   * not this class's to pin — it comes from the process rather than from configuration, and {@code
   * ProjectStoreTest} drives it through the overload that takes it. The sampling directory comes
   * from the same property the profiles are read from — one key, so the directory an operator can
   * write to and the directory that is fenced off cannot disagree — and the configuration file from
   * {@link WorkspaceProperties}.
   *
   * <p><b>No definitions fixture here any more.</b> Until task 10 this test also wrote one and
   * named its directory as a fourth mandatory path — that directory carried its own property and
   * its own entry in {@link ProjectStore#mandatoryExclusions}. Both are gone: agent and bot
   * definitions live inside {@code plowshare.data.dir}'s own tree now, which this wiring fences by
   * naming the root, and {@code
   * ProjectStoreTest.no_workspace_may_reach_the_definitions_directories} is where that is measured.
   */
  @Test
  void the_mandatory_exclusions_name_this_deployment_s_own_files(@TempDir Path dir)
      throws Exception {
    // `plowshare.yml`, NOT `application.yml`, on ProjectStoreTest's stated
    // convention and for its reason: a fixture named like the shipped
    // default is one that would still pass if the code ignored the property
    // and hardcoded the default, and this project has already shipped a
    // fixture whose value coincided with the default it was meant to
    // detect. The property is absolute here, so the name is not what makes
    // it discriminate -- which is exactly the near-miss worth not taking.
    Path config = dir.resolve("plowshare.yml");
    Files.writeString(config, "plowshare:\n");
    // THE SAMPLING DIRECTORY, in a subdirectory of its own so neither
    // mandatory path is inside the other: nested ones collapse to the
    // outermost, which is correct and is its own test, but it would leave
    // this one asserting a list the config file had dropped out of. A
    // profile file decides what every agent on this server samples at,
    // effective at the next boot, so write access to it is a delay-fused
    // escalation -- an agent that could edit `models.yaml` could put its
    // own critic back on the configuration this project measured
    // returning empty content.
    Path profiles = dir.resolve("profiles");
    Files.createDirectories(profiles);

    runner()
        .withPropertyValues(
            "plowshare.workspace.config-file=" + config,
            "plowshare.llm.sampling-directory=" + profiles)
        .run(
            context ->
                assertEquals(
                    // The server's own working directory leads, because
                    // Spring Boot loads configuration from `./` and
                    // `./config/` whatever this property says -- see
                    // ProjectStore.mandatoryExclusions. The other two are
                    // this deployment's, and are what this test is for.
                    List.of(real(Path.of("")), config.toRealPath(), profiles.toRealPath()),
                    context
                        .getBean(ProjectStore.class)
                        .effectiveExclusions(
                            new ProjectRecord("payments", dir, List.of(), List.of()))
                        .stream()
                        .map(AgentsConfigTest::real)
                        .toList()));
  }

  /**
   * And the operator token file the fence names is the one {@code plowshare.auth.token-file} points
   * at — never {@code Path.of(System.getProperty("user.home"), ".config", "plowshare",
   * "console-token")}.
   *
   * <p><b>Both halves, because the claim has two ends and each fails differently.</b> The wiring
   * reads a key from a layer this configuration otherwise knows nothing about, on the same one-key
   * argument the sampling directory carries: {@code AuthConfig.announce} writes the file named by
   * that property, so a fence reading anything else fences a path nothing writes and leaves the
   * live credential readable.
   *
   * <p>The second half is the one with teeth, and it is why this test exists rather than a comment.
   * A blank property is a deployment that mints and writes no operator token at all — {@code
   * AuthConfig}'s listener returns before minting — and it is the state of every context in this
   * repository that did not come through {@code PlowshareServerApplication.main}. Substituting the
   * default path there would put the <em>running operator's real credential path</em> into the
   * exclusions of every such context, and into the list an operator is shown by a server that never
   * writes to it. That substitution is the one-line change this assertion is here to kill.
   */
  @Test
  void the_fence_names_the_configured_token_file_and_never_the_default(@TempDir Path dir)
      throws Exception {
    // Outside the working directory, or the collapse would drop it and
    // this test would assert nothing. Not dot-prefixed, for
    // ProjectStoreTest's reason: a hidden fixture is one
    // FileAccess.permits already refuses, so it would pass with this
    // exclusion deleted.
    Path token = Files.writeString(dir.resolve("console-token"), "a fixture, not a token");

    runner()
        .withPropertyValues("plowshare.auth.token-file=" + token)
        // Through `real`, as the test above is and for its reason: the
        // exclusions are absolute and normalised but never real-pathed
        // (ProjectStore.absolute says why), and this host's temp
        // directory is reached through a symlink.
        .run(
            context ->
                assertTrue(
                    context
                        .getBean(ProjectStore.class)
                        .effectiveExclusions(
                            new ProjectRecord("payments", dir, List.of(), List.of()))
                        .stream()
                        .map(AgentsConfigTest::real)
                        .toList()
                        .contains(token.toRealPath()),
                    "the fence follows the property that decides where the token is" + " written"));

    runner()
        .withPropertyValues()
        .run(
            context ->
                assertFalse(
                    context
                        .getBean(ProjectStore.class)
                        .effectiveExclusions(
                            new ProjectRecord("payments", dir, List.of(), List.of()))
                        .contains(
                            Path.of(
                                System.getProperty("user.home"),
                                ".config",
                                "plowshare",
                                "console-token")),
                    "a deployment that writes no token names no path for one, and"
                        + " certainly not this machine's real one"));
  }

  /**
   * And the export directory the fence names is the one {@code
   * plowshare.conversations.retention.export-directory} points at — the same key {@code
   * ArchiveConfig.payloadExport} writes through.
   *
   * <p><b>The token file's argument, on the other kind of secret.</b> That one is a credential;
   * this one is other people's content — the file bodies a tool returned, taken out of {@code
   * entries.content} and written to disk in the open so that an export survives this server being
   * uninstalled. Every property that makes it a good archive makes it a good thing for an agent to
   * find.
   *
   * <p><b>The second half is why this is a test and not a comment.</b> Blank is a real
   * configuration — {@code PayloadExport.NONE}, the operator who wants the liability gone rather
   * than moved — and the two readings of a blank key have to agree: a wiring that substituted the
   * shipped {@code exports} default here would fence off a directory this deployment never writes
   * to, and would say so in the list an operator reads.
   */
  @Test
  void the_fence_names_the_configured_export_directory_and_never_the_default(@TempDir Path dir)
      throws Exception {
    // Outside the working directory, for the token fixture's reason --
    // and ABSOLUTE, which is the whole of the exposure: the shipped
    // `exports` is relative, so it lands inside the working directory and
    // is dropped by the collapse whatever this list says. A relative
    // fixture would pass with the entry deleted.
    Path ejected = Files.createDirectories(dir.resolve("ejected"));

    runner()
        .withPropertyValues("plowshare.conversations.retention.export-directory=" + ejected)
        .run(
            context ->
                assertTrue(
                    context
                        .getBean(ProjectStore.class)
                        .effectiveExclusions(
                            new ProjectRecord("payments", dir, List.of(), List.of()))
                        .stream()
                        .map(AgentsConfigTest::real)
                        .toList()
                        .contains(ejected.toRealPath()),
                    "the fence follows the property that decides where an ejected"
                        + " payload is written"));

    runner()
        .withPropertyValues("plowshare.conversations.retention.export-directory=")
        .run(
            context ->
                assertFalse(
                    context
                        .getBean(ProjectStore.class)
                        .effectiveExclusions(
                            new ProjectRecord("payments", dir, List.of(), List.of()))
                        .contains(Path.of("exports").toAbsolutePath().normalize()),
                    "a deployment that keeps no export names no path for one, and"
                        + " certainly not the default it is not using"));
  }

  /**
   * And the tree this server owns is fenced by the root, from the layout's own resolution of it.
   *
   * <p><b>The layout object and not the key.</b> {@code DataLayout} absolutises its root once, at
   * construction, and an export is written under <em>that</em> path; a fence that re-read {@code
   * plowshare.data.dir} and resolved it a second time would be a second answer to "where is the
   * tree", and the two could differ in every way a relative path can. This asserts they are the
   * same object's answer.
   *
   * <p>The second half is the blank, which is the state of every context in this suite and of every
   * {@code @SpringBootTest} in this repository: no data directory, nothing created, and no path
   * named for one.
   */
  @Test
  void the_fence_names_the_layouts_own_root_and_never_a_second_resolution(@TempDir Path dir)
      throws Exception {
    // Outside the working directory, and named something other than
    // `data` -- a fixture named like the shipped default is one that
    // would pass against a wiring that ignored the property.
    Path owned = dir.resolve("owned");

    runner()
        .withPropertyValues("plowshare.data.dir=" + owned)
        .run(
            context -> {
              assertEquals(
                  owned.toAbsolutePath().normalize(),
                  context.getBean(DataLayout.class).root(),
                  "the layout resolved it once");
              assertTrue(
                  context
                      .getBean(ProjectStore.class)
                      .effectiveExclusions(new ProjectRecord("payments", dir, List.of(), List.of()))
                      .stream()
                      .map(AgentsConfigTest::real)
                      .toList()
                      .contains(owned.toRealPath()),
                  "and the fence names that, not a path of its own");
            });

    runner()
        .withPropertyValues()
        .run(
            context ->
                assertFalse(
                    context
                        .getBean(ProjectStore.class)
                        .effectiveExclusions(
                            new ProjectRecord("payments", dir, List.of(), List.of()))
                        .contains(Path.of("data").toAbsolutePath().normalize()),
                    "a deployment that owns no tree names no path for one, and"
                        + " certainly not the default it is not using"));
  }

  private static Path real(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      throw new UncheckedIOException("could not real-path " + path, e);
    }
  }

  @Test
  void a_curator_and_a_job_store_are_wired(@TempDir Path dir) {
    write(dir, "promotion_judge.md", agent("promotion_judge", "fast", "[memory_read]", null));

    runner()
        .withPropertyValues()
        .run(
            context -> {
              assertNotNull(context.getBean(Curator.class));
              assertNotNull(context.getBean(JobStore.class));
            });
  }

  /**
   * The one silent link in the eviction chain, pinned.
   *
   * <p>{@code FileChannelConfig} gathers close listeners with {@code
   * ObjectProvider<SessionCloseListener>.orderedStream()}, and {@link DefinitionResolver} joins
   * that stream for one reason only: it happens to implement the interface, so Spring's type
   * matching finds it. Nothing declares the relationship and nothing asserts it — which means the
   * day {@code DefinitionResolver} stops being visible as that type, because the bean method's
   * return type narrows, or a proxy wraps it, or the interface moves, <b>the eviction simply stops
   * happening</b>. Every cache entry keyed to a closed session stays in the map for the life of the
   * process, holding a {@code SessionChannel} for a socket that is gone, and not one test in this
   * repository turns red.
   *
   * <p>Asserted through {@code getBeanProvider} rather than by asking for the {@code
   * DefinitionResolver} bean and checking {@code instanceof}, because the two are not the same
   * question. {@code instanceof} would pass on a class that implements the interface but that
   * Spring's own type matching never offers to a collection injection point; this asks the
   * container the identical question {@code FileChannelConfig} asks it.
   */
  @Test
  void the_definition_resolver_is_reachable_as_a_session_close_listener() {
    runner()
        .run(
            context ->
                assertTrue(
                    context
                        .getBeanProvider(SessionCloseListener.class)
                        .orderedStream()
                        .anyMatch(listener -> listener instanceof DefinitionResolver),
                    "no SessionCloseListener in this context is the DefinitionResolver, so a"
                        + " closing session evicts nothing: "
                        + context
                            .getBeanProvider(SessionCloseListener.class)
                            .orderedStream()
                            .map(listener -> listener.getClass().getName())
                            .toList()));
  }

  // --- the shipped floor, and the global tier layered over it ---------------------

  /**
   * The shipped set is the floor: a context with no {@code global/} directory at all still has a
   * registry, and it is the whole of what this repository ships.
   *
   * <p><b>Replaces the old test for a server started with no directory of definitions at all</b>,
   * which asserted the state this change retires. That test and its two neighbours below it -- one
   * for a directory that degrades writes to new rather than refusing, and {@code
   * a_directory_with_no_scribe_definition_files_a_write_flat_and_says_which} -- measured Spring
   * producing a {@code null} registry bean and a registry with no {@code scribe} in it. Neither
   * state is reachable through {@link AgentsConfig} any more: {@link ClasspathDefinitions} is
   * always there, and {@code scribe} is {@link AgentsConfig#REQUIRED}, so a boot that reaches a
   * running context has one. {@link io.aeyer.plowshare.server.agents.scribe.ScribeTest} already
   * owns the degraded behaviour itself -- the {@code Supplier} that answers {@code null} and the
   * verdict that names "no agent registry" or "no agent named 'scribe'" -- driven directly rather
   * than through a boot, so removing the Spring-level duplicates here does not drop coverage.
   * {@code the_boot_says_which_directory_it_looked_in_and_did_not_find} is gone for the same reason
   * one step further: the WARN it measured was logged by the {@code Files.isDirectory} guard this
   * task deletes, and there is no replacement state for it to name.
   */
  @Test
  void a_server_with_no_global_directory_still_has_every_shipped_definition() {
    contextRunner()
        .run(
            context -> {
              AgentRegistry registry = context.getBean(AgentRegistry.class);
              assertTrue(
                  registry.names().containsAll(AgentsConfig.REQUIRED),
                  "boot set was " + registry.names());
              assertTrue(registry.names().contains("interlocutor"));
            });
  }

  /**
   * And a definition in {@code global/agents/} shadows the shipped one of the same name, which is
   * {@link LayeredDefinitions}' whole "first layer to name something wins it" rule measured at the
   * boot that actually wires it.
   *
   * <p>{@code DataLayout(data).initialise()} runs before the fixture is written, and that ordering
   * is required rather than incidental: {@code DataConfig.dataLayout} calls {@code initialise()} on
   * every boot with {@code plowshare.data.dir} set, and it refuses a directory that "already holds
   * files and has no layout.properties" -- which is exactly what {@code global/agents/librarian.md}
   * written before the marker exists would be.
   */
  @Test
  void a_global_definition_shadows_the_shipped_one_of_the_same_name(@TempDir Path data)
      throws Exception {
    new DataLayout(data).initialise();
    Path globalAgents = Files.createDirectories(data.resolve("global/agents"));
    Files.writeString(
        globalAgents.resolve("librarian.md"), shippedLibrarianWithBody("a body nobody shipped"));

    contextRunnerWithDataDir(data)
        .run(
            context -> {
              AgentRegistry registry = context.getBean(AgentRegistry.class);
              assertTrue(registry.get("librarian").prompt().contains("a body nobody shipped"));
            });
  }

  /**
   * And a definition placed only in {@code global/bots/} -- never {@code global/agents/} -- loads
   * too, and is resolvable by name. The two are siblings at the same tier and {@code
   * AgentsConfig.agentRegistry} layers both, not just the one {@code
   * a_global_definition_shadows_the_shipped_one_of_the_same_name} and every other fixture-based
   * test in this file happens to exercise -- without this test, deleting {@code global/bots} from
   * that method's {@code List.of(...)} leaves every other test in the suite passing.
   */
  @Test
  void a_bot_only_definition_loads_from_global_bots(@TempDir Path data) throws Exception {
    new DataLayout(data).initialise();
    Path globalBots = Files.createDirectories(data.resolve("global/bots"));
    Files.writeString(
        globalBots.resolve("vault_keeper.md"), agent("vault_keeper", "fast", "[]", null));

    contextRunnerWithDataDir(data)
        .run(
            context -> {
              AgentRegistry registry = context.getBean(AgentRegistry.class);
              assertTrue(registry.names().contains("vault_keeper"), registry.names().toString());
              assertNotNull(registry.get("vault_keeper"));
            });
  }

  /**
   * And the same name defined in both {@code global/agents/} and {@code global/bots/} is a hard
   * boot-time refusal naming both files -- the whole reason {@code AgentsConfig.requireNoClash}
   * exists rather than leaving {@code LayeredDefinitions} to pick the {@code global/agents}
   * definition silently, on its usual "first layer to name something wins it" rule. Both are
   * operator-written directories at the same tier, so neither has a claim to win over the other.
   */
  @Test
  void the_same_name_in_both_global_agents_and_global_bots_stops_the_boot(@TempDir Path data)
      throws Exception {
    new DataLayout(data).initialise();
    Path globalAgents = Files.createDirectories(data.resolve("global/agents"));
    Path globalBots = Files.createDirectories(data.resolve("global/bots"));
    Path agentSide = globalAgents.resolve("double_booked.md");
    Path botSide = globalBots.resolve("double_booked.md");
    Files.writeString(agentSide, agent("double_booked", "fast", "[]", null));
    Files.writeString(botSide, agent("double_booked", "fast", "[]", null));

    contextRunnerWithDataDir(data)
        .run(
            context -> {
              assertTrue(
                  context.getStartupFailure() != null,
                  "a name defined in both directories has no sensible winner");
              String said = trail(context.getStartupFailure());
              assertTrue(said.contains(agentSide.toAbsolutePath().toString()), said);
              assertTrue(said.contains(botSide.toAbsolutePath().toString()), said);
            });
  }

  /**
   * An agent that asked for something this server cannot give it is told so, by name.
   *
   * <h2>The honest cost of DEFAULT, and it must never be quiet</h2>
   *
   * <p>A model with no profile sends no sampling parameters and runs at its own defaults, which is
   * the safe outcome and not an error — refusing every model nobody has written a profile for would
   * make this server hostile to the next one. What it costs is that a declared {@code sampling:}
   * intent <b>is not honoured</b>: the file says {@code precise} and the request says nothing, and
   * no reader of either would know.
   *
   * <p>That is the same class of invisible fact this whole design exists to end — a temperature
   * that was a Gemma fact sitting in an agent file where nothing could see it was wrong for the
   * model in use. So it is a WARN naming the agent, the intent it asked for and the model that
   * could not answer, and it is asserted here rather than described in a javadoc.
   *
   * <p>The dispatcher is mocked and resolves nothing, which is exactly the state a server pointed
   * at an unrecognised model is in.
   */
  @Test
  void an_intent_no_profile_can_honour_is_warned_about_by_agent_and_by_model(@TempDir Path dir) {
    write(
        dir,
        "seeker.md",
        """
                ---
                name: seeker
                description: d
                model: fast
                tools: []
                max-turns: 1
                max-model-calls: 1
                sampling: precise
                ---
                body
                """);
    LlmDispatcher unmapped = mock(LlmDispatcher.class);
    when(unmapped.wireModelFor(anyString())).thenReturn("a-model-nobody-mapped");
    ListAppender<ILoggingEvent> heard = listening();

    runner(unmapped)
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertEquals(
                  Sampling.NONE,
                  context.getBean(AgentRegistry.class).get("seeker").sampling(),
                  "no profile matched, so the request carries nothing at all and the"
                      + " model's own defaults apply");

              String said =
                  heard.list.stream()
                      .filter(event -> event.getLevel() == Level.WARN)
                      .map(ILoggingEvent::getFormattedMessage)
                      .reduce("", (all, one) -> all + one + "\n");

              assertTrue(said.contains("seeker"), said);
              assertTrue(said.contains("precise"), said);
              assertTrue(said.contains("a-model-nobody-mapped"), said);
              assertTrue(said.contains("NOT HONOURED"), said);
            });
  }

  /**
   * And an agent that asked for nothing in particular is not warned about, because nothing went
   * unhonoured.
   *
   * <p>The other half, and the one that keeps the warning worth reading. A server with no profiles
   * at all is an ordinary server; if every agent on it produced a WARN, the line above would be
   * noise by the second boot and the one agent that really did lose a declaration would be
   * invisible inside it.
   */
  @Test
  void an_agent_that_declared_no_intent_is_not_warned_about(@TempDir Path dir) {
    write(dir, "quiet.md", agent("quiet", "fast", "[]", null));
    LlmDispatcher unmapped = mock(LlmDispatcher.class);
    when(unmapped.wireModelFor(anyString())).thenReturn("a-model-nobody-mapped");
    ListAppender<ILoggingEvent> heard = listening();

    runner(unmapped)
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertNotNull(context.getBean(AgentRegistry.class));
              for (ILoggingEvent event : heard.list) {
                assertFalse(
                    event.getLevel() == Level.WARN && event.getFormattedMessage().contains("quiet"),
                    "an agent that declared no sampling intent lost nothing, so warning"
                        + " about it would bury the agent that did: "
                        + event.getFormattedMessage());
              }
            });
  }

  // --- the job store and the event channel ----------------------------------------

  /**
   * The wiring, driven rather than asserted about.
   *
   * <p>"The bean exists" is not the claim, and it could not be: {@code AgentsConfig.jobStore} takes
   * an {@link org.springframework.beans.factory.ObjectProvider} and falls back to {@code
   * JobEvents.NONE}, so a boot in which the seam went unresolved would start perfectly and publish
   * nowhere for ever. The only way to tell those apart is to run a job and see whether anything
   * arrives.
   *
   * <p>Through {@code submit(String, Function)} rather than an agent, because the two events under
   * test are the store's own — it minted the id and it files the outcome — so a dispatcher, a
   * definition and a turn loop would all be scenery.
   */
  @Test
  void a_wired_event_channel_is_the_one_the_job_store_publishes_through() {
    List<JobEvent> heard = Collections.synchronizedList(new ArrayList<>());
    JobEvents channel = (session, event) -> heard.add(event);

    runner()
        .withBean(JobEvents.class, () -> channel)
        .run(
            context -> {
              JobStore store = context.getBean(JobStore.class);
              String id =
                  store.submit(
                      "a probe of the wiring",
                      cancelled -> new Outcome(Ending.ANSWERED, "done", 1, 1, ""));

              awaitDone(store, id);
              List<String> kinds;
              synchronized (heard) {
                kinds = heard.stream().map(JobEvent::kind).toList();
              }
              assertEquals(
                  List.of(JobEvent.STARTED, JobEvent.ENDED),
                  kinds,
                  "the store this context built publishes through the bean this context"
                      + " wired");
            });
  }

  /**
   * And a context with no event channel is a legal, running server whose jobs are watched by
   * nobody.
   *
   * <p>The same degraded shape this file already holds for the agent registry, and it is the
   * ordinary one here rather than an outage: the stream is droppable by design, so a run with
   * nobody listening is a correct run and not a broken one. <b>Every other test in this file
   * exercises this branch</b> — the runner wires no {@code JobEvents} — which is what makes the
   * fallback tested rather than merely written.
   */
  @Test
  void a_context_with_no_event_channel_still_runs_jobs() {
    runner()
        .run(
            context -> {
              JobStore store = context.getBean(JobStore.class);
              String id =
                  store.submit(
                      "a job nobody is watching",
                      cancelled -> new Outcome(Ending.ANSWERED, "done", 1, 1, ""));

              assertEquals(Ending.ANSWERED, awaitDone(store, id).ending());
            });
  }

  /**
   * Polls rather than sleeps: a job is started on a virtual thread and its outcome is filed there.
   */
  private static Outcome awaitDone(JobStore store, String id) throws InterruptedException {
    for (long waited = 0; waited < 10_000; waited += 10) {
      Job job = store.get(id);
      if (job.state() == Job.State.DONE) {
        return job.outcome().orElseThrow();
      }
      Thread.sleep(10);
    }
    throw new AssertionError("job " + id + " never finished");
  }

  // --- what stops the boot --------------------------------------------------------

  /**
   * A cycle among agents nothing in this code depends on disables them and leaves the server up.
   *
   * <p><b>This test asserted the opposite and was correct at the time.</b> The loader refused a
   * whole directory over one bad set, which is right for four curated in-repo definitions and
   * hostile for user content — a stranger's malformed bot must not be able to stop Plowshare
   * starting. What the cut is now is dependency: {@link AgentsConfig#REQUIRED} names the agents
   * this code looks up by name, and a fault in anything else is a disablement.
   *
   * <p><b>The loudness is what is asserted instead, and it is not weaker.</b> Both members are
   * named on the surface with the reason, so a person meets the cycle by reading it rather than by
   * watching a run go nowhere. A silently missing agent would be worse than the failed boot this
   * replaced.
   */
  @Test
  void a_cycle_among_agents_nothing_depends_on_disables_them_and_starts(@TempDir Path dir) {
    write(dir, "ping.md", agent("ping", "fast", "[agent_run]", "[pong]"));
    write(dir, "pong.md", agent("pong", "fast", "[agent_run]", "[ping]"));

    runner()
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertNull(context.getStartupFailure(), "a stranger's cycle must not stop the boot");
              AgentRegistry registry = context.getBean(AgentRegistry.class);
              // Not List.of(): the shipped seed is always layered underneath,
              // so the registry is never empty. Only ping and pong -- the pair
              // this test wrote a cycle into -- are absent and disabled.
              assertFalse(registry.names().contains("ping"), registry.names().toString());
              assertFalse(registry.names().contains("pong"), registry.names().toString());
              assertEquals(List.of("ping", "pong"), List.copyOf(registry.disabled().keySet()));
              assertTrue(
                  registry.disabled().get("ping").contains("cycle"),
                  registry.disabled().toString());
            });
  }

  /**
   * The same cycle through an agent the code depends on still stops the boot.
   *
   * <p>{@code Scribe} looks {@code scribe} up by name and has nothing to do without it: every write
   * is filed flat, which is discovered at the first write and connects back to no file. That is the
   * failure abort-on-fail is for, and it is kept for exactly the set that has it.
   */
  @Test
  void a_cycle_through_an_agent_the_code_depends_on_stops_the_boot(@TempDir Path dir) {
    write(dir, "scribe.md", agent("scribe", "fast", "[agent_run]", "[pong]"));
    write(dir, "pong.md", agent("pong", "fast", "[agent_run]", "[scribe]"));

    runner()
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertTrue(
                  context.getStartupFailure() != null,
                  "a cycle through scribe must not" + " start");
              String said = trail(context.getStartupFailure());
              assertTrue(said.contains("scribe"), said);
              assertTrue(said.contains("pong"), said);
            });
  }

  /**
   * The abort set is the names this code spells, and nothing else.
   *
   * <p><b>Asserted as literal strings and not as {@code Scribe.AGENT} and its three siblings</b>,
   * which would be the same expression twice and could not fail. What it catches is a rename on one
   * side only, and an agent quietly added to or dropped from the set — the second of which is the
   * silent direction: an agent left out becomes a disablement nothing downstream can work around.
   *
   * <p><b>{@code conversation_folder} joined it when a fold stopped running as the agent being
   * folded.</b> {@code Compaction} looks it up by name, per fold, and catches what a miss throws so
   * that a fold can never fail a turn — so out of this set it is the one whose absence is
   * invisible: a server that answers every question and folds nothing, for ever, at one WARN a
   * turn.
   */
  @Test
  void the_abort_set_is_exactly_the_four_agents_the_code_looks_up_by_name() {
    assertEquals(
        List.of("conversation_folder", "learner", "promotion_judge", "scribe"),
        List.copyOf(new java.util.TreeSet<>(AgentsConfig.REQUIRED)));
    assertEquals(
        AgentsConfig.REQUIRED,
        java.util.Set.of(Scribe.AGENT, Curator.AGENT, Learner.AGENT, Compaction.FOLDER));
  }

  /**
   * {@code LlmDispatcher.requireServed} exists for exactly this, and this is the wiring that calls
   * it.
   *
   * <p>Without it, an agent on a model no pool declares fails at its first run — in production,
   * once, inside a job, where the report is an {@code UNAVAILABLE} outcome that names a specifier
   * and not a file. The registry cannot make this check itself: it reads files and knows nothing
   * about pools.
   */
  @Test
  void an_agent_the_code_depends_on_whose_model_no_pool_serves_stops_the_boot(@TempDir Path dir) {
    write(dir, "scribe.md", agent("scribe", "enormous", "[]", null));
    LlmDispatcher dispatcher = mock(LlmDispatcher.class);
    // doThrow and not when(...).thenThrow: requireServed returns void, so
    // when() has nothing to take.
    doThrow(new UnknownSpecifierException("enormous", "fast, reasoning"))
        .when(dispatcher)
        .requireServed("enormous");

    runner(dispatcher)
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertTrue(context.getStartupFailure() != null, "an unserved model must not start");
              String said = trail(context.getStartupFailure());
              assertTrue(said.contains("scribe"), said);
              assertTrue(said.contains("enormous"), said);
            });
  }

  /**
   * The same fault on an agent nothing depends on is a disablement, and it lands in the same place
   * every other one does.
   *
   * <p><b>This is the check the registry cannot make for itself</b> — it reads files and knows
   * nothing about pools — so it is made here, after the read, and its answer is folded back into
   * the reading rather than thrown. An unserved model was otherwise the one fault that still took
   * the boot down for everybody, which would have made the rule true of the loader and false of the
   * boot.
   */
  @Test
  void an_agent_nothing_depends_on_whose_model_no_pool_serves_is_disabled(@TempDir Path dir) {
    write(dir, "echo.md", agent("echo", "enormous", "[]", null));
    write(dir, "steady.md", agent("steady", "fast", "[]", null));
    LlmDispatcher dispatcher = mock(LlmDispatcher.class);
    doThrow(new UnknownSpecifierException("enormous", "fast, reasoning"))
        .when(dispatcher)
        .requireServed("enormous");

    runner(dispatcher)
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertNull(context.getStartupFailure(), "an unserved model must not stop the boot");
              AgentRegistry registry = context.getBean(AgentRegistry.class);
              // Not List.of("steady"): the shipped seed is always layered
              // underneath, so other names are present too. "echo" is the one
              // that must be absent -- disabled for its unserved model -- and
              // "steady" the one that must have loaded.
              assertTrue(registry.names().contains("steady"), registry.names().toString());
              assertFalse(registry.names().contains("echo"), registry.names().toString());
              assertTrue(
                  registry.disabled().get("echo").contains("enormous"),
                  registry.disabled().toString());
            });
  }

  /**
   * An agent that declares it needs a model that sees boots when the fleet says the model does.
   *
   * <p>The positive half is worth its own test rather than being implied by the refusal below: a
   * check that refused everything would pass the refusal test on its own, and this is the assertion
   * it cannot pass.
   */
  @Test
  void an_agent_naming_a_vision_capable_model_boots(@TempDir Path dir) {
    // A model no shipped agent names, for the reason
    // an_agent_that_declares_no_vision_is_never_asked_about_it's fixture
    // is: image_reader and interlocutor both declare vision on "fast" and
    // "reasoning", and verify(dispatcher).requireSees("fast") wants
    // exactly one call -- which the shipped set alone would already cost.
    write(
        dir,
        "figure_reader.md",
        withVision(agent("figure_reader", "figure-reader-only-model", "[]", null)));
    LlmDispatcher dispatcher = mock(LlmDispatcher.class);

    runner(dispatcher)
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(context.getBean(AgentRegistry.class).names().contains("figure_reader"));
              verify(dispatcher).requireSees("figure-reader-only-model");
            });
  }

  /**
   * One that names a model nothing has declared as seeing is refused, naming the agent and the
   * model.
   *
   * <p>{@code requireModelServed}'s shape exactly, and beside it in the same {@code try} for the
   * same reason: both are "this definition names a model the fleet cannot answer for", and they
   * share one ladder rather than inventing a second answer to "does this disable the agent or stop
   * the server".
   *
   * <p>Without it, an agent needing vision fails at its first image — inside a job, in production,
   * with the whole report being the model saying it saw nothing, which reads as a model limitation
   * rather than a configuration one.
   */
  @Test
  void an_agent_naming_a_model_that_has_not_declared_it_sees_is_refused(@TempDir Path dir) {
    write(dir, "scribe.md", withVision(agent("scribe", "enormous", "[]", null)));
    LlmDispatcher dispatcher = mock(LlmDispatcher.class);
    doThrow(
            new LlmException(
                "'enormous' resolves to a model that has not been declared"
                    + " as one that sees, on [studio serving 'qwen3.5-9b']"))
        .when(dispatcher)
        .requireSees("enormous");

    runner(dispatcher)
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertTrue(
                  context.getStartupFailure() != null,
                  "an agent needing a capability the fleet does not have must not start");
              String said = trail(context.getStartupFailure());
              assertTrue(said.contains("scribe"), said);
              assertTrue(said.contains("enormous"), said);
              assertTrue(said.contains("vision"), said);
            });
  }

  /**
   * The same fault on an agent nothing depends on costs the agent and not the boot, which is the
   * ladder {@code requireModelServed} already established and which this check joins rather than
   * duplicates.
   */
  @Test
  void an_agent_nothing_depends_on_that_cannot_see_is_disabled(@TempDir Path dir) {
    write(dir, "figure_reader.md", withVision(agent("figure_reader", "enormous", "[]", null)));
    write(dir, "steady.md", agent("steady", "fast", "[]", null));
    LlmDispatcher dispatcher = mock(LlmDispatcher.class);
    doThrow(new LlmException("not declared as one that sees"))
        .when(dispatcher)
        .requireSees("enormous");

    runner(dispatcher)
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              AgentRegistry registry = context.getBean(AgentRegistry.class);
              // Not List.of("steady"): the shipped seed is always layered
              // underneath, so other names are present too.
              assertTrue(registry.names().contains("steady"), registry.names().toString());
              assertFalse(registry.names().contains("figure_reader"), registry.names().toString());
              assertTrue(
                  registry.disabled().get("figure_reader").contains("enormous"),
                  registry.disabled().toString());
            });
  }

  /**
   * An agent that says nothing about vision is never asked, and that is not a gap to be tightened
   * later.
   *
   * <p>Every agent in the shipped tree is this one. Putting them all behind a check about a
   * capability none of them uses would make a fleet with no vision model unable to boot at all.
   */
  @Test
  void an_agent_that_declares_no_vision_is_never_asked_about_it(@TempDir Path dir) {
    // A model no other loaded definition names, and not "fast" or
    // "reasoning": the registry now always layers the shipped seed under
    // this fixture, and image_reader and interlocutor both declare
    // vision on those two models, which would make a blanket
    // never(requireSees(anyString())) fail for a reason that is not this
    // one.
    write(dir, "steady.md", agent("steady", "steady-only-model", "[]", null));
    LlmDispatcher dispatcher = mock(LlmDispatcher.class);

    runner(dispatcher)
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(context.getBean(AgentRegistry.class).names().contains("steady"));
              verify(dispatcher, never()).requireSees("steady-only-model");
            });
  }

  /**
   * {@link #agent} has no parameter for {@code vision:}, because every other fixture in this file
   * declares none -- which is the ordinary shape.
   */
  private static String withVision(String definition) {
    int end = definition.indexOf("max-turns:");
    return definition.substring(0, end) + "vision: true\n" + definition.substring(end);
  }

  @Test
  void a_malformed_scribe_definition_stops_the_boot(@TempDir Path dir) {
    write(
        dir,
        "scribe.md",
        """
                ---
                name: scribe
                description: judges the shape of a write
                model: fast
                tool: []
                max-turns: 1
                max-model-calls: 1
                ---
                You decide the shape of a memory.
                """);

    runner()
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertTrue(context.getStartupFailure() != null, "a bad definition must not start");
              String said = trail(context.getStartupFailure());
              assertTrue(said.contains("scribe.md"), said);
              // The key by name, and asserted with its quotes: the same message
              // ends "makes a mistyped 'tools' into an agent that starts", so a
              // bare contains("tool") would pass on a refusal about some other
              // key entirely. `tool:` for `tools:` is the mistype the closed key
              // set exists for.
              assertTrue(said.contains("unrecognised frontmatter key 'tool'"), said);
            });
  }

  /**
   * <b>The example has moved twice, and both moves are the same event: this boot grew the tool the
   * example named.</b> It was {@code file_read} until the file tools were wired, then {@code
   * file_grep} until a search was built. It is now {@code memory_grep}, one of the three archive
   * tools Excalibur declares that this server has no equivalent for — {@code MemoryTools} builds a
   * recall and a read and nothing else, and both shipped agent files carry a comment saying so. So
   * it is still a name an operator porting a definition really does write and really does have to
   * be told about, which is the case this test is for.
   *
   * <p>A name that could never be bound — {@code not_a_tool} — would hold the same assertion and is
   * deliberately not used: what is being checked is that a <em>plausible</em> declaration is
   * refused loudly, and a nonsense one cannot show that.
   */
  @Test
  void an_agent_naming_a_tool_this_boot_does_not_bind_stops_the_boot(@TempDir Path dir) {
    // scribe and not digger: the fault this measures is the unknown-tool
    // refusal, and after the disable rule only an agent the code depends on
    // still turns one into a boot failure. What is being checked is
    // unchanged -- that a plausible ported tool name is refused loudly and
    // names what this boot does bind.
    write(dir, "scribe.md", agent("scribe", "fast", "[memory_grep]", null));

    runner()
        .withPropertyValues(dataDir(dir))
        .run(
            context -> {
              assertTrue(context.getStartupFailure() != null, "an unknown tool must not start");
              String said = trail(context.getStartupFailure());
              assertTrue(said.contains("scribe"), said);
              assertTrue(said.contains("memory_grep"), said);
            });
  }

  /**
   * A {@code global/agents} that is a file rather than a directory is a typo, not an absent
   * deployment, and it gets the loud half.
   *
   * <p><b>Moved from the operator's own directory key, since retired, to {@code global/agents}
   * under {@code plowshare.data.dir}</b>: {@code AgentsConfig.agentRegistry} deleted its own {@code
   * Files.isDirectory} guard, and the refusal this test measures now comes from {@link
   * FilesystemDefinitions#list()} instead, reached through {@link DataLayout#agentsFor}. Same claim
   * -- a path that exists and is not a directory stops the boot rather than reading as an empty
   * tier -- reached through the source that actually owns it now.
   */
  @Test
  void a_global_agents_path_that_is_a_file_stops_the_boot(@TempDir Path data) throws Exception {
    // Initialised first, for a_global_definition_shadows_the_shipped_one_of_the_same_name's
    // reason: DataConfig.dataLayout refuses an unmarked directory that
    // already holds something, so the marker has to exist before "agents"
    // is written or the boot fails one gate earlier than this test means
    // to measure.
    new DataLayout(data).initialise();
    Path global = Files.createDirectories(data.resolve("global"));
    // "agents" as a plain file, not a directory: the configuration most
    // likely to be a typo, on the original test's own reasoning.
    Path agentsFile = Files.writeString(global.resolve("agents"), "not a directory of definitions");

    contextRunnerWithDataDir(data)
        .run(
            context -> {
              assertTrue(context.getStartupFailure() != null, "a file is not a directory");
              // The exact path FilesystemDefinitions names, not a bare
              // contains("agents"): the test method's own name also contains
              // "agents", and under Gradle's per-test temporary-directory
              // scoping that substring can appear in `data`'s own path too --
              // either of which would let a loose contains("agents") pass
              // without the refusal actually naming this file.
              assertTrue(
                  trail(context.getStartupFailure())
                      .contains(agentsFile.toAbsolutePath().toString()),
                  trail(context.getStartupFailure()));
            });
  }

  // --- helpers ---------------------------------------------------------------------

  /**
   * {@code plowshare.data.dir}, provisioned so that {@code global/agents} mirrors {@code
   * agentsDir}'s files.
   *
   * <p>{@code AgentsConfig.agentRegistry} reads no directory property at all -- it reads {@code
   * DataLayout.agentsFor(null)}, layered under the shipped classpath seed, ever since Task 6, and
   * the standalone key it would otherwise have been read from retired in task 10. A fixture written
   * into a bare {@code @TempDir} is invisible to the registry unless it is also reachable as this
   * deployment's global tier, which is what this provisions. Copies rather than moves, so the
   * {@code @TempDir} a test wrote into is untouched and JUnit's own cleanup of it is unaffected.
   *
   * <p><b>The shipped seed is still there underneath, always.</b> {@code LayeredDefinitions} only
   * lets a more specific layer shadow a name; it never removes an entry from a layer beneath it. So
   * a context wired this way sees the fixture's own definitions plus every definition this
   * repository ships -- never the fixture alone -- and a test asserting {@code names()} has to say
   * so rather than assert an isolated set.
   *
   * <p><b>{@code DataLayout.initialise()} is called on the fresh directory first</b>, so the marker
   * it requires is written before any fixture file lands in it -- otherwise {@code
   * DataConfig.dataLayout} refuses to start on a tree that "already holds files and has no
   * layout.properties", which is exactly what an unmarked directory holding a fixture would be.
   *
   * <p><b>A sibling of {@code agentsDir} and not a fresh OS temp directory.</b> {@code
   * Files.createTempDirectory} answered a path JUnit's {@code @TempDir} extension does not own and
   * never cleans up, which leaked one directory under the host's real temp root per test that
   * called this -- on the order of fifteen per run of this file alone. Deriving it from {@code
   * agentsDir} instead keeps it inside the {@code @TempDir} tree the calling test was already
   * given, so it is swept with everything else in that tree when the test ends.
   */
  private static String dataDir(Path agentsDir) {
    try {
      Path data = agentsDir.resolveSibling(agentsDir.getFileName() + "-data");
      new DataLayout(data).initialise();
      Path globalAgents = Files.createDirectories(data.resolve("global").resolve("agents"));
      if (Files.isDirectory(agentsDir)) {
        try (Stream<Path> entries = Files.list(agentsDir)) {
          for (Path file : entries.filter(Files::isRegularFile).toList()) {
            Files.copy(file, globalAgents.resolve(file.getFileName()));
          }
        }
      }
      return "plowshare.data.dir=" + data.toAbsolutePath();
    } catch (IOException e) {
      throw new UncheckedIOException(
          "could not provision a data directory mirroring " + agentsDir, e);
    }
  }

  /**
   * The names this repository ships, read straight off {@code src/main/resources/agents} rather
   * than off a booted registry.
   *
   * <p>{@code AgentsConfig.agentRegistry} always layers the classpath seed in now, and {@code
   * ClasspathDefinitions} scans {@code classpath*:agents/*}, which in this module's own test JVM
   * aggregates {@code src/main/resources/agents} <em>and</em> {@code src/test/resources/agents} --
   * the fixtures used elsewhere in this test suite. A booted registry's {@code names()} is
   * therefore the shipped set plus every test fixture on the classpath, and a test that wants only
   * the former has to name it independently, the way {@code ClasspathDefinitions}' own test does
   * with {@code containsAll} rather than an exact count.
   */
  private static Set<String> shippedNames() {
    try (Stream<Path> files = Files.list(Path.of("src/main/resources/agents"))) {
      return files
          .map(file -> file.getFileName().toString())
          .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".md"))
          .map(name -> name.substring(0, name.length() - ".md".length()))
          .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    } catch (IOException e) {
      throw new UncheckedIOException("could not list src/main/resources/agents", e);
    }
  }

  private static String agent(String name, String model, String tools, String calls) {
    return "---\n"
        + "name: "
        + name
        + "\n"
        + "description: a fixture agent\n"
        + "model: "
        + model
        + "\n"
        + "tools: "
        + tools
        + "\n"
        + (calls == null ? "" : "calls: " + calls + "\n")
        + "max-turns: 2\n"
        + "max-model-calls: 4\n"
        + "---\n"
        + "You do the one thing this fixture is for.\n";
  }

  /**
   * The same fixture with a {@code scopes:} line, which {@link #agent} has no parameter for because
   * most definitions here declare none.
   */
  private static String withScopes(String definition, String scopes) {
    int end = definition.indexOf("max-turns:");
    return definition.substring(0, end) + "scopes: " + scopes + "\n" + definition.substring(end);
  }

  private static void write(Path dir, String file, String content) {
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(file), content);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write the fixture " + file, e);
    }
  }

  private static ListAppender<ILoggingEvent> listening() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(AgentsConfig.class)).addAppender(appender);
    return appender;
  }

  private static String trail(Throwable failure) {
    StringBuilder text = new StringBuilder();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      text.append(current.getMessage()).append('\n');
    }
    return text.toString();
  }

  /** Spec 2026-09-30-local-hooks-are-served decision 5: the run's chain is project, then local. */
  @Test
  @SuppressWarnings("unchecked")
  void a_run_s_hooks_are_personal_then_project_then_local() {
    JobRuntime runtime = mock(JobRuntime.class);
    Hooks personal = mock(Hooks.class);
    Hooks project = mock(Hooks.class);
    Hooks local = mock(Hooks.class);
    when(personal.toolPre(any(), any(), any())).thenReturn(ToolPre.allowed("{}"));
    ObjectProvider<Hooks> personalProvider = mock(ObjectProvider.class);
    when(personalProvider.getIfAvailable(any())).thenReturn(personal);
    when(project.toolPre(any(), any(), any())).thenReturn(ToolPre.allowed("{}"));
    when(local.toolPre(any(), any(), any())).thenReturn(ToolPre.allowed("{}"));
    ObjectProvider<Hooks> projectProvider = mock(ObjectProvider.class);
    ObjectProvider<Hooks> localProvider = mock(ObjectProvider.class);
    ObjectProvider<io.aeyer.plowshare.server.harness.Harness> harness = mock(ObjectProvider.class);
    ObjectProvider<CallValidator> validator = mock(ObjectProvider.class);
    when(projectProvider.getIfAvailable(any())).thenReturn(project);
    when(localProvider.getIfAvailable(any())).thenReturn(local);
    when(harness.getIfAvailable(any())).thenReturn(io.aeyer.plowshare.server.harness.Harness.NONE);
    when(validator.getIfAvailable(any())).thenReturn(CallValidator.NONE);

    Hooks chain =
        new AgentsConfig()
            .runHooks(
                runtime, personalProvider, projectProvider, localProvider, harness, validator);
    chain.toolPre(
        new HookContext("scribe", false, Set.of(), "ledger", "cnv_1", HookContext.SERVER),
        "file_read",
        "{}");

    verify(runtime).useHooks(chain);
    InOrder order = inOrder(personal, project, local);
    order.verify(personal).toolPre(any(), any(), any());
    order.verify(project).toolPre(any(), any(), any());
    order.verify(local).toolPre(any(), any(), any());
  }
}
