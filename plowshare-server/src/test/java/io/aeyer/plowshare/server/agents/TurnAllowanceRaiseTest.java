package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * ADR 0069 at the admission it changes: a conversation that has spent its whole allowance and whose
 * last turn answered accepts the next utterance once that utterance names a new total, and the
 * total is committed to the conversation's own retained allowance before the exhausted-budget
 * refusal reads it.
 *
 * <p>Persistence is an in-memory stand-in for the allowance repository's two operations a turn
 * reaches — a claim that is refused at the ceiling, and a ceiling write refused below usage — so
 * what is asserted is what {@link Turn} hands the repository, not SQL.
 */
class TurnAllowanceRaiseTest {

  private static final String ID = "cnv_RAISE1";

  private final AgentDefinition definition =
      new AgentDefinition(
          "chat",
          "chat",
          "fast",
          List.of(),
          List.of(),
          List.of(),
          4,
          8,
          "Answer clearly",
          true,
          false);
  private final LlmDispatcher dispatcher = mock(LlmDispatcher.class);
  private final ConversationStore conversations = mock(ConversationStore.class);
  private final TurnStore history = mock(TurnStore.class);
  private final Compaction compaction = mock(Compaction.class);
  private final Compaction.TurnTranscript transcript = mock(Compaction.TurnTranscript.class);
  private final Home home = Home.of("ledger");

  /** The retained allowance: every committed ceiling, in order, and the canonical counter. */
  private final List<Integer> ceilings = new ArrayList<>();

  private int retainedCeiling;
  private int retainedSpent;

  /** A conversation at {@code spent} of {@code ceiling}, its last turn answered. */
  private Budget conversationAt(int ceiling, int spent) {
    retainedCeiling = ceiling;
    retainedSpent = spent;
    UUID identity = UUID.randomUUID();
    Budget budget = Budget.restored(identity, ceiling, spent);
    budget.bindAllowance(
        () -> {
          if (retainedSpent >= retainedCeiling) return Optional.empty();
          retainedSpent++;
          return Optional.of(new Budget.Claim(identity, retainedSpent, retainedCeiling));
        },
        total -> {
          if (total < retainedSpent) throw new IllegalStateException("below usage");
          ceilings.add(total);
          retainedCeiling = total;
        });
    rowWith(budget);
    return budget;
  }

  private void rowWith(Budget budget) {
    ConversationRecord row = mock(ConversationRecord.class);
    when(row.id()).thenReturn(ID);
    when(row.home()).thenReturn(home);
    when(row.origin()).thenReturn(Origin.TURN);
    when(row.lifecycle()).thenReturn(ConversationLifecycle.ACTIVE);
    when(row.budget()).thenReturn(budget);
    when(conversations.find(ID)).thenReturn(Optional.of(row));
    when(compaction.transcriptFor(eq(ID), eq(definition), any())).thenReturn(transcript);
    when(transcript.conversationId()).thenReturn(ID);
    when(transcript.before()).thenReturn(List.of());
    when(transcript.speaker()).thenReturn(Speaker.person("alice"));
    when(transcript.usage()).thenReturn(UsageAttribution.LEGACY);
    when(transcript.parentUsage()).thenReturn(UsageAttribution.LEGACY);
  }

  private String speak(Turn turns, Integer maxModelCalls) {
    return turns.speak(
        ID, definition, "Hello again", null, null, maxModelCalls, Speaker.person("alice"), o -> {});
  }

  private static void awaitDone(JobStore jobs, String id) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (jobs.get(id).state() != Job.State.DONE && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(10);
    }
    assertEquals(Job.State.DONE, jobs.get(id).state(), "job did not publish its outcome");
  }

  @Test
  void an_exhausted_answered_conversation_accepts_the_turn_and_commits_the_new_total()
      throws Exception {
    Budget budget = conversationAt(40, 40);
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(new Completion("answer", "stop", TokenUsage.UNKNOWN, List.of()));
    try (JobStore jobs = new JobStore(new JobRuntime(dispatcher, List.of()))) {
      Turn turns = new Turn(jobs, conversations, history, compaction);

      String id = speak(turns, 60);
      awaitDone(jobs, id);

      assertEquals(Outcome.Ending.ANSWERED, jobs.get(id).outcome().orElseThrow().ending());
      assertEquals(List.of(60), ceilings, "the raise is committed to the retained allowance");
      assertEquals(60, budget.limit());
      assertEquals(41, retainedSpent, "the turn spends from the raised allowance");
    }
  }

  @Test
  void a_total_below_what_was_spent_is_refused_and_nothing_runs() {
    conversationAt(40, 40);
    try (JobStore jobs = new JobStore(new JobRuntime(dispatcher, List.of()))) {
      Turn turns = new Turn(jobs, conversations, history, compaction);

      CallerFault refused = assertThrows(CallerFault.class, () -> speak(turns, 30));

      assertTrue(refused.getMessage().contains("already spent 40"), refused.getMessage());
      assertEquals(List.of(), ceilings);
      verifyNoInteractions(dispatcher);
      assertFalse(turns.isSpeaking(ID), "a refused raise frees the conversation");
    }
  }

  @Test
  void a_replayed_total_writes_the_same_ceiling_and_never_raises_twice() throws Exception {
    Budget budget = conversationAt(40, 40);
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(new Completion("answer", "stop", TokenUsage.UNKNOWN, List.of()));
    try (JobStore jobs = new JobStore(new JobRuntime(dispatcher, List.of()))) {
      Turn turns = new Turn(jobs, conversations, history, compaction);

      awaitDone(jobs, speak(turns, 60));
      awaitDone(jobs, speak(turns, 60));

      assertEquals(List.of(60, 60), ceilings, "an absolute total, never an increment");
      assertEquals(60, budget.limit());
      assertEquals(42, retainedSpent);
    }
  }

  @Test
  void an_omitted_total_leaves_an_exhausted_conversation_refused_and_names_the_remedy() {
    conversationAt(40, 40);
    try (JobStore jobs = new JobStore(new JobRuntime(dispatcher, List.of()))) {
      Turn turns = new Turn(jobs, conversations, history, compaction);

      Turn.Refused refused = assertThrows(Turn.Refused.class, () -> speak(turns, null));

      assertTrue(refused.getMessage().contains("'maxModelCalls'"), refused.getMessage());
      assertTrue(refused.getMessage().contains("agent.run"), refused.getMessage());
      assertEquals(List.of(), ceilings);
      verifyNoInteractions(dispatcher);
      assertFalse(turns.isSpeaking(ID));
    }
  }

  @Test
  void a_total_for_an_allowance_with_no_ceiling_is_the_callers_fault() {
    rowWith(Budget.none());
    try (JobStore jobs = new JobStore(new JobRuntime(dispatcher, List.of()))) {
      Turn turns = new Turn(jobs, conversations, history, compaction);

      assertThrows(CallerFault.class, () -> speak(turns, 60));

      verifyNoInteractions(dispatcher);
      assertFalse(turns.isSpeaking(ID));
    }
  }
}
