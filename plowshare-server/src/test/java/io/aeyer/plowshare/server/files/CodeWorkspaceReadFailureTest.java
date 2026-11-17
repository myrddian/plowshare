package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeWorkspaceReadFailureTest {
  @TempDir Path temporary;

  private LocalProvider provider() throws Exception {
    Path root = temporary.toRealPath();
    Files.writeString(root.resolve("A.java"), "class A {}");
    return LocalProvider.over(
        FileAccess.of(List.of(root), List.of()), List.of(new Grant(Scope.WORKSPACE, Mode.READ)));
  }

  @Test
  void failedReadDoesNotPublishAnEmptyForegroundManifest() throws Exception {
    var provider = provider();
    var observations = mock(CodeMapObservations.class);
    var failure = ReadFailedException.deadlineExceeded();
    when(observations.cached(any(), any())).thenThrow(failure);
    var map =
        new WorkspaceCodeMap(new ProviderRouter(home -> List.of(provider)), () -> false)
            .observing(observations);
    assertSame(
        failure, assertThrows(ReadFailedException.class, () -> map.reconcile(Home.global(), "**")));
    verify(observations, never()).publish(any(), any());
  }

  @Test
  void onlyRetryableIndependentReadFailuresDeferTheRetainedScan() throws Exception {
    var provider = provider();
    for (var failure :
        List.of(ReadFailedException.deadlineExceeded(), ReadFailedException.invalidated())) {
      var store = mock(CodeWorkspaceStore.class);
      var indexes = mock(CodeIndexes.class);
      var scope = new CodeWorkspaceStore.Scope(Home.global(), "reader", "coder", "session");
      var ticket = new CodeMapObservations.Ticket("workspace", 1);
      when(store.claim()).thenReturn(Optional.of(new CodeWorkspaceStore.Scan(ticket, scope, "**")));
      when(indexes.states(eq(scope), anyList())).thenThrow(failure);
      try (var monitor =
          new CodeWorkspaceMonitor(
                  store, ignored -> new ProviderRouter(home -> List.of(provider)), true)
              .indexing(() -> indexes)) {
        assertTrue(monitor.pollOnce());
        verify(store).defer(ticket);
        verify(store, never()).publish(any(), any());
        verify(store, never()).unavailable(any(), anyString());
      }
    }
  }

  @Test
  void interruptionClosureAndCancellationDoNotReplayOrEraseTheClaim() throws Exception {
    var provider = provider();
    for (var failure :
        List.of(
            ReadFailedException.interrupted(),
            ReadFailedException.closed(),
            ReadFailedException.queryCancelled(null))) {
      var store = mock(CodeWorkspaceStore.class);
      var indexes = mock(CodeIndexes.class);
      var scope = new CodeWorkspaceStore.Scope(Home.global(), "reader", "coder", "session");
      var ticket = new CodeMapObservations.Ticket("workspace", 1);
      when(store.claim()).thenReturn(Optional.of(new CodeWorkspaceStore.Scan(ticket, scope, "**")));
      when(indexes.states(eq(scope), anyList())).thenThrow(failure);
      try (var monitor =
          new CodeWorkspaceMonitor(
                  store, ignored -> new ProviderRouter(home -> List.of(provider)), true)
              .indexing(() -> indexes)) {
        assertSame(failure, assertThrows(ReadFailedException.class, monitor::pollOnce));
        verify(store, never()).defer(any());
        verify(store, never()).publish(any(), any());
        verify(store, never()).unavailable(any(), anyString());
        if (failure.reason() == ReadFailedException.Reason.INTERRUPTED)
          assertTrue(Thread.currentThread().isInterrupted());
        if (failure.reason() == ReadFailedException.Reason.CLOSED) assertFalse(monitor.pollOnce());
      } finally {
        Thread.interrupted();
      }
    }
  }
}
