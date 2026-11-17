package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CodeWorkspaceHintFailureTest {
  @Test
  void aPureAuthorizationReadFailureRetainsHintsAndKeepsTheNewestCoalescedSession() {
    for (var failure :
        List.of(ReadFailedException.deadlineExceeded(), ReadFailedException.invalidated())) {
      var store = mock(CodeWorkspaceStore.class);
      when(store.claim()).thenReturn(Optional.empty());
      var first = new AtomicBoolean(true);
      var owner = new AtomicReference<CodeWorkspaceMonitor>();
      var original = new WorkspaceChanges.Change(Home.of("project"), "alice", "old-session");
      var latest = new WorkspaceChanges.Change(Home.of("project"), "alice", "new-session");
      try (var monitor =
          new CodeWorkspaceMonitor(
                  store,
                  ignored -> {
                    throw new AssertionError("No scan was claimed");
                  },
                  true)
              .changes(
                  change -> {
                    if (first.getAndSet(false)) {
                      owner.get().changed(latest);
                      throw failure;
                    }
                  },
                  ReadAdmission.direct())) {
        owner.set(monitor);
        monitor.changed(original);
        assertFalse(monitor.pollOnce());
        assertFalse(monitor.pollOnce());
        assertFalse(monitor.pollOnce());
        verify(store).changed(List.of(latest));
        verify(store, times(1)).changed(anyList());
      }
    }
  }

  @Test
  void aFailureAfterPublicationStartsDoesNotReplayAPossiblyAppliedMutation() {
    for (RuntimeException failure :
        List.of(ReadFailedException.deadlineExceeded(), new ReadUnavailableException())) {
      var store = mock(CodeWorkspaceStore.class);
      when(store.claim()).thenReturn(Optional.empty());
      doThrow(failure).when(store).changed(anyList());
      var change = new WorkspaceChanges.Change(Home.of("project"), "alice", "session");
      try (var monitor =
          new CodeWorkspaceMonitor(
                  store,
                  ignored -> {
                    throw new AssertionError("No scan was claimed");
                  },
                  true)
              .changes(ignored -> {}, ReadAdmission.direct())) {
        monitor.changed(change);
        assertSame(failure, assertThrows(RuntimeException.class, monitor::pollOnce));
        assertFalse(monitor.pollOnce());
        verify(store, times(1)).changed(List.of(change));
      }
    }
  }
}
