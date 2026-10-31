package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.reads.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkspaceChangesTest {
  @Test
  void manySocketsAndDuplicateEventsProduceOneAuthorizedDatabaseBatch() {
    var store = mock(CodeWorkspaceStore.class);
    when(store.claim()).thenReturn(Optional.empty());
    var authorized = new ArrayList<WorkspaceChanges.Change>();
    try (var monitor =
        new CodeWorkspaceMonitor(
                store,
                ignored -> {
                  throw new AssertionError();
                },
                true)
            .changes(authorized::add, ReadAdmission.direct())) {
      for (int event = 0; event < 1000; event++)
        monitor.changed(
            new WorkspaceChanges.Change(Home.of("project"), "alice", "socket-" + event));
      verifyNoInteractions(store);
      assertFalse(monitor.pollOnce());
      var expected = new WorkspaceChanges.Change(Home.of("project"), "alice", "socket-999");
      assertEquals(List.of(expected), authorized);
      verify(store).changed(List.of(expected));
      monitor.pollOnce();
      verify(store, times(1)).changed(anyList());
    }
  }

  @Test
  void revocationBeforeFlushCannotWakeRetainedWorkspaces() {
    var store = mock(CodeWorkspaceStore.class);
    when(store.claim()).thenReturn(Optional.empty());
    try (var monitor =
        new CodeWorkspaceMonitor(
                store,
                ignored -> {
                  throw new AssertionError();
                },
                true)
            .changes(
                change -> {
                  throw new WorkspaceRefusedException("revoked");
                },
                ReadAdmission.direct())) {
      monitor.changed(new WorkspaceChanges.Change(Home.of("project"), "alice", "session"));
      monitor.pollOnce();
      verify(store, never()).changed(anyList());
    }
  }

  @Test
  void capacityDefersHintsWithoutExpandingTheBoundedQueue() {
    var store = mock(CodeWorkspaceStore.class);
    when(store.claim()).thenReturn(Optional.empty());
    var busy = new AtomicBoolean(true);
    ReadAdmission admission =
        (principal, work) -> {
          assertEquals(ReadAdmission.Work.MAINTENANCE, work);
          if (busy.get()) throw new ReadUnavailableException();
          return () -> {};
        };
    try (var monitor =
        new CodeWorkspaceMonitor(
                store,
                ignored -> {
                  throw new AssertionError();
                },
                true)
            .changes(change -> {}, admission)) {
      for (int project = 0; project < 1000; project++)
        monitor.changed(
            new WorkspaceChanges.Change(Home.of("project-" + project), "alice", "socket"));
      monitor.pollOnce();
      verify(store, never()).changed(anyList());
      busy.set(false);
      monitor.pollOnce();
      @SuppressWarnings("unchecked")
      ArgumentCaptor<List<WorkspaceChanges.Change>> batch = ArgumentCaptor.forClass(List.class);
      verify(store).changed(batch.capture());
      assertEquals(64, batch.getValue().size());
    }
  }
}
