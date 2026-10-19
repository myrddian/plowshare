package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds;
import io.aeyer.plowshare.server.access.SystemOperations;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.harness.Harness;
import io.aeyer.plowshare.server.hooks.Hooks;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InformationControlHooksTest {
  @ParameterizedTest
  @ValueSource(strings = {"tags", "tagGroups", "rebuild"})
  void manualControlsUseTheirOwnLogAndLiveFenceWithoutBorrowingAWorkerLease(String stage) {
    var stages = mock(InformationStageRepository.class);
    var processing = mock(InformationProcessingRepository.class);
    var conversations = mock(ConversationStore.class);
    var inputs = mock(InformationJobs.class);
    var system = mock(SystemOperations.class);
    var log = mock(ConversationRecord.class);
    when(log.id()).thenReturn("control-log");
    var event =
        new InformationLifecycle.Lease(
            UUID.randomUUID(), UUID.randomUUID(), 1, stage, 1, UUID.randomUUID(), "owner", 1L);
    when(stages.logState(event.revision(), 1L))
        .thenReturn(new InformationStageRepository.LogState("fixture", "worker-log", 5, 0, null));
    when(processing.budgetSource(event.revision()))
        .thenReturn(
            new InformationProcessingRepository.BudgetSource(UUID.randomUUID(), 5, 0, null));
    var scope = new AuthorityIds.ScopeId(UUID.randomUUID());
    when(processing.home(event.revision())).thenReturn(scope);
    var captured = new AtomicReference<Runnable>();
    var work = mock(SystemOperations.Work.class);
    when(system.admit(
            eq(SystemOperations.Purpose.DOCUMENT_PREPARATION),
            eq(Home.of("fixture")),
            eq(scope),
            any(),
            anyString(),
            any()))
        .thenAnswer(
            invocation -> {
              Runnable fence = invocation.getArgument(5);
              fence.run();
              captured.set(fence);
              return work;
            });
    when(conversations.logSystem(
            any(),
            any(),
            eq("document_pipeline"),
            isNull(),
            any(),
            eq("owner"),
            isNull(),
            eq(work)))
        .thenReturn(log);
    var hooks =
        new InformationStageHooks(
            stages,
            mock(InformationCatalogue.class),
            conversations,
            LogStages.NONE,
            inputs,
            Hooks.NONE,
            Harness.NONE,
            () -> null,
            system,
            processing);
    var allowed = new AtomicBoolean(true);
    var control =
        new InformationLifecycle.Control(
            event,
            () -> {
              if (!allowed.get()) throw new IllegalStateException("authority withdrawn");
            });
    assertFalse(hooks.beforeControl(control).isDenied());
    assertFalse(hooks.afterControl(control).isDenied());
    verify(conversations, times(1))
        .logSystem(any(), any(), anyString(), isNull(), any(), anyString(), isNull(), any());
    verify(processing, never()).requireLease(any());
    verify(stages, never()).pinLog(any(), anyString());
    verify(stages, never()).replaceLog(any(), anyString(), anyString());
    allowed.set(false);
    assertThrows(IllegalStateException.class, captured.get()::run);
    assertThrows(IllegalStateException.class, () -> hooks.afterControl(control));
    hooks.finished(event);
  }

  @Test
  void defaultControlGatesStillCheckTheCallerFence() {
    var event =
        new InformationLifecycle.Lease(
            UUID.randomUUID(), UUID.randomUUID(), 1, "tags", 1, UUID.randomUUID(), "owner", 1L);
    var control =
        new InformationLifecycle.Control(
            event,
            () -> {
              throw new IllegalStateException("expired command");
            });
    var gates = new InformationLifecycle.Gates() {};
    assertThrows(IllegalStateException.class, () -> gates.beforeControl(control));
    assertThrows(IllegalStateException.class, () -> gates.afterControl(control));
  }
}
