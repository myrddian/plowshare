package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR 0069 at {@code agent.run}'s decision: which bodies may carry {@code maxModelCalls}, and that
 * the raise is only ever handed to {@link Turn} after the caller's work authority over the
 * conversation is proved. What the raise then does to the allowance is {@code
 * TurnAllowanceRaiseTest}'s.
 */
class RunsAllowanceRaiseTest {

  private final JobStore jobs = mock(JobStore.class);
  private final Turn turns = mock(Turn.class);
  private final ProjectStore projects = mock(ProjectStore.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final AgentRegistry registry = new AgentRegistry(Map.of("scribe", agent()));
  private Runs runs;

  @BeforeEach
  void setUp() {
    when(projects.id("ledger")).thenReturn(8L);
    when(turns.homeOf("cnv_ledger")).thenReturn(Home.of("ledger"));
    when(members.mayUse("ledger", "alice")).thenReturn(true);
    when(members.mayWork("ledger", "alice")).thenReturn(true);
    when(members.mayUse("ledger", "bob")).thenReturn(true);
    runs =
        new Runs(
            new Callers(
                new DefinitionResolver(
                    registry,
                    DataLayout.NONE,
                    id -> true,
                    Set.of(),
                    Set.of(),
                    mock(SessionChannel.class),
                    session -> true,
                    DefinitionChecks.NONE),
                projects,
                turns,
                new CallerAccess(new SessionRegistry(), members)),
            jobs,
            turns);
  }

  @Test
  void a_total_for_an_existing_conversation_reaches_the_turn_that_applies_it() {
    when(turns.speak(
            anyString(), any(), anyString(), any(), any(), anyInt(), any(Speaker.class), any()))
        .thenReturn("job_000001");

    Runs.Started started = runs.start(ask("alice", "cnv_ledger", null, null, 60), shown());

    assertEquals("job_000001", started.id());
    verify(turns)
        .speak(
            eq("cnv_ledger"),
            eq(registry.get("scribe")),
            eq("go"),
            isNull(),
            isNull(),
            eq(60),
            eq(Speaker.person("alice")),
            any());
  }

  @Test
  void an_utterance_naming_no_total_takes_the_door_it_always_took() {
    when(turns.speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any()))
        .thenReturn("job_000002");

    runs.start(ask("alice", "cnv_ledger", null, null, null), shown());

    verify(turns)
        .speak(
            eq("cnv_ledger"),
            eq(registry.get("scribe")),
            eq("go"),
            isNull(),
            isNull(),
            eq(Speaker.person("alice")),
            any());
    verifyNoRaise();
  }

  @Test
  void a_total_without_an_existing_conversation_is_refused_before_anything_runs() {
    CallerFault refused =
        assertThrows(
            CallerFault.class, () -> runs.start(ask("alice", null, "ledger", null, 60), shown()));

    assertTrue(refused.getMessage().contains("needs a 'conversation'"), refused.getMessage());
    verifyNoInteractions(jobs);
    verifyNoRaise();
  }

  @Test
  void a_total_beside_new_conversation_is_refused_before_anything_is_opened() {
    CallerFault refused =
        assertThrows(
            CallerFault.class, () -> runs.start(ask("alice", null, "ledger", true, 60), shown()));

    assertTrue(refused.getMessage().contains("'newConversation'"), refused.getMessage());
    verifyNoInteractions(jobs);
    verifyNoRaise();
  }

  @Test
  void a_total_that_is_not_positive_is_refused_at_the_boundary() {
    for (int total : new int[] {0, -5}) {
      CallerFault refused =
          assertThrows(
              CallerFault.class,
              () -> runs.start(ask("alice", "cnv_ledger", null, null, total), shown()));
      assertTrue(refused.getMessage().contains("at least one model call"), refused.getMessage());
    }
    verifyNoInteractions(jobs);
    verifyNoRaise();
  }

  @Test
  void a_caller_without_work_authority_is_refused_before_any_raise() {
    // bob may read the project but may not work in it, which is what speaking requires.
    assertThrows(
        CallerFault.class, () -> runs.start(ask("bob", "cnv_ledger", null, null, 60), shown()));

    verifyNoInteractions(jobs);
    verifyNoRaise();
    verify(turns, never())
        .speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any());
  }

  private void verifyNoRaise() {
    verify(turns, never())
        .speak(anyString(), any(), anyString(), any(), any(), anyInt(), any(Speaker.class), any());
  }

  private static Runs.Ask ask(
      String handle,
      String conversation,
      String project,
      Boolean newConversation,
      Integer maxModelCalls) {
    return new Runs.Ask(
        "scribe",
        "go",
        project,
        null,
        conversation,
        null,
        null,
        List.of(),
        Speaker.person(handle),
        handle,
        newConversation,
        null,
        maxModelCalls);
  }

  private static Runs.Shown shown() {
    return (definition, home, uids) -> List.of();
  }

  private static AgentDefinition agent() {
    return new AgentDefinition(
        "scribe",
        "a fixture",
        "fast",
        List.of(),
        List.of(),
        List.of(),
        2,
        4,
        "You do one thing.",
        true,
        true);
  }
}
