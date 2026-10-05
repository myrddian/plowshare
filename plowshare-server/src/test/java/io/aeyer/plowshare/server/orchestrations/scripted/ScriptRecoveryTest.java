package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScriptRecoveryTest {
  @Test
  void uncertain_mutation_is_refused_but_recorded_results_and_readiness_observers_are_safe() {
    var journal = mock(ScriptStore.class, CALLS_REAL_METHODS);
    var mutation =
        new ScriptStore.Tool("file_write", "{\"path\":\"result.txt\",\"text\":\"result\"}", false);
    when(journal.latest("conductor"))
        .thenReturn(Optional.of(new ScriptStore.Step(0, "hash", mutation, null, null, true, null)));
    assertThrows(CallerFault.class, () -> journal.requireRecoverable("conductor"));
    when(journal.latest("conductor"))
        .thenReturn(
            Optional.of(new ScriptStore.Step(0, "hash", mutation, "receipt", null, true, null)));
    assertDoesNotThrow(() -> journal.requireRecoverable("conductor"));
    var observer =
        new ScriptStore.Tool(
            "information_read",
            "{\"operation\":\"await\",\"sources\":[{\"revision\":\"00000000-0000-0000-0000-000000000001\"}],\"waitMs\":0}",
            true);
    when(journal.latest("conductor"))
        .thenReturn(Optional.of(new ScriptStore.Step(0, "hash", observer, null, null, true, null)));
    assertDoesNotThrow(() -> journal.requireRecoverable("conductor"));
  }
}
