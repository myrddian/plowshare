package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.llm.accounting.CallLifecycle;
import io.aeyer.plowshare.server.llm.dispatch.InferenceCapture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RepairInferenceProgressTest {
  private final EmbeddingWorkRepository repository = mock(EmbeddingWorkRepository.class);
  private final EmbeddingRepair.Attempt attempt =
      new EmbeddingRepair.Attempt(
          UUID.randomUUID(),
          mock(EmbeddingProfile.class),
          new EmbeddingWorkRepository.Source(
              EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, "fixture"),
              1,
              "fixture source",
              null));
  private final RepairInferenceProgress progress = new RepairInferenceProgress(repository, attempt);

  @Test
  void canonicalQueueAndTerminalEventsDoNotDuplicateDatabaseWrites() {
    var call = UUID.randomUUID();
    var wire = UUID.randomUUID();
    progress.accounted(new InferenceCapture(call, false));
    progress.queued();
    progress.started();
    progress.attempting(wire);
    progress.response(200);
    progress.finished(CallLifecycle.SUCCEEDED);
    verify(repository, times(2)).repairProgress(eq(attempt), any());
    progress.phase(EmbeddingRepair.Phase.PUBLISHING);
    var checkpoints = ArgumentCaptor.forClass(EmbeddingRepair.Progress.class);
    verify(repository, times(3)).repairProgress(eq(attempt), checkpoints.capture());
    assertEquals(
        List.of(
            EmbeddingRepair.Phase.ACCOUNTED,
            EmbeddingRepair.Phase.ATTEMPT_STARTED,
            EmbeddingRepair.Phase.PUBLISHING),
        checkpoints.getAllValues().stream().map(EmbeddingRepair.Progress::phase).toList());
    var publication = checkpoints.getValue();
    assertEquals(call, publication.callId());
    assertEquals(wire, publication.wireAttemptId());
    assertEquals(200, publication.httpStatus());
    assertEquals(CallLifecycle.SUCCEEDED, publication.outcome());
    verifyNoMoreInteractions(repository);
  }

  @Test
  void nextWireAttemptNeverInheritsThePreviousHttpResponse() {
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    progress.attempting(first);
    progress.response(503);
    progress.attempting(second);
    var current = progress.snapshot(EmbeddingRepair.Phase.EXECUTION_FINISHED);
    assertEquals(second, current.wireAttemptId());
    assertNull(current.httpStatus());
  }
}
