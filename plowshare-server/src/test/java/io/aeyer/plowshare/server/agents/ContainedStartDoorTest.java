package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The contained door: the one way a run starts inside a confined child Application (ADR 0042).
 *
 * <p>A contained run's conversation carries {@code Origin.CONTAINED}, which {@code
 * Turn.requireSpeakable} refuses by design — that guard is the one a <em>caller-supplied</em>
 * conversation id reaches from {@code POST /v1/agents/&#123;name&#125;/runs} and the {@code
 * agent.run} frame alike, so widening it to accept {@code CONTAINED} would let {@code agent.run}
 * post an utterance into a child's log and spend the child's allowance. The answer is {@code
 * Turn.speakContained}, a second and narrower door, and <strong>the safety property is its
 * reachability</strong>. That is what this class is for.
 *
 * <p>This test lives in {@code agents} and not beside {@code ContainedRunTest} for one concrete
 * reason: {@code speakContained} is package-private and its key type is a package-private member of
 * {@code Runs}, so no other package can name either in order to stub or verify it. That inability
 * is the narrowing working, and it is why {@code ContainedRunTest} now points here for which door
 * the real {@code Runs} chooses.
 *
 * <p>Real {@code Runs}, real {@code Callers}, real {@code Turn}, real {@code JobStore} and real
 * {@code JobRuntime}. Only the dispatcher and the persistence below {@code Turn} are stubbed, so
 * what the tests measure is the decision and not a mock's agreement.
 */
class ContainedStartDoorTest {
  final LlmDispatcher dispatcher = mock(LlmDispatcher.class);
  final ConversationStore conversations = mock(ConversationStore.class);
  final TurnStore history = mock(TurnStore.class);
  final Compaction compaction = mock(Compaction.class);
  final ProjectStore projects = mock(ProjectStore.class);
  final ProjectMembers members = mock(ProjectMembers.class);

  final String childProject = "contained:" + UUID.randomUUID();
  final Home childHome = Home.of(childProject);
  final String handle = "@service/parent";
  final AccountId account = new AccountId(UUID.randomUUID());

  final AgentDefinition definition =
      new AgentDefinition(
          "worker",
          "worker",
          "fast",
          List.of(),
          List.of(),
          List.of(),
          4,
          8,
          "Answer clearly",
          true,
          false);

  JobStore jobs;
  Turn turns;
  Runs runs;
  Budget childPool;

  /** The child's own admission, as a containment hop produces it: the container's account. */
  OperationAuthority.Admission contained() {
    return new OperationAuthority.Admission(
        UUID.randomUUID(),
        handle,
        account,
        new ScopeId(UUID.randomUUID()),
        childHome,
        GrantRecipient.account(account),
        List.of(
            new OperationAuthority.Support(AuthorityAction.READ, UUID.randomUUID()),
            new OperationAuthority.Support(AuthorityAction.WRITE, UUID.randomUUID()),
            new OperationAuthority.Support(AuthorityAction.RUN_MODEL, UUID.randomUUID())));
  }

  /** A conversation row of one origin in one home, with a budget of its own. */
  ConversationRecord row(String id, Origin origin, Home home, Budget budget) {
    var record = mock(ConversationRecord.class);
    when(record.id()).thenReturn(id);
    when(record.home()).thenReturn(home);
    when(record.origin()).thenReturn(origin);
    when(record.lifecycle()).thenReturn(ConversationLifecycle.ACTIVE);
    when(record.budget()).thenReturn(budget);
    when(record.agent()).thenReturn(origin == Origin.TURN ? null : "worker");
    when(conversations.find(id)).thenReturn(Optional.of(record));
    var transcript = mock(Compaction.TurnTranscript.class);
    when(compaction.transcriptFor(eq(id), any(), any())).thenReturn(transcript);
    when(transcript.conversationId()).thenReturn(id);
    when(transcript.before()).thenReturn(List.of());
    when(transcript.speaker()).thenReturn(Speaker.contained(UUID.randomUUID()));
    when(transcript.usage())
        .thenReturn(io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
    when(transcript.parentUsage())
        .thenReturn(io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
    return record;
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    childPool = Budget.of(5);
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(new Completion("done", "stop", TokenUsage.UNKNOWN, List.of()));
    when(projects.id(childProject)).thenReturn(42L);
    when(projects.id("parent")).thenReturn(7L);
    // The container's project and nothing else, which is what production answers: a contained
    // project carries no PROJECT scope and therefore no role for anybody.
    when(members.mayWork("parent", handle)).thenReturn(true);
    when(members.role("parent", handle)).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));

    var childRow = row("cnv_CHILD", Origin.CONTAINED, childHome, childPool);
    when(conversations.openContained(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(childRow);

    jobs = new JobStore(new JobRuntime(dispatcher, List.of()));
    turns = new Turn(jobs, conversations, history, compaction);
    var resolver =
        new DefinitionResolver(
            new AgentRegistry(Map.of("worker", definition)),
            io.aeyer.plowshare.server.data.DataLayout.NONE,
            id -> true,
            Set.of(),
            Set.of(),
            mock(io.aeyer.plowshare.server.files.SessionChannel.class),
            session -> true,
            DefinitionChecks.NONE);
    runs =
        new Runs(
            new Callers(
                resolver,
                projects,
                turns,
                new CallerAccess(new io.aeyer.plowshare.server.session.SessionRegistry(), members)),
            jobs,
            turns);
    var stages =
        (org.springframework.beans.factory.ObjectProvider<LogStages>)
            mock(org.springframework.beans.factory.ObjectProvider.class);
    when(stages.getObject()).thenReturn(mock(LogStages.class));
    runs.useConversations(
        conversations, new io.aeyer.plowshare.server.api.ConversationsProperties(), stages);
  }

  /** The contained start exactly as {@code application.child.run} builds it. */
  Runs.Ask containedAsk() {
    return new Runs.Ask(
        "worker",
        "Summarise the notes",
        childProject,
        null,
        null,
        3,
        null,
        List.of(),
        Speaker.contained(UUID.randomUUID()),
        handle,
        true,
        new Runs.Ask.Contained(contained(), childPool, "parent"));
  }

  void awaitDone(String job) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (jobs.get(job).state() != Job.State.DONE && System.nanoTime() < deadline)
      TimeUnit.MILLISECONDS.sleep(10);
    assertEquals(Job.State.DONE, jobs.get(job).state(), "the job published its outcome");
  }

  /**
   * <strong>A child does work.</strong> This is the first evidence in this capability that a
   * confined Application can <em>run</em> rather than merely exist: a contained start goes through
   * the new door, the turn executes, and the run reaches a terminal answer. Before the door existed
   * this call died at {@code Turn.requireSpeakable} with {@code "conversation cnv_CHILD is the log
   * of a contained run"}, so {@code application.child.run} could open a child's conversation and
   * never spend a turn in it.
   */
  @Test
  void aContainedRunCompletesItsFirstTurn() throws Exception {
    var started = runs.start(containedAsk(), (d, h, u) -> List.of());

    assertEquals("cnv_CHILD", started.conversation(), "the child's own conversation");
    assertEquals("worker", started.agent());
    awaitDone(started.id());
    var outcome = jobs.get(started.id()).outcome().orElseThrow();
    assertEquals(Outcome.Ending.ANSWERED, outcome.ending(), "the child's first turn answered");
    assertEquals("done", outcome.text());
    assertEquals(1, childPool.spent(), "and it spent the child's own allowance, once");
    // The contained door and never the ordinary one, so the conversation this ran in is the one
    // openContained wrote with Origin.CONTAINED.
    verify(conversations).openContained(any(), any(), any(), any(), any(), any(), any());
    verify(conversations, never()).open(any(), any(), any(), any());
    verify(conversations).turnEnded("cnv_CHILD", childPool);
  }

  /**
   * <strong>{@code agent.run} still cannot post into a child's log.</strong> The same {@code
   * Runs.start}, the same {@code Turn}, the same conversation — reached the way {@code agent.run}
   * reaches it, by naming the conversation in the request — is refused, while the contained start
   * over the identical fixture succeeds.
   *
   * <p>The fixture deliberately <em>opens the fence in front of the door</em> so that the door is
   * what refuses. In production a caller naming a child's conversation is stopped earlier, at the
   * work check, because a contained project carries no role for anybody; here {@code
   * members.mayWork} answers true for the child's project, so the request gets all the way to the
   * guard. Without that, this test would pass for a server whose door was wide open.
   */
  @Test
  void agentRunStillCannotPostIntoAChildsLog() throws Exception {
    when(members.mayUse(childProject, handle)).thenReturn(true);
    when(members.mayWork(childProject, handle)).thenReturn(true);
    when(members.role(childProject, handle)).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    var asAgentRun =
        new Runs.Ask(
            "worker",
            "Read the child's notes back to me",
            null,
            null,
            "cnv_CHILD",
            null,
            null,
            List.of(),
            Speaker.person("mallory"),
            handle);

    var refused =
        assertThrows(Turn.Refused.class, () -> runs.start(asAgentRun, (d, h, u) -> List.of()));
    assertTrue(
        refused.getMessage().contains("is the log of a contained run"), refused.getMessage());
    verifyNoInteractions(dispatcher);

    // SAME-CALL POSITIVE CONTROL: the identical path, differing only in whether the start carries
    // the contained authority no wire shape can supply, runs the child's turn.
    var started = runs.start(containedAsk(), (d, h, u) -> List.of());
    awaitDone(started.id());
    assertEquals(Outcome.Ending.ANSWERED, jobs.get(started.id()).outcome().orElseThrow().ending());
  }

  /**
   * No origin becomes speakable because the contained door exists. {@code requireSpeakable} accepts
   * {@link Origin#TURN} alone and {@code requireContainedLog} accepts {@link Origin#CONTAINED}
   * alone; the two sets are disjoint and this asserts over the whole enumeration rather than over a
   * couple of examples.
   */
  @Test
  void noOtherOriginBecomesSpeakable() throws Exception {
    var refused = new ArrayList<Origin>();
    var checked = new ArrayList<Origin>();
    for (Origin origin : Origin.values()) {
      if (origin == Origin.TURN) continue;
      checked.add(origin);
      String id = "cnv_" + origin.name();
      row(id, origin, origin == Origin.CONTAINED ? childHome : Home.of("parent"), Budget.of(5));
      try {
        turns.speak(id, definition, "post this", null);
        fail("speak admitted " + origin);
      } catch (Turn.Refused expected) {
        refused.add(origin);
      }
    }
    // Non-empty first, and the whole enumeration minus TURN: an absence assertion over an empty
    // set proves nothing, and "every other origin" has to mean every one.
    assertEquals(Origin.values().length - 1, checked.size());
    assertTrue(checked.size() >= 8, "the enumeration is the one this server has: " + checked);
    assertEquals(checked, refused, "every origin but TURN is refused by the speakable door");
    assertTrue(checked.contains(Origin.SUBMISSION), checked.toString());
    assertTrue(checked.contains(Origin.EVENT), checked.toString());
    assertTrue(checked.contains(Origin.DELEGATION), checked.toString());
    assertTrue(checked.contains(Origin.MEMORY), checked.toString());

    // CONTROL on the other side, so "everything is refused" is not the whole story: a TURN row is
    // admitted by the same call, and the contained row the loop above refused is admitted by the
    // contained door.
    row("cnv_PERSON", Origin.TURN, Home.of("parent"), Budget.of(5));
    assertDoesNotThrow(() -> turns.speak("cnv_PERSON", definition, "post this", null));
    var started = runs.start(containedAsk(), (d, h, u) -> List.of());
    awaitDone(started.id());
    assertEquals(Outcome.Ending.ANSWERED, jobs.get(started.id()).outcome().orElseThrow().ending());
  }

  /**
   * The reachability claim itself, enumerated rather than asserted in prose.
   *
   * <p>{@code Turn.speakContained} cannot be called without a {@code Runs.ContainedStart}. That
   * type is {@code sealed} with exactly one permitted implementation, and that implementation is
   * {@code private} to {@code Runs} — so no class outside {@code Runs} can implement the interface
   * or construct the implementation, and therefore <em>no code outside {@code Runs} can produce the
   * argument this door requires</em>. The method is package-private besides, so nothing outside
   * this package can name it either.
   *
   * <p><strong>What this does not claim.</strong> "Only {@code Runs}" is what the compiler
   * enforces. The step from there to "only {@code Runs.start}'s contained branch" rests on the
   * single construction site and on {@code Ask.Contained} being unsuppliable from the wire — which
   * {@code ContainedRunTest} pins by reflecting over {@code RunAgentRequest}'s components. That is
   * a chain of two enforced links and one pinned one, not a convention, and it is stated here so a
   * later reader does not read more into it than it says.
   */
  @Test
  void theContainedDoorCannotBeReachedFromOutsideRuns() throws Exception {
    Class<?> key = Class.forName("io.aeyer.plowshare.server.agents.Runs$ContainedStart");
    assertTrue(key.isSealed(), "the key is sealed, so nothing else may implement it");
    var permitted = key.getPermittedSubclasses();
    // Non-empty first: a zero-length permitted array would make every assertion below vacuous.
    assertEquals(1, permitted.length, "exactly one implementation: " + Arrays.toString(permitted));
    assertEquals("io.aeyer.plowshare.server.agents.Runs$ContainedOpened", permitted[0].getName());
    assertTrue(
        Modifier.isPrivate(permitted[0].getModifiers()),
        "the only implementation is private to Runs, so only Runs can construct one");
    assertEquals(Runs.class, permitted[0].getNestHost());
    for (var constructor : permitted[0].getDeclaredConstructors())
      assertTrue(
          Modifier.isPrivate(constructor.getModifiers()),
          "every constructor of the key is private: " + constructor);

    var door =
        Turn.class.getDeclaredMethod(
            "speakContained",
            key,
            AgentDefinition.class,
            String.class,
            String.class,
            TurnCap.class,
            Speaker.class,
            java.util.function.Consumer.class);
    assertFalse(
        Modifier.isPublic(door.getModifiers()),
        "the door is package-private, so nothing outside this package can name it");
    assertFalse(Modifier.isPrivate(door.getModifiers()), "and Runs can still reach it");
    // And the key really is the first parameter, so there is no overload without it: a door that
    // took a bare conversation id beside a flag would be wide with a convention attached.
    assertEquals(key, door.getParameterTypes()[0]);
    assertEquals(
        1,
        Arrays.stream(Turn.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("speakContained"))
            .count(),
        "one contained door and no overload beside it");
  }

  /**
   * The guard is sound on its own, not on {@code openContained}'s behalf: a row claiming {@code
   * Origin.CONTAINED} in an ordinary project is refused rather than admitted on the strength of its
   * origin alone.
   */
  @Test
  void aContainedOriginOutsideAContainedProjectIsRefused() {
    var forged = row("cnv_FORGED", Origin.CONTAINED, Home.of("parent"), Budget.of(5));
    when(conversations.openContained(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(forged);

    var refused =
        assertThrows(Turn.Refused.class, () -> runs.start(containedAsk(), (d, h, u) -> List.of()));
    assertTrue(
        refused.getMessage().contains("not the log of a run inside a contained"),
        refused.getMessage());
    verifyNoInteractions(dispatcher);
  }
}
