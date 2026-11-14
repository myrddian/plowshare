package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.AuthorityWithdrawnException;
import io.aeyer.plowshare.server.access.OperationFence;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.harness.Harness;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.StageDone;
import io.aeyer.plowshare.server.hooks.StageStart;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Stubber;

/**
 * A run's {@link OperationFence} runs in every transaction a gated information write opens, before
 * the pipeline log is opened and before the hook chain fires, so a revoked run fires no hook and
 * leaves no new gate or log row. Callers with {@link OperationFence#none()} keep replay unchanged.
 */
class InformationWriteGatesFenceTest {
  private static final String ACCOUNT = "author";
  private static final String HASH = "0".repeat(64);

  private final InformationGateRepository repository = mock(InformationGateRepository.class);
  private final UnitOfWork work = mock(UnitOfWork.class);
  private final InformationAccess access = mock(InformationAccess.class);
  private final InformationJobs inputs = mock(InformationJobs.class);
  private final ConversationStore conversations = mock(ConversationStore.class);
  private final LogStages logs = mock(LogStages.class);
  private final List<String> events = new ArrayList<>();
  private final UUID request = UUID.randomUUID();
  private final InformationContext context =
      new InformationContext(ACCOUNT, InformationContext.Selection.personal());
  private final InformationGateResult.Recorded result =
      new InformationGateResult.Recorded(UUID.randomUUID());
  private final Hooks hooks =
      new Hooks() {
        @Override
        public Gate stagePre(HookContext hookContext, StageStart start) {
          events.add("hook:pre");
          return Gate.NOTHING;
        }

        @Override
        public Gate stagePost(HookContext hookContext, StageDone done) {
          events.add("hook:post");
          return Gate.NOTHING;
        }
      };
  private final InformationWriteGates gates =
      new InformationWriteGates(
          repository, work, access, inputs, conversations, logs, hooks, Harness.NONE);

  @BeforeEach
  void recording() {
    when(work.inTransaction(any()))
        .thenAnswer(
            call -> {
              Supplier<?> body = call.getArgument(0);
              events.add("begin");
              try {
                Object value = body.get();
                events.add("commit");
                return value;
              } catch (RuntimeException failed) {
                events.add("rollback");
                throw failed;
              }
            });
    ConversationRecord opened = mock(ConversationRecord.class);
    when(opened.id()).thenReturn("cnv_pipeline");
    when(conversations.log(any(), any(), anyString(), isNull(), any(), anyString(), isNull()))
        .thenAnswer(
            call -> {
              events.add("log");
              return opened;
            });
    record("claim").when(repository).claim(anyString(), any(), anyString(), anyString(), any());
    record("opened").when(repository).opened(anyString(), any(), anyString());
    record("preGate").when(repository).preGate(anyString(), any(), any(), any());
    record("postGate").when(repository).postGate(anyString(), any(), any(), any());
    record("approved").when(repository).approved(anyString(), any());
    record("completed").when(repository).completed(anyString(), any(), any());
    record("blocked").when(repository).blocked(anyString(), any(), any(), any());
  }

  private Stubber record(String name) {
    return doAnswer(
        call -> {
          events.add(name);
          return name.equals("claim") ? claimed : null;
        });
  }

  private InformationGateRepository.Receipt<?> claimed =
      receipt(InformationGateRepository.State.RESERVED, null);

  private InformationGateRepository.Receipt<InformationGateResult.Recorded> receipt(
      InformationGateRepository.State state, String log) {
    return new InformationGateRepository.Receipt<>(
        UUID.randomUUID(),
        state,
        state == InformationGateRepository.State.COMPLETED ? result : null,
        log);
  }

  /** Passes the first {@code passes} checks, then refuses as a revocation does. */
  private OperationFence revokedAfter(int passes) {
    int[] seen = {0};
    return () -> {
      events.add("fence");
      if (seen[0]++ >= passes)
        throw new OperationFence.Withdrawn(new AuthorityWithdrawnException("revoked"));
    };
  }

  private InformationGateResult.Recorded execute(OperationFence fence) {
    return gates.executePrepared(
        context,
        request,
        "intake",
        new InformationGateIdentity.Intake(
            "notes.md", HASH, "text/markdown", null, InformationContext.Corpus.DOCUMENTS),
        List.of(),
        "session",
        InformationGateResult.Recorded.class,
        () ->
            new InformationTransitions.Prepared<>(
                () -> {
                  events.add("transition");
                  return result;
                },
                null),
        fence);
  }

  @Test
  void everyGateTransactionAndTheHookChainRunTheFence() {
    assertSame(result, execute(revokedAfter(Integer.MAX_VALUE)));

    assertEquals(
        List.of(
            "begin",
            "claim",
            "fence",
            "commit",
            "fence",
            "log",
            "begin",
            "fence",
            "opened",
            "commit",
            "fence",
            "hook:pre",
            "begin",
            "fence",
            "preGate",
            "commit",
            "hook:post",
            "begin",
            "fence",
            "postGate",
            "commit",
            "begin",
            "fence",
            "approved",
            "commit",
            "begin",
            "fence",
            "transition",
            "completed",
            "commit"),
        events);
  }

  @Test
  void aRevocationAtTheClaimLeavesNoReceiptLogOrHook() {
    assertThrows(OperationFence.Withdrawn.class, () -> execute(revokedAfter(0)));

    assertEquals(List.of("begin", "claim", "fence", "rollback"), events);
    verify(repository, never()).blocked(anyString(), any(), any(), any());
  }

  @Test
  void aRevocationAfterTheClaimOpensNoLogFiresNoHookAndBlocksTheReceipt() {
    assertThrows(OperationFence.Withdrawn.class, () -> execute(revokedAfter(1)));

    assertEquals(List.of("begin", "claim", "fence", "commit", "fence", "blocked"), events);
  }

  @Test
  void aRevocationBeforeTheHooksOfAnOpenedReceiptFiresNoHook() {
    claimed = receipt(InformationGateRepository.State.RESERVED, "cnv_pipeline");

    assertThrows(OperationFence.Withdrawn.class, () -> execute(revokedAfter(1)));

    assertEquals(List.of("begin", "claim", "fence", "commit", "fence", "blocked"), events);
  }

  @Test
  void aRunCannotReplayACompletedReceiptOnceRevoked() {
    claimed = receipt(InformationGateRepository.State.COMPLETED, "cnv_pipeline");

    assertThrows(OperationFence.Withdrawn.class, () -> execute(revokedAfter(0)));

    assertEquals(List.of("begin", "claim", "fence", "rollback"), events);
  }

  @Test
  void unfencedCallersStillReplayACompletedReceipt() {
    claimed = receipt(InformationGateRepository.State.COMPLETED, "cnv_pipeline");

    assertSame(result, execute(OperationFence.none()));

    assertEquals(List.of("begin", "claim", "commit"), events);
  }
}
