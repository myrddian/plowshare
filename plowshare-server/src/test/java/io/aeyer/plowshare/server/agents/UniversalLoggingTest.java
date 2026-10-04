package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Every run gets a conversation, and a child's noise stays out of its caller's prompt.
 *
 * <h2>What this file exists to fail on</h2>
 *
 * <p>Three claims, and every one of them was untrue of this server before {@code
 * V17__conversation_origin.sql}:
 *
 * <ul>
 *   <li><b>a delegated child keeps a log at all.</b> It ran on {@code Transcript.NONE}, so a {@code
 *       code_reviewer} run left no trace of what it did — its caller's log held the {@code
 *       agent_run} call and the result and nothing in between. {@link
 *       #a_delegated_child_keeps_a_log_of_its_own_run} is what fails if that comes back;
 *   <li><b>none of that log reaches the caller's prompt.</b> This is the objection the design has
 *       to survive, and the point of answering it with a separate conversation is that it is
 *       <em>structural</em> — so it is asserted rather than assumed. {@link
 *       #nothing_a_child_said_is_in_its_caller_s_log_or_its_caller_s_prompt} reads both the
 *       caller's log and the messages the transport actually received;
 *   <li><b>the child gains no second budget.</b> The one place this quietly goes wrong: a child
 *       spends its parent's allowance by reference, and a child row carrying a copy of the numbers
 *       would double the accounting with two plausible-looking rows. {@link
 *       #a_child_holds_no_allowance_of_its_own_and_the_tree_s_spending_is_counted_once} is the one
 *       that fails on it.
 * </ul>
 *
 * <h2>Everything but the model is real</h2>
 *
 * <p>Postgres behind Flyway, the real stores, the real {@link JobRuntime} loop, the real {@link
 * Compaction} and the shipped fixture agents in {@code src/test/resources/agents} — {@code boss}
 * delegates to {@code helper} and to {@code middle}, and {@code middle} delegates to {@code helper}
 * again, which is what gives this file a two-level tree to walk. The transport is scripted, for
 * {@code DelegationTest}'s reason: a suite that reached a live endpoint would have red runs meaning
 * "the model had a bad day".
 *
 * <p><b>Folds run on the calling thread</b> — {@code Runnable::run} — which is {@code
 * CompactionTest}'s arrangement and its reason: a fold on a thread this class has no handle on is
 * one the teardown cannot join. Nothing here trips a fold, so what that buys is determinism rather
 * than an assertion.
 */
@Tag("full-db")
@Testcontainers
class UniversalLoggingTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /**
   * Far above anything a model call costs here, and short enough that a genuinely stuck run fails
   * rather than hanging the suite.
   */
  private static final int RUN_SECONDS = 30;

  /**
   * Room for every history this file builds, so nothing folds and the assertions are about logging
   * rather than about compaction.
   */
  private static final int ROOMY = 1_000_000;

  /**
   * A phrase out of {@code boss.md}'s body, which is how a request sent to the caller is told from
   * one sent to a child: the system message is the agent's own and no two fixtures share one.
   */
  private static final String BOSS_PROMPT = "You hand work to the agents you may call";

  private static final Set<String> FIXTURE_TOOLS =
      Set.of("probe_read", "probe_write", AgentRegistry.AGENT_RUN, MemoryTools.WRITE_NAME);

  private static JdbcTemplate jdbc;

  private ConversationStore conversations;
  private TurnStore turns;
  private EntryStore entries;
  private Compaction compaction;
  private final List<JobStore> opened = new ArrayList<>();
  private final List<Compaction> folding = new ArrayList<>();

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
  void freshTables() {
    // entries and compactions reference turns, turns references
    // conversations, and conversations now references itself — so the whole
    // group has to be named in one statement for Postgres to accept it.
    // ConversationStoreTest's fixture says the same thing about the same
    // group.
    jdbc.update(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, entries, compactions, turns, conversations CASCADE");
    conversations = new ConversationStore(jdbc);
    turns = new TurnStore(jdbc);
    entries = new EntryStore(jdbc);
  }

  @AfterEach
  void close() {
    opened.forEach(JobStore::close);
    folding.forEach(Compaction::close);
  }

  // --- a child keeps a log ------------------------------------------------------

  /**
   * The defect this whole change is about: a delegated run left nothing behind.
   *
   * <p>Asserted on the child's <em>own</em> conversation — origin, parent, agent — and on the
   * entries in it, because "a conversation exists" and "the run wrote into it" are different claims
   * and only the second one is the trajectory.
   */
  @Test
  void a_delegated_child_keeps_a_log_of_its_own_run() throws Exception {
    Scripted transport =
        new Scripted()
            .then(
                () ->
                    asking(
                        "handing it over",
                        new ToolCall(
                            "c1", AgentRegistry.AGENT_RUN, delegate("helper", "which programme?"))))
            .thenAlways(() -> answer("done"));
    Fixture fixture = fixtureOver(transport, Probe.returning("probe_read", "a memory"));

    String parent = fixture.speak("boss", "who ran the deploy?");

    ConversationRecord child = onlyChildOf(parent);
    assertEquals(Origin.DELEGATION, child.origin(), "a delegated run is a delegation");
    assertEquals(parent, child.parentId(), "and it names the conversation that delegated");
    assertEquals(
        "helper",
        child.agent(),
        "a child conversation is one agent's for its whole life, and says which");

    List<EntryRecord> log = entries.forConversation(child.id());
    assertFalse(log.isEmpty(), "a delegated child used to write no entries at all");
    assertTrue(
        log.stream()
            .anyMatch(
                entry ->
                    entry.kind() == EntryKind.UTTERANCE
                        && entry.content().contains("which programme?")),
        "the task the caller handed over is the child's opening utterance — " + log);
    assertTrue(
        log.stream().anyMatch(entry -> entry.kind() == EntryKind.ANSWER),
        "and what the child answered is in its own log — " + log);
  }

  /**
   * The one link from a call row to the log it started (spec 2026-09-29 §2.3, §6): a delegated
   * child's row names the id of the {@code agent_run} call that opened it, so a reader on that call
   * can go straight to what it started.
   */
  @Test
  void a_delegated_child_names_the_call_that_opened_it() throws Exception {
    Scripted transport =
        new Scripted()
            .then(
                () ->
                    asking(
                        "handing it over",
                        new ToolCall(
                            "c1", AgentRegistry.AGENT_RUN, delegate("helper", "which programme?"))))
            .thenAlways(() -> answer("done"));
    Fixture fixture = fixtureOver(transport, Probe.returning("probe_read", "a memory"));
    String parent = fixture.speak("boss", "who ran the deploy?");
    ConversationRecord child = onlyChildOf(parent);

    assertEquals(
        "c1",
        jdbc.queryForObject(
            "SELECT opened_by_call FROM conversations WHERE id = ?", String.class, child.id()));
  }

  @Test
  void the_log_keeps_each_call_s_salient_argument_and_each_result_s_outcome() throws Exception {
    Scripted transport =
        new Scripted()
            .then(
                () ->
                    asking(
                        "handing it over",
                        new ToolCall(
                            "c1", AgentRegistry.AGENT_RUN, delegate("helper", "which programme?"))))
            .thenAlways(() -> answer("done"));
    Fixture fixture = fixtureOver(transport, Probe.returning("probe_read", "a memory"));
    String parent = fixture.speak("boss", "who ran the deploy?");

    assertEquals(
        "{\"c1\": \"helper\"}",
        jdbc.queryForObject(
            "SELECT salients::text FROM entries WHERE conversation_id = ? AND kind = 'answer'"
                + " AND tool_calls IS NOT NULL",
            String.class,
            parent));
    // "ok", not "answered": ToolLines.delegation(ANSWERED) is OK, the fixed tool-line word
    // for anything that ran and succeeded (ToolLinesTest asserts the same mapping); the
    // tool-line vocabulary spec 2026-09-28 §2 fixes has no "answered" in it.
    assertEquals(
        "ok",
        jdbc.queryForObject(
            "SELECT outcome FROM entries WHERE conversation_id = ? AND kind = 'tool_result'",
            String.class,
            parent));
  }

  /**
   * A child's {@code turns} row, which is what makes it readable back and what carries the agent.
   *
   * <p>{@code turns.agent} is the column {@code POST /v1/conversations/&#123;id&#125;/resume} reads
   * instead of taking an agent from its caller, so a child that wrote entries and no row would have
   * a trajectory nothing could be continued from.
   */
  @Test
  void a_child_s_run_is_written_down_as_a_turn_naming_the_agent_that_answered() throws Exception {
    Scripted transport =
        new Scripted()
            .then(
                () ->
                    asking(
                        "handing it over",
                        new ToolCall(
                            "c1", AgentRegistry.AGENT_RUN, delegate("helper", "which programme?"))))
            .thenAlways(() -> answer("the nightly one"));
    Fixture fixture = fixtureOver(transport, Probe.returning("probe_read", "a memory"));

    String parent = fixture.speak("boss", "who ran the deploy?");

    List<TurnRecord> spoken = turns.forConversation(onlyChildOf(parent).id());
    assertEquals(1, spoken.size(), "one run, one turn");
    assertEquals(
        "helper",
        spoken.get(0).agent(),
        "which agent answered is recorded now, and nothing recorded it before");
    assertEquals(Outcome.Ending.ANSWERED, spoken.get(0).ending());
    assertEquals(
        "which programme?",
        spoken.get(0).utterance(),
        "a child's turn records the task it was handed");
  }

  // --- and none of it reaches the caller ----------------------------------------

  /**
   * The objection a separate conversation exists to answer, asserted from both sides it can be
   * observed from.
   *
   * <p>The caller's <b>log</b> must not hold the child's working, and the caller's <b>prompt</b>
   * must not either — those are two claims, because a log that held it and a projection that
   * dropped it would pass the second assertion and would put the whole guarantee in one filter.
   * What the caller is entitled to is exactly what {@code AgentRunTool.render} gives it: the
   * child's answer, as one tool result.
   */
  @Test
  void nothing_a_child_said_is_in_its_caller_s_log_or_its_caller_s_prompt() throws Exception {
    String secret = "the child read a private file called quarterly-forecast";
    Scripted transport =
        new Scripted()
            .then(
                () ->
                    asking(
                        "handing it over",
                        new ToolCall(
                            "c1", AgentRegistry.AGENT_RUN, delegate("helper", "look it up"))))
            // The child's own first step: a tool call whose result is the
            // noise the caller must never hold.
            .then(() -> asking("looking", new ToolCall("c2", "probe_read", "{}")))
            .then(() -> answer("it was the nightly one"))
            .thenAlways(() -> answer("the nightly one, apparently"));
    Fixture fixture = fixtureOver(transport, Probe.returning("probe_read", secret));

    String parent = fixture.speak("boss", "who ran the deploy?");
    String child = onlyChildOf(parent).id();

    assertTrue(
        entries.forConversation(child).stream().anyMatch(entry -> entry.content().contains(secret)),
        "the fixture is only meaningful if the child really did read it");
    assertFalse(
        entries.forConversation(parent).stream()
            .anyMatch(entry -> entry.content().contains(secret)),
        "a child's tool result is in the child's log and never in its caller's");
    // The CALLER's requests and not every request: the child's own prompts
    // carry the child's tool result, which is the ordinary shape of a run
    // reading its own working, and an assertion over all of them would fire
    // on that. What has to be true is that the noise never crossed back up.
    String toTheCaller = transport.everythingSentTo(BOSS_PROMPT);
    assertFalse(toTheCaller.contains(secret), "and it never reached a prompt the caller sent");
    assertTrue(
        toTheCaller.contains("it was the nightly one"),
        "what the caller does get is the child's answer, as one tool result");
  }

  /**
   * Two levels, so that a grandchild is a child of its own caller and not of the root.
   *
   * <p>The tree is the point of the parent column — "person asked X → interlocutor → delegated to
   * code_reviewer → which read these files" — and a column that pointed every descendant at the
   * root would flatten exactly the structure it exists to record.
   */
  @Test
  void a_grandchild_is_parented_to_its_own_caller_and_not_to_the_root() throws Exception {
    Scripted transport =
        new Scripted()
            .then(
                () ->
                    asking(
                        "down one",
                        new ToolCall(
                            "c1", AgentRegistry.AGENT_RUN, delegate("middle", "pass it on"))))
            .then(
                () ->
                    asking(
                        "down two",
                        new ToolCall(
                            "c2", AgentRegistry.AGENT_RUN, delegate("helper", "look it up"))))
            .thenAlways(() -> answer("found it"));
    Fixture fixture = fixtureOver(transport, Probe.returning("probe_read", "a memory"));

    String root = fixture.speak("boss", "who ran the deploy?");

    ConversationRecord middle = onlyChildOf(root);
    assertEquals("middle", middle.agent());
    ConversationRecord helper = onlyChildOf(middle.id());
    assertEquals("helper", helper.agent());
    assertEquals(
        middle.id(),
        helper.parentId(),
        "the grandchild names the run that delegated to it, not the one at the top");
  }

  // --- the budget ---------------------------------------------------------------

  /**
   * The one place this quietly goes wrong.
   *
   * <p>A delegated child spends its parent's {@link Budget} <b>by reference</b>. If its row carried
   * a copy of the numbers there would be two rows at the same total, each plausible, and the first
   * thing to sum {@code budget_spent} across conversations would count every model call the tree
   * made twice. The row holding nothing is what makes that unrepresentable rather than merely
   * unwritten.
   *
   * <p>It asserts the arithmetic as well as the nulls: the conversation's own row records what the
   * <em>whole tree</em> spent, which is the sharing working, and the child's row records nothing at
   * all.
   */
  @Test
  void a_child_holds_no_allowance_of_its_own_and_the_tree_s_spending_is_counted_once()
      throws Exception {
    Scripted transport =
        new Scripted()
            .then(
                () ->
                    asking(
                        "handing it over",
                        new ToolCall(
                            "c1", AgentRegistry.AGENT_RUN, delegate("helper", "look it up"))))
            .thenAlways(() -> answer("done"));
    Fixture fixture = fixtureOver(transport, Probe.returning("probe_read", "a memory"));

    String parent = fixture.speak("boss", "who ran the deploy?");

    ConversationRecord child = onlyChildOf(parent);
    assertNull(
        child.budget(), "a delegated child spends its parent's allowance and holds no copy of it");
    assertNull(
        jdbc.queryForObject(
            "SELECT budget_total FROM conversations WHERE id = ?", Integer.class, child.id()),
        "and the row itself holds neither half");

    ConversationRecord root = conversations.find(parent).orElseThrow();
    assertNotNull(root.budget(), "a person's conversation owns the allowance it spends");
    assertEquals(
        transport.calls().size(),
        root.budget().spent(),
        "every model call the tree made is charged once, to the one allowance there is");
  }

  /**
   * The schema refuses the row Java refuses to build, which is this project's usual doubling:
   * {@code ConversationStore.log} is one writer, and a psql session, a later migration or a second
   * writer does not go through it.
   */
  @Test
  void the_table_refuses_a_shared_allowance_written_onto_a_child_s_row() {
    ConversationRecord parent = conversations.open(Home.global(), Budget.of(20));

    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () ->
                jdbc.update(
                    // `lifecycle` NULL and not left to default: V19 keeps the
                    // lifecycle on the root of a tree, so a child that carried one
                    // would break that rule as well as the one under test here.
                    "INSERT INTO conversations"
                        + " (id, origin, lifecycle, parent_id, agent, created_at, budget_total,"
                        + " budget_spent)"
                        + " VALUES ('cnv_second_purse', 'delegation', NULL, ?, 'helper', now(),"
                        + " 20, 0)",
                    parent.id()));
    assertTrue(
        refused.getMessage().contains("conversations_an_allowance_is_owned_or_shared"),
        "the second copy of a shared budget is what the row was refused for — "
            + refused.getMessage());
  }

  /**
   * A turn writes down the system block it went out with, beside the row that says what it said.
   *
   * <p><b>The write half of V32, driven through a real run rather than through the store.</b>
   * {@code TurnStoreTest} proves the table behaves; what this proves is that the one caller in
   * {@code main} actually reaches it — {@code Compaction.TurnTranscript.closed}, which is where a
   * turn's row is written and where the block now goes with it. Before V32 nothing anywhere held
   * the prompt text a turn carried, so every projection of every past turn opened with whatever the
   * file said at the moment somebody read it.
   *
   * <p><b>The agent's own prompt and not the assembled system message.</b> A fold's seam is merged
   * into the same message on the wire, and the <em>summary</em> behind it is an entry the archive
   * already holds once; storing the merged text would put a second copy of it beside the first for
   * {@code Compaction.projectionAsOf} to disagree with, and would freeze a string {@code
   * JobRuntime.oneSystemMessageFirst} derives, where re-merging on the read keeps one
   * implementation of that rule. So what comes back is exactly {@code AgentDefinition.prompt} — and
   * this test asserts that equality, which is the only thing that makes the stored column a
   * <em>recording</em> of the file rather than of an assembly.
   *
   * <p><b>It is not the claim that a seam comes back unchanged.</b> The seam's sentence is built at
   * read time from today's definition, so the wording can differ from the one the turn was sent;
   * {@code Compaction.projectionAsOf} names that gap and nothing here closes it.
   */
  @Test
  void a_turn_records_the_system_block_it_went_out_with() throws Exception {
    Scripted transport = new Scripted().thenAlways(() -> answer("a push that runs"));
    Fixture fixture = fixtureOver(transport);
    AgentDefinition echo = fixture.registry.get("echo");

    String conversation = fixture.speak("echo", "what is a deploy?");

    TurnRecord only = turns.forConversation(conversation).get(0);
    assertNotNull(
        only.systemBlock(),
        "a turn that ran under an agent with a prompt sent a block, and the row is the"
            + " only place that can still say which one");
    assertEquals(
        Optional.of(echo.prompt()),
        turns.blockSentAt(conversation, 1),
        "and what it points at is the agent's prompt as it stood when the turn ran");
  }

  /**
   * A block that cannot be stored costs the turn nothing.
   *
   * <p><b>The rule this had to keep: the turn is the work and the block is the audit trail beside
   * it.</b> {@code Compaction.closed} already swallows a failed {@code turns.record} for the same
   * reason one line down, and a conversation must not lose the record of what it said over the
   * record of what it was shown.
   *
   * <p>Driven by taking the table away underneath the run, which is the only failure available that
   * is not a mock: {@code system_blocks} is renamed for the duration, so the insert fails the way a
   * real outage would while the {@code turns} row still has somewhere to land. The foreign key
   * survives the rename with the table, so a null reference is still writable.
   */
  @Test
  void a_block_that_could_not_be_stored_does_not_fail_the_turn() throws Exception {
    Scripted transport = new Scripted().thenAlways(() -> answer("a push that runs"));
    Fixture fixture = fixtureOver(transport);
    jdbc.execute("ALTER TABLE system_blocks RENAME TO system_blocks_elsewhere");
    try {
      String conversation = fixture.speak("echo", "what is a deploy?");

      TurnRecord only = turns.forConversation(conversation).get(0);
      assertEquals(
          "what is a deploy?",
          only.utterance(),
          "the turn still has its row: losing the transcript over the audit trail is"
              + " the wrong way round");
      assertNull(
          only.systemBlock(),
          "and it reads back as not recorded, which is true of it -- not as some"
              + " other turn's block and not as the file read afterwards");
    } finally {
      jdbc.execute("ALTER TABLE system_blocks_elsewhere RENAME TO system_blocks");
    }
  }

  // --- a curator ruling and a submission -----------------------------------------

  /**
   * A run started on its own behalf gets a conversation too, and it is the one origin besides a
   * turn that <em>owns</em> the allowance on its row: there is no parent to inherit from and no
   * pass sharing one.
   */
  @Test
  void a_submission_keeps_a_log_and_owns_the_allowance_it_spends() throws Exception {
    Scripted transport = new Scripted().thenAlways(() -> answer("answered out of my head"));
    Fixture fixture = fixtureOver(transport);

    fixture.await(
        fixture.jobs.submit(
            fixture.registry.get("echo"), "what is a deploy?", Home.global(), null));

    ConversationRecord logged = onlyConversationOfOrigin(Origin.SUBMISSION);
    assertEquals("echo", logged.agent());
    assertNull(logged.parentId(), "a submission is a root");
    assertNotNull(logged.budget(), "and it owns what it spends");
    assertEquals(
        8, logged.budget().limit(), "which is the agent's own max-model-calls, as it always was");
    assertTrue(
        entries.forConversation(logged.id()).stream()
            .anyMatch(entry -> entry.kind() == EntryKind.UTTERANCE),
        "and the run wrote into it");
    assertEquals("echo", turns.forConversation(logged.id()).get(0).agent());
  }

  /**
   * Spec 2026-09-30-local-hooks-are-served decision 4: a submission naming a session says so at
   * open.
   */
  @Test
  void a_submission_names_its_session_to_the_log_it_opens() throws Exception {
    Scripted transport = new Scripted().thenAlways(() -> answer("answered out of my head"));
    Fixture fixture = fixtureOver(transport);
    RecordingLogStages told = new RecordingLogStages();
    compaction.useLogStages(told);

    fixture.await(
        fixture.jobs.submit(
            fixture.registry.get("echo"), "what is a deploy?", Home.global(), "tui-1"));

    assertEquals("tui-1", told.opened.get(0).session());
    assertNull(told.opened.get(0).inherits());
  }

  /**
   * <b>And what it spent is on that row when it is over.</b>
   *
   * <p>{@code implementation rationale} §11: a submission owned an allowance and nothing ever wrote
   * the spending back, so every submission this server ran left a row saying nought of its total
   * for ever. That was a small defect while a submission was one run of one agent; a document
   * ingest is a submission whose row is the only durable record of half an hour of model calls, and
   * "0 of 300" is not a record of it.
   *
   * <p><b>Asserted on the row and not on the object.</b> The {@link Budget} has always known — it
   * is the object every turn and every delegated child spends through — and the whole of the defect
   * was that nobody asked it once the run was over.
   */
  @Test
  void a_submission_writes_back_what_it_spent() throws Exception {
    Scripted transport = new Scripted().thenAlways(() -> answer("answered out of my head"));
    Fixture fixture = fixtureOver(transport);

    fixture.await(
        fixture.jobs.submit(
            fixture.registry.get("echo"), "what is a deploy?", Home.global(), null));

    ConversationRecord logged = onlyConversationOfOrigin(Origin.SUBMISSION);
    assertEquals(
        1,
        logged.budget().spent(),
        "the run made one model call and the row is what has to say so — TODO §11");
    assertEquals(8, logged.budget().limit(), "and the total it was granted is unchanged");
  }

  /**
   * A curator's ruling keeps a log and owns nothing, which is the pair that decided how the
   * allowance constraint is written.
   *
   * <p><b>Parentless <em>and</em> sharing.</b> A pass is a job and not a conversation — {@code
   * JobStore.submit(String, Function)} takes the work itself — so a ruling has nothing to point at,
   * and every ruling in a pass still spends one {@code Budget} the pass minted. That is why {@code
   * Origin.ownsItsAllowance} keys off the origin and not off the parent column: "is a root" and
   * "owns its allowance" are two different questions, and a constraint written on the first would
   * have given every ruling in every pass a second copy of the pass's numbers.
   *
   * <p>Driven through {@link Compaction#logFor} and {@link JobRuntime#run} in the same order {@code
   * Curator.pass} calls them, rather than through a pass — a pass needs an archive, a proposal
   * store and a promotion queue, none of which say anything about this.
   */
  @Test
  void a_curator_s_ruling_keeps_a_log_and_shares_the_pass_s_allowance() throws Exception {
    Scripted transport = new Scripted().thenAlways(() -> answer("promote it"));
    Fixture fixture = fixtureOver(transport);
    // One object across the whole pass, exactly as Curator.pass mints it.
    Budget pass = Budget.of(12);

    Transcript log =
        compaction.logFor(Origin.CURATOR, Home.global(), fixture.registry.get("echo"), null, null);
    Outcome ruling =
        fixture.runtime.run(
            fixture.registry.get("echo"),
            "is mem_000042 worth promoting?",
            Home.global(),
            pass,
            () -> false,
            null,
            JobWatch.UNWATCHED,
            log);
    log.closed("is mem_000042 worth promoting?", ruling);

    ConversationRecord logged = onlyConversationOfOrigin(Origin.CURATOR);
    assertEquals("echo", logged.agent());
    assertNull(logged.parentId(), "a pass is a job and not a conversation to be a child of");
    assertNull(
        logged.budget(), "and a ruling spends the pass's allowance rather than a copy of it");
    assertEquals(1, pass.spent(), "the one object the pass holds is what was charged");
    assertTrue(
        entries.forConversation(logged.id()).stream()
            .anyMatch(entry -> entry.kind() == EntryKind.UTTERANCE),
        "a ruling used to leave nothing behind at all");
    assertEquals("echo", turns.forConversation(logged.id()).get(0).agent());
  }

  // --- what a person's listing shows -----------------------------------------------

  /**
   * The filter {@code GET /v1/conversations} needs, driven through the store that answers it.
   *
   * <p><b>Parentless is not the right test and this is the fixture that proves it</b>: the
   * submission below has no parent either, and it is not a person's conversation. That is the whole
   * reason {@code origin} is a column and not something inferred from the parent reference.
   */
  @Test
  void a_person_s_listing_holds_their_conversations_and_none_of_the_machine_s() {
    Home home = Home.of("payments");
    ConversationRecord mine = conversations.open(home, Budget.of(20));
    ConversationRecord submitted =
        conversations.log(Origin.SUBMISSION, home, "echo", null, Budget.of(8));
    conversations.log(Origin.DELEGATION, home, "helper", mine.id(), null);
    conversations.log(Origin.CURATOR, home, "promotion_judge", null, null);

    List<String> listed = conversations.inHome(home).stream().map(ConversationRecord::id).toList();

    assertEquals(
        List.of(mine.id()),
        listed,
        "a submission is parentless and is still not a person's conversation, so the"
            + " filter is on origin — "
            + submitted.id()
            + " must not be listed");
  }

  // --- speaking into a machine's log ------------------------------------------------

  /**
   * An id read out of a trajectory is an id somebody can put in {@code
   * RunAgentRequest.conversation}.
   *
   * <p>Before this guard that utterance reached {@code record.budget()} and found a null — because
   * a delegated child holds no allowance by design — and surfaced as a null dereference on a
   * virtual thread rather than as an answer.
   */
  @Test
  void a_person_cannot_speak_into_a_delegated_child_s_log() throws Exception {
    Scripted transport = new Scripted().thenAlways(() -> answer("done"));
    Fixture fixture = fixtureOver(transport);
    ConversationRecord mine = conversations.open(Home.global(), Budget.of(20));
    ConversationRecord child =
        conversations.log(Origin.DELEGATION, Home.global(), "helper", mine.id(), null);

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () ->
                fixture.turn.speak(
                    child.id(), fixture.registry.get("helper"), "carry on then", null));

    assertTrue(refused.getMessage().contains("delegation"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("POST /v1/conversations"),
        "and it says where a conversation to speak into comes from — " + refused.getMessage());
  }

  /**
   * Nor continued, and the reason is the accounting rather than the log.
   *
   * <p>A child has everything a resumption reads now — entries, a {@code turns} row, an ending, the
   * agent that answered — and no allowance: it spent the {@link Budget} object of the run that
   * delegated to it, and that run is over. Granting one would write an allowance onto a row the
   * schema refuses to let hold one, which is the constraint that stops a shared budget being
   * counted twice.
   */
  @Test
  void a_delegated_child_s_log_cannot_be_continued_either() throws Exception {
    Scripted transport = new Scripted().thenAlways(() -> answer("done"));
    Fixture fixture = fixtureOver(transport);
    ConversationRecord mine = conversations.open(Home.global(), Budget.of(20));
    ConversationRecord child =
        conversations.log(Origin.DELEGATION, Home.global(), "helper", mine.id(), null);
    turns.record(
        child.id(), "look it up", "ran out", Outcome.Ending.CALL_BUDGET, 900, "helper", null);

    Turn.Refused refused =
        assertThrows(
            Turn.Refused.class,
            () -> fixture.turn.resume(child.id(), fixture.registry.get("helper"), null, null, 40));

    assertTrue(refused.getMessage().contains("cannot account for"), refused.getMessage());
  }

  // --- scaffolding -------------------------------------------------------------------

  private ConversationRecord onlyChildOf(String parent) {
    List<String> children =
        jdbc.queryForList(
            "SELECT id FROM conversations WHERE parent_id = ? ORDER BY created_at, id",
            String.class,
            parent);
    assertEquals(1, children.size(), "expected exactly one child of " + parent);
    return conversations.find(children.get(0)).orElseThrow();
  }

  private ConversationRecord onlyConversationOfOrigin(Origin origin) {
    List<String> found =
        jdbc.queryForList(
            "SELECT id FROM conversations WHERE origin = ?", String.class, origin.wireName());
    assertEquals(1, found.size(), "expected exactly one " + origin.wireName());
    return conversations.find(found.get(0)).orElseThrow();
  }

  private Fixture fixtureOver(Scripted transport, AgentTool... tools) throws Exception {
    AgentRegistry registry =
        AgentRegistry.of(
            Path.of(UniversalLoggingTest.class.getResource("/agents").toURI()), FIXTURE_TOOLS);
    LlmDispatcher models =
        new LlmDispatcher(
            List.of(
                new LlmPool(
                    "scripted",
                    List.of("model-fast"),
                    Map.of("fast", "model-fast"),
                    4,
                    1,
                    Duration.ofSeconds(5),
                    transport)),
            new NoOpTokenLedger());
    compaction =
        new Compaction(
            models,
            UniversalLoggingTest::folder,
            turns,
            new CompactionStore(jdbc),
            entries,
            ROOMY,
            Runnable::run,
            conversations);
    folding.add(compaction);
    JobRuntime runtime = new JobRuntime(models, List.of(tools), () -> registry);
    JobStore jobs = new JobStore(runtime, JobEvents.NONE, compaction);
    opened.add(jobs);
    return new Fixture(registry, runtime, jobs, new Turn(jobs, conversations, turns, compaction));
  }

  private final class Fixture {

    private final AgentRegistry registry;
    private final JobRuntime runtime;
    private final JobStore jobs;
    private final Turn turn;

    private Fixture(AgentRegistry registry, JobRuntime runtime, JobStore jobs, Turn turn) {
      this.registry = registry;
      this.runtime = runtime;
      this.jobs = jobs;
      this.turn = turn;
    }

    /**
     * Opens a conversation, says one thing into it, waits, and answers with the conversation's id —
     * which is what every assertion here starts from.
     */
    String speak(String agent, String utterance) {
      ConversationRecord opened = conversations.open(Home.global(), Budget.of(40));
      await(turn.speak(opened.id(), registry.get(agent), utterance, null));
      return opened.id();
    }

    /**
     * Waits for a job to finish.
     *
     * <p>A poll and not a sleep: the loop asks the state it is waiting for rather than guessing a
     * duration, which is what {@code CompactionTest.speakAndWait} does and for the same reason. The
     * deadline exists so a genuinely stuck run fails this test instead of hanging the suite.
     */
    Outcome await(String job) {
      for (int attempt = 0; attempt < RUN_SECONDS * 40; attempt++) {
        if (jobs.get(job).state() == Job.State.DONE) {
          return jobs.get(job)
              .outcome()
              .orElseThrow(() -> new AssertionError("a DONE job with no outcome"));
        }
        try {
          TimeUnit.MILLISECONDS.sleep(25);
        } catch (InterruptedException stop) {
          Thread.currentThread().interrupt();
          throw new AssertionError("interrupted waiting for " + job, stop);
        }
      }
      throw new AssertionError("job " + job + " never finished");
    }
  }

  /** {@code agent_run} arguments, as the model would send them. */
  private static String delegate(String agent, String task) {
    return "{\"agent\": \"" + agent + "\", \"task\": \"" + task + "\"}";
  }

  private static Completion answer(String content) {
    return new Completion(content, "stop", TokenUsage.UNKNOWN, List.of());
  }

  private static Completion asking(String content, ToolCall... wanted) {
    return new Completion(content, "tool_calls", TokenUsage.UNKNOWN, List.of(wanted));
  }

  /**
   * A transport that answers what the test queued, in order, and remembers every message it was
   * sent.
   *
   * <p>{@code DelegationTest.Scripted} without the latches, which no test here needs: a parent and
   * its children run on one thread, one after another, so the index order is the order the tree
   * makes its calls.
   */
  private static final class Scripted implements LlmTransport {

    private final List<java.util.function.Supplier<Completion>> steps = new ArrayList<>();
    private final List<List<ChatMessage>> calls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger index = new AtomicInteger();
    private volatile java.util.function.Supplier<Completion> fallback =
        () -> answer("nothing left to say");

    Scripted then(java.util.function.Supplier<Completion> step) {
      steps.add(step);
      return this;
    }

    Scripted thenAlways(java.util.function.Supplier<Completion> step) {
      fallback = step;
      return this;
    }

    List<List<ChatMessage>> calls() {
      synchronized (calls) {
        return List.copyOf(calls);
      }
    }

    /**
     * Every message of every request whose system prompt names one agent, joined — which is the
     * whole of what that agent was ever shown.
     *
     * <p>A tree makes calls for several agents through one transport, so "what the model was sent"
     * is not one thing. The system message is what separates them: it is assembled per run from the
     * agent's own definition, and no two fixtures in {@code src/test/resources/agents} share a
     * body.
     */
    String everythingSentTo(String systemPrompt) {
      StringBuilder out = new StringBuilder();
      for (List<ChatMessage> call : calls()) {
        boolean theirs =
            call.stream()
                .anyMatch(
                    message ->
                        message.role() == ChatMessage.Role.SYSTEM
                            && message.content().contains(systemPrompt));
        if (!theirs) {
          continue;
        }
        for (ChatMessage message : call) {
          out.append(message.content()).append('\n');
        }
      }
      return out.toString();
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      int at = index.getAndIncrement();
      calls.add(messages);
      return at < steps.size() ? steps.get(at).get() : fallback.get();
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        io.aeyer.plowshare.server.llm.dispatch.Deltas sink,
        java.util.function.BooleanSupplier abandoned) {
      // Delegating to complete(...) for DelegationTest's reason:
      // reconciling two wire formats is the transport's problem and is
      // proved elsewhere, so a fake that answered differently down this
      // path would only be testing itself.
      Completion streamed = complete(wireModel, messages, sampling, tools);
      String content = streamed.content();
      if (content != null && !content.isEmpty()) {
        sink.answered(content);
      }
      return streamed;
    }

    @Override
    public io.aeyer.plowshare.server.llm.dispatch.Embeddings embed(
        String wireModel, List<String> input) {
      throw new UnsupportedOperationException("the job runtime does not embed");
    }

    @Override
    public void close() {}
  }

  /**
   * A tool that answers with what the test chose, so a child's working is a string this file can
   * look for in a log it must not be in.
   */
  private static final class Probe implements AgentTool {

    private final ToolSchema schema;
    private final Function<String, String> behaviour;

    private Probe(String name, Function<String, String> behaviour) {
      this.schema =
          new ToolSchema(
              name, "a probe called " + name, Map.of("type", "object", "properties", Map.of()));
      this.behaviour = behaviour;
    }

    static Probe returning(String name, String result) {
      return new Probe(name, args -> result);
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      return behaviour.apply(argumentsJson);
    }
  }

  /**
   * The agent a fold runs as, which nothing in this class asks for.
   *
   * <p>Named rather than left out all the same. A fold that could not say what it runs as would run
   * as the agent being folded, which is the defect {@code Compaction.FOLDER} exists to end --
   * {@code implementation rationale} §6.1 -- and a fixture that quietly had no folder would be the
   * one place that could not tell the two apart.
   */
  private static AgentDefinition folder() {
    return new AgentDefinition(
        Compaction.FOLDER,
        "a fixture folder",
        "fast",
        List.of(),
        List.of(),
        List.of(),
        1,
        1,
        "You summarise a span of a recorded conversation.");
  }
}
