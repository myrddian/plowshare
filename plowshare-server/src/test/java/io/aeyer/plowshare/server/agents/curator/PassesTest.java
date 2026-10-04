package io.aeyer.plowshare.server.agents.curator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentsProperties;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * One curator pass's whole decision, below the surface that asked for it.
 *
 * <p>{@code AgentControllerTest} still measures the same refusals through HTTP, and deliberately:
 * what that file proves is that the status and the body a caller sees did not move when the
 * decision did. This file proves the decision itself — and above all <b>the silent default</b>,
 * which is the half no refusal can show because nothing refuses.
 *
 * <p><b>{@link #a_pass_with_no_budget_takes_the_configured_curator_budget} is the point of this
 * class.</b> An omitted {@code maxModelCalls} takes the operator's configured number, and the only
 * way a second surface can get that wrong is by reaching a different source for it — which produces
 * a differently-budgeted pass and no error at all. The property here is set to a number that is not
 * {@link AgentsProperties}'s own default, so a reimplementation that hardcoded the default would
 * still fail this.
 */
class PassesTest {

  private JobStore jobs;
  private Curator curator;
  private AgentsProperties props;
  private Passes passes;

  @BeforeEach
  void setUp() {
    jobs = mock(JobStore.class);
    curator = mock(Curator.class);
    props = new AgentsProperties();
    // Not 200, which is what this class ships as the default: a test that
    // used the shipped number could not tell "read from the property" from
    // "repeated the property's value somewhere else".
    props.setCuratorBudget(37);
    passes = new Passes(jobs, curator, props);
  }

  // --- the refusal, and why it is curation's own sentence -----------------------

  /**
   * There is no global pass, and the refusal says why rather than saying "project is required":
   * promotion is what puts a project's memory into global, so a pass over global would have nowhere
   * to promote to.
   */
  @Test
  void a_pass_with_no_project_is_refused_and_says_why_there_is_no_global_pass() {
    CallerFault refused = assertThrows(CallerFault.class, () -> passes.start(null, null));

    assertTrue(refused.getMessage().contains("curating needs a project"), refused.getMessage());
    assertTrue(refused.getMessage().contains("nowhere to promote to"), refused.getMessage());
    verify(jobs, never()).submit(anyString(), any(), any());
  }

  /** Blank is the same fact as absent — a name nothing can be promoted into. */
  @Test
  void a_blank_project_is_refused_the_same_way() {
    CallerFault refused = assertThrows(CallerFault.class, () -> passes.start("   ", null));

    assertTrue(refused.getMessage().contains("nowhere to promote to"), refused.getMessage());
    verify(jobs, never()).submit(anyString(), any(), any());
  }

  /**
   * A budget of zero is a pass that can do nothing, and {@code Budget.of} already refuses it — as a
   * fault this caller can read, not as a constructor blowing up.
   */
  @Test
  void a_budget_of_nothing_is_refused_before_anything_is_submitted() {
    CallerFault refused = assertThrows(CallerFault.class, () -> passes.start("payments", 0));

    assertTrue(refused.getMessage().contains("at least one model call"), refused.getMessage());
    verify(jobs, never()).submit(anyString(), any(), any());
  }

  // --- the ordering ------------------------------------------------------------

  /**
   * The project first, sent as one body that is wrong in both ways.
   *
   * <p>Swap the two and a caller who named no project is told about a number, when what they have
   * to change is the project.
   */
  @Test
  void the_missing_project_is_named_before_the_budget_is() {
    CallerFault refused = assertThrows(CallerFault.class, () -> passes.start(null, 0));

    assertTrue(refused.getMessage().contains("nowhere to promote to"), refused.getMessage());
  }

  // --- the silent default ------------------------------------------------------

  /**
   * <b>The rule nothing refuses.</b> With no number from the caller, the configured one — read from
   * {@link AgentsProperties} here, where the decision is, rather than by whoever is calling.
   */
  @Test
  void a_pass_with_no_budget_takes_the_configured_curator_budget() {
    when(jobs.submit(anyString(), any(), any())).thenReturn("job_000007");
    when(curator.pass(anyString(), any(), any()))
        .thenReturn(new Outcome(Ending.ANSWERED, "This pass finished.", 0, 0, ""));

    passes.start("payments", null);

    assertEquals(
        37,
        budgetOfTheSubmittedPass().limit(),
        "the default is the property's, read from it rather than repeated here");
  }

  /**
   * And a number the caller did name is the pass's own, untouched by the default that would have
   * applied.
   */
  @Test
  void a_budget_the_caller_named_is_the_passs_own() {
    when(jobs.submit(anyString(), any(), any())).thenReturn("job_000007");
    when(curator.pass(anyString(), any(), any()))
        .thenReturn(new Outcome(Ending.ANSWERED, "This pass finished.", 0, 0, ""));

    passes.start("payments", 12);

    assertEquals(12, budgetOfTheSubmittedPass().limit());
  }

  // --- what a pass that is not refused does ------------------------------------

  @Test
  void the_pass_is_submitted_under_the_curators_name_in_the_project_it_names() {
    when(jobs.submit(anyString(), any(), any())).thenReturn("job_000007");

    Passes.Started started = passes.start("payments", 12);

    assertEquals("job_000007", started.id());
    assertEquals(Curator.BY, started.agent());
    verify(jobs).submit(eq(Curator.BY), eq(Home.of("payments")), any());
  }

  /**
   * The captured work really is a pass over the project that was asked for, invoked rather than
   * asserted about: "submit was called" is satisfied by a caller that submitted the wrong pass, or
   * an empty one.
   */
  private Budget budgetOfTheSubmittedPass() {
    ArgumentCaptor<Function<BooleanSupplier, Outcome>> work = workCaptor();
    verify(jobs).submit(eq(Curator.BY), eq(Home.of("payments")), work.capture());
    work.getValue().apply(() -> false);

    ArgumentCaptor<Budget> budget = ArgumentCaptor.forClass(Budget.class);
    verify(curator).pass(eq("payments"), budget.capture(), any());
    return budget.getValue();
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<Function<BooleanSupplier, Outcome>> workCaptor() {
    return ArgumentCaptor.forClass(Function.class);
  }
}
