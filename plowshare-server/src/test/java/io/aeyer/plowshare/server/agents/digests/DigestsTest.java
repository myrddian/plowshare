package io.aeyer.plowshare.server.agents.digests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What navigating and digesting really decide, below the surface that asked.
 *
 * <p>{@code DigestControllerTest} still measures the same statuses through HTTP, deliberately: what
 * that file proves is that nothing a caller sees moved when the decisions did. This file proves the
 * decisions themselves, and three of the four are things <b>no refusal can show, because nothing
 * refuses</b> — two budgets read off the operator's configuration, and the classification of how a
 * digest pass ended. {@code DigestController} held all of them inline, which meant a frame handler
 * reimplementing that method would have agreed about every answer and disagreed about every bill,
 * and would have filed a pass that ran out of allowance as one that simply got stuck.
 *
 * <p>Both properties below are set to numbers {@link MemoryProperties} does not ship, so a
 * reimplementation that hardcoded the shipped default would still fail these.
 */
class DigestsTest {

  private Navigator navigator;
  private Digester digester;
  private JobStore jobs;
  private MemoryProperties properties;
  private Digests digests;

  @BeforeEach
  void setUp() {
    navigator = mock(Navigator.class);
    digester = mock(Digester.class);
    jobs = mock(JobStore.class);
    properties = new MemoryProperties();
    properties.setNavigationBudget(17);
    properties.setDigestBudget(23);
    digests = new Digests(navigator, digester, properties, jobs);
  }

  // --- navigate ----------------------------------------------------------------

  @Test
  void a_navigation_with_no_question_is_refused_before_the_navigator_is_asked() {
    CallerFault refused = assertThrows(CallerFault.class, () -> digests.navigate("a", "  "));

    assertEquals("A question is required", refused.getMessage());
    verify(navigator, never()).navigate(any(), anyString(), any(), any());
  }

  /**
   * The question is read before the tier is, which is the endpoint's own order: a body that names
   * neither is told the thing it has to add.
   */
  @Test
  void the_question_is_read_before_the_tier() {
    CallerFault refused = assertThrows(CallerFault.class, () -> digests.navigate("   ", null));

    assertEquals("A question is required", refused.getMessage());
  }

  @Test
  void a_blank_tier_is_refused_rather_than_read_as_global() {
    assertThrows(CallerFault.class, () -> digests.navigate("   ", "anything"));
    verify(navigator, never()).navigate(any(), anyString(), any(), any());
  }

  /**
   * The moved default: a navigation spends the operator's configured allowance, and nothing in the
   * request or the answer says which.
   */
  @Test
  void a_navigation_takes_the_operators_configured_allowance() {
    when(navigator.navigate(any(), anyString(), any(), any()))
        .thenReturn(new Navigator.Result("root", List.of(), "text", true, 0));

    digests.navigate(null, "what did we decide");

    ArgumentCaptor<Budget> allowance = ArgumentCaptor.captor();
    verify(navigator)
        .navigate(eq(Home.global()), eq("what did we decide"), allowance.capture(), any());
    assertEquals(17, allowance.getValue().limit());
  }

  // --- digest ------------------------------------------------------------------

  @Test
  void a_digest_resolves_the_tier_before_anything_is_submitted() {
    assertThrows(CallerFault.class, () -> digests.start("   "));

    // The ordering DigestControllerTest pins as a status: resolution on the
    // calling thread, so a blank project fails fast instead of becoming a
    // job that fails quietly on a worker.
    verify(jobs, never()).submit(anyString(), any());
  }

  @Test
  void a_digest_is_submitted_under_the_digester_name_and_answers_its_id() {
    when(jobs.submit(eq(Digests.AGENT), any())).thenReturn("job_9");

    Digests.Started started = digests.start("payments");

    assertEquals("job_9", started.id());
    assertEquals(Digests.AGENT, started.agent());
  }

  /** A pass that built something ended by answering. */
  @Test
  void a_pass_that_built_a_digest_answered() {
    when(digester.pass(any(), any(), any())).thenReturn("Built 3 digests");

    Outcome ended = run(submittedPass());

    assertEquals(Outcome.Ending.ANSWERED, ended.ending());
    assertEquals("Built 3 digests", ended.text());
  }

  /**
   * A pass that spent every call it was allowed ended on its allowance, and not as {@code STUCK}.
   *
   * <p>The one classification a second surface could get wrong in silence: both endings carry the
   * digester's own text, so nothing in the answer distinguishes them except the field an operator
   * reads to decide whether to raise the budget or go and look at the archive.
   */
  @Test
  void a_pass_that_spent_its_whole_allowance_ended_on_the_allowance() {
    when(digester.pass(any(), any(), any()))
        .thenAnswer(
            asked -> {
              Budget budget = asked.getArgument(1);
              for (int spent = 0; spent < 23; spent++) {
                budget.trySpend();
              }
              return "nothing more to fold";
            });

    assertEquals(Outcome.Ending.CALL_BUDGET, run(submittedPass()).ending());
  }

  @Test
  void a_pass_that_built_nothing_with_allowance_left_is_stuck() {
    when(digester.pass(any(), any(), any())).thenReturn("nothing more to fold");

    assertEquals(Outcome.Ending.STUCK, run(submittedPass()).ending());
  }

  @Test
  void a_pass_that_was_cancelled_says_so_whatever_it_had_built() {
    when(digester.pass(any(), any(), any())).thenReturn("Built 1 digest");

    Outcome ended = submittedPass().apply(() -> true);

    assertEquals(Outcome.Ending.CANCELLED, ended.ending());
  }

  /**
   * The digest allowance is the operator's too, and it reaches the digester rather than being
   * invented on the way.
   */
  @Test
  void a_pass_takes_the_operators_configured_allowance() {
    when(digester.pass(any(), any(), any())).thenReturn("Built 1 digest");

    run(submittedPass());

    ArgumentCaptor<Budget> allowance = ArgumentCaptor.captor();
    verify(digester).pass(eq(Home.of("payments")), allowance.capture(), any());
    assertEquals(23, allowance.getValue().limit());
  }

  /**
   * The closure {@code start} handed the job store, captured — the budget and the ending it decides
   * are reachable no other way, since {@link JobStore} is mocked and never runs it.
   */
  private Function<BooleanSupplier, Outcome> submittedPass() {
    digests.start("payments");
    ArgumentCaptor<Function<BooleanSupplier, Outcome>> work = ArgumentCaptor.captor();
    verify(jobs).submit(eq(Digests.AGENT), work.capture());
    return work.getValue();
  }

  private static Outcome run(Function<BooleanSupplier, Outcome> work) {
    return work.apply(() -> false);
  }
}
