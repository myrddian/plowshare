package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnHistoryRepository;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResolvedConversationRenameTest {
  private static ConversationRecord row(String project, Budget allowance) {
    return new ConversationRecord(
        "cnv_stable",
        Home.of(project),
        Origin.TURN,
        ConversationLifecycle.ACTIVE,
        null,
        null,
        Instant.EPOCH,
        null,
        allowance,
        null,
        null);
  }

  @Test
  void resolvedDoorCannotReplaceTheNormalSpeakableGuardOrOmitCurrentWork() {
    var jobs = mock(JobStore.class);
    var rows = mock(ConversationStore.class);
    var logs = mock(Compaction.class);
    var definition = mock(AgentDefinition.class);
    when(definition.name()).thenReturn("worker");
    var allowance = Budget.of(10);
    when(rows.find("cnv_stable"))
        .thenReturn(
            Optional.of(
                new ConversationRecord(
                    "cnv_stable",
                    Home.of("house"),
                    Origin.DELEGATION,
                    ConversationLifecycle.ACTIVE,
                    "cnv_parent",
                    "worker",
                    Instant.EPOCH,
                    null,
                    allowance,
                    null,
                    null)));
    var turn = new Turn(jobs, rows, mock(TurnHistoryRepository.class), logs);
    var checked = new AtomicInteger();
    assertThrows(
        Turn.Refused.class,
        () ->
            turn.speakResolved(
                "cnv_stable",
                definition,
                "work",
                null,
                null,
                25,
                Speaker.person("alice"),
                ignored -> {},
                ignored -> checked.incrementAndGet()));
    assertThrows(
        NullPointerException.class,
        () ->
            turn.speakResolved(
                "cnv_stable",
                definition,
                "work",
                null,
                null,
                25,
                Speaker.person("alice"),
                ignored -> {},
                null));
    assertEquals(0, checked.get());
    assertEquals(10, allowance.limit());
    assertFalse(turn.isSpeaking("cnv_stable"));
    verify(rows).find("cnv_stable");
    verifyNoInteractions(jobs, logs);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void renameDuringDefinitionLoadingChecksTheCurrentTargetBeforeAnyAllowanceChange(
      boolean originalAuthorized) throws Exception {
    var callers = mock(Callers.class);
    var jobs = mock(JobStore.class);
    var rows = mock(ConversationStore.class);
    var logs = mock(Compaction.class);
    var definition = mock(AgentDefinition.class);
    when(definition.name()).thenReturn("worker");
    when(definition.maxTurns()).thenReturn(8);
    var allowance = Budget.of(10);
    var durableRaises = new AtomicInteger();
    allowance.bindAllowance(() -> Optional.empty(), ignored -> durableRaises.incrementAndGet());
    var snapshot = row("house", allowance);
    var current = new AtomicReference<>(snapshot);
    when(rows.find("cnv_stable")).thenAnswer(ignored -> Optional.of(current.get()));
    var loaded = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    when(callers.requireAgent(eq("worker"), any()))
        .thenAnswer(
            ignored -> {
              loaded.complete(null);
              release.orTimeout(2, TimeUnit.SECONDS).join();
              return definition;
            });
    var transcript = mock(Compaction.TurnTranscript.class);
    when(logs.transcriptFor(eq("cnv_stable"), eq(definition), any(Speaker.class)))
        .thenReturn(transcript);
    var ending = new AtomicReference<Consumer<Outcome>>();
    when(jobs.submit(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(true)))
        .thenAnswer(
            call -> {
              ending.set(call.getArgument(7));
              return "job_normal";
            });
    var turn = new Turn(jobs, rows, mock(TurnHistoryRepository.class), logs);
    var runs = new Runs(callers, jobs, turn);
    if (!originalAuthorized)
      doThrow(new CallerFault("Original project work access was withdrawn"))
          .when(callers)
          .requireWork("renamed", "alice");
    else
      doThrow(new CallerFault("Replacement project is unrelated"))
          .when(callers)
          .requireWork("house", "alice");
    var ask =
        new Runs.Ask(
            "worker",
            "work",
            null,
            null,
            "cnv_stable",
            null,
            null,
            List.of(),
            Speaker.person("alice"),
            "alice",
            null,
            null,
            25);
    var result = new CompletableFuture<Runs.Started>();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    result.complete(
                        runs.startResolved(
                            ask,
                            mock(Runs.Shown.class),
                            new ConversationRuns.Resolved(snapshot, 42, "alice")));
                  } catch (Throwable failed) {
                    result.completeExceptionally(failed);
                  }
                });
    try {
      loaded.get(2, TimeUnit.SECONDS);
      // The immutable alias-root FK still names project 42. Its current name changes; the old name
      // can now denote a replacement project whose Work role is deliberately the opposite.
      current.set(row("renamed", allowance));
      release.complete(null);
      if (!originalAuthorized) {
        var refused =
            assertThrows(
                CompletionException.class, () -> result.orTimeout(2, TimeUnit.SECONDS).join());
        assertEquals(CallerFault.class, refused.getCause().getClass());
        assertEquals(0, durableRaises.get());
        assertEquals(10, allowance.limit());
        verifyNoInteractions(jobs, logs);
      } else {
        assertEquals("cnv_stable", result.get(2, TimeUnit.SECONDS).conversation());
        assertEquals(1, durableRaises.get());
        assertEquals(25, allowance.limit());
        assertNotNull(ending.get());
        ending.get().accept(new Outcome(Outcome.Ending.CANCELLED, "Cancelled", 0, 0, ""));
      }
      verify(callers).requireWork("renamed", "alice");
      verify(callers, never()).requireWork("house", "alice");
      verify(callers, never()).homeOfConversation(anyString());
      verify(rows).find("cnv_stable");
      assertFalse(turn.isSpeaking("cnv_stable"));
    } finally {
      release.complete(null);
      worker.join(2000);
    }
  }
}
