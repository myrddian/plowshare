package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.harness.Harness;
import io.aeyer.plowshare.server.hooks.Hooks;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Which {@link Home} a gated information transition opens its {@code document_pipeline} log
 * against.
 *
 * <p>{@code InformationWriteGates.executePrepared} derives that home from {@link
 * InformationContext.Selection#project()} alone. A PROJECT selection names a project, so it gets
 * {@code Home.of(project)}; every other scope is forbidden to name one by the {@code Selection}
 * constructor, so a PERSONAL selection reaches the same expression with {@code project == null} and
 * falls to {@code Home.global()}. These tests execute that branch rather than read it.
 */
class InformationWriteGatesHomeTest {

  private static final String ACCOUNT = "person@example.test";
  private static final String HASH =
      "0000000000000000000000000000000000000000000000000000000000000000";

  private final InformationGateRepository repository = mock(InformationGateRepository.class);
  private final UnitOfWork work = mock(UnitOfWork.class);
  private final InformationAccess access = mock(InformationAccess.class);
  private final InformationJobs inputs = mock(InformationJobs.class);
  private final ConversationStore conversations = mock(ConversationStore.class);
  private final LogStages logs = mock(LogStages.class);

  private final InformationWriteGates gates =
      new InformationWriteGates(
          repository, work, access, inputs, conversations, logs, Hooks.NONE, Harness.NONE);

  /**
   * The stubbing that reaches the home expression, and nothing more. A RESERVED receipt with no log
   * is exactly what {@code claim} returns for a first attempt at an upload, so this is the ordinary
   * path and not a contrived one: the only fixture-specific part is making {@code inTransaction}
   * actually run its supplier, which every real implementation does.
   */
  private void reachTheLogOpening(String log) {
    doReturn(
            new InformationGateRepository.Receipt<>(
                UUID.randomUUID(), InformationGateRepository.State.RESERVED, null, null))
        .when(repository)
        .claim(anyString(), any(), anyString(), anyString(), any());
    when(work.inTransaction(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    ConversationRecord opened = mock(ConversationRecord.class);
    when(opened.id()).thenReturn(log);
    when(conversations.log(any(), any(), anyString(), isNull(), any(), anyString(), isNull()))
        .thenReturn(opened);
  }

  private Home capturedHome() {
    ArgumentCaptor<Home> home = ArgumentCaptor.forClass(Home.class);
    verify(conversations)
        .log(
            eq(Origin.SUBMISSION),
            home.capture(),
            eq("document_pipeline"),
            isNull(),
            any(Budget.class),
            eq(ACCOUNT),
            isNull());
    return home.getValue();
  }

  private InformationGateResult.Recorded upload(InformationContext.Selection selection) {
    var context = new InformationContext(ACCOUNT, selection);
    return gates.executePrepared(
        context,
        UUID.randomUUID(),
        "intake",
        new InformationGateIdentity.Intake(
            "notes.md", HASH, "text/markdown", null, InformationContext.Corpus.DOCUMENTS),
        List.of(),
        "session-1",
        InformationGateResult.Recorded.class,
        () ->
            new InformationTransitions.Prepared<>(
                () -> new InformationGateResult.Recorded(UUID.randomUUID()), null));
  }

  /**
   * CONFIRMED DEFECT, pinned as it stands. A PERSONAL upload opens its {@code document_pipeline}
   * log against the GLOBAL home, because {@code Selection} forbids a non-PROJECT scope from naming
   * a project and the gate reads nothing else. Downstream admission therefore sees a Global write
   * from an account that has no Global write authority.
   *
   * <p><b>When this is fixed, invert the assertion</b> — a PERSONAL selection must open its log
   * against that account's own personal home, not {@code Home.global()}.
   */
  @Test
  void personal_selection_currently_opens_its_pipeline_log_against_the_global_home() {
    reachTheLogOpening("cnv_personal");

    assertNotNull(upload(InformationContext.Selection.personal()));

    Home home = capturedHome();
    assertTrue(
        home.isGlobal(),
        "a personal selection reached the gate's project==null branch and fell to global");
    assertEquals(Home.global(), home);
    assertNull(home.project());
  }

  /**
   * The control. A PROJECT selection runs the same expression with a project present, so it gets
   * that project's home — which proves the test above exercised the branch rather than a stub.
   */
  @Test
  void project_selection_opens_its_pipeline_log_against_that_project() {
    reachTheLogOpening("cnv_project");

    assertNotNull(upload(InformationContext.Selection.project("atlas")));

    Home home = capturedHome();
    assertEquals(Home.of("atlas"), home);
    assertFalse(home.isGlobal());
    assertEquals("atlas", home.project());
  }
}
