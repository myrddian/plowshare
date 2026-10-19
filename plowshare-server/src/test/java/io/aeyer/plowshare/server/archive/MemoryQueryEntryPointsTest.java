package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.MemoryTools;
import io.aeyer.plowshare.server.agents.learner.Reminder;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.api.MemoryController;
import io.aeyer.plowshare.server.api.RecallRequest;
import io.aeyer.plowshare.server.embedding.QueryEmbeddings;
import io.aeyer.plowshare.server.ws.Asking;
import io.aeyer.plowshare.server.ws.MemoryRecallHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemoryQueryEntryPointsTest {
  final Archive archive = mock(Archive.class);

  @Test
  void framesAndHttpBindAuthenticatedContextWithoutAccountingEnabled() {
    when(archive.querying()).thenReturn(true);
    when(archive.recall(any(MemoryQueries.Request.class)))
        .thenReturn(new Archive.Recall(List.of(), 0));
    var alive = new AtomicBoolean(true);
    var asking =
        new Asking(
            null,
            "reader",
            null,
            io.aeyer.plowshare.server.relay.RelayReadBudget.LEGACY,
            () -> {
              if (!alive.get()) throw new IllegalStateException("socket closed");
            });
    new MemoryRecallHandler(archive)
        .handle(Map.of("project", "project", "question", "query"), asking);
    new MemoryController(archive, mock(Scribe.class))
        .recall(new RecallRequest("project", "query", 5), "service");
    var requests = ArgumentCaptor.forClass(MemoryQueries.Request.class);
    verify(archive, times(2)).recall(requests.capture());
    var frame = requests.getAllValues().getFirst();
    assertEquals("reader", frame.account());
    assertFalse(frame.runBound());
    assertEquals("service", requests.getAllValues().getLast().account());
    alive.set(false);
    assertThrows(IllegalStateException.class, frame.origin().validity()::run);
    verify(archive, never()).usage(any(), any(), any());
  }

  @Test
  void reminderNeedsItsBoundTurnButKeepsEligibilityAndBestEffortBehavior() {
    when(archive.querying()).thenReturn(true);
    when(archive.survey(any(MemoryQueries.Request.class)))
        .thenReturn(
            new Archive.Survey(List.of(new TocEntry("mem_one", "knowledge", "scope", false)), 0));
    var eligible =
        new AgentDefinition(
            "reader",
            "read",
            "model",
            List.of(MemoryTools.RECALL_NAME),
            List.of(),
            List.of(),
            1,
            1,
            "read");
    var home = Home.of("project");
    var unbound = new Reminder(archive);
    assertTrue(unbound.whatTheArchiveHolds(eligible, home, "query").isEmpty());
    verify(archive, never()).survey(any(MemoryQueries.Request.class));
    var bound =
        unbound.forQueries(
            "reader", home, () -> new QueryEmbeddings.Origin("retained-log:turn:1", () -> {}));
    assertTrue(bound.whatTheArchiveHolds(eligible, home, "query").isPresent());
    var request = ArgumentCaptor.forClass(MemoryQueries.Request.class);
    verify(archive).survey(request.capture());
    assertTrue(request.getValue().runBound());
    assertEquals(MemoryQueries.Kind.REMINDER, request.getValue().kind());
    assertEquals("retained-log:turn:1", request.getValue().origin().invocation());
    var ineligible =
        new AgentDefinition(
            "reader", "read", "model", List.of(), List.of(), List.of(), 1, 1, "read");
    assertTrue(bound.whatTheArchiveHolds(ineligible, home, "query").isEmpty());
    verify(archive, times(1)).survey(any(MemoryQueries.Request.class));
  }
}
