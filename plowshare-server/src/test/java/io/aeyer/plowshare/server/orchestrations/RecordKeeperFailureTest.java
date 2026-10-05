package io.aeyer.plowshare.server.orchestrations;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.Outcome;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies causal diagnostic presentation at the recorder boundary without a database. */
class RecordKeeperFailureTest {
  @Test
  void a_failed_delegate_retains_the_cause_beside_its_terminal_text() {
    var records = mock(RecordStore.class);
    var runs = mock(OrchestrationStore.class);
    when(records.placeOf("cnv_parent"))
        .thenReturn(Optional.of(new RecordStore.Place("orc_root", "alice", "orc_root", true)));
    var keeper = new RecordKeeper(records, runs, () -> null);
    var failure =
        new Outcome(
            Outcome.Ending.UNAVAILABLE,
            "The model connection failed.",
            0,
            1,
            "LlmTransportException: connection reset (HTTP phase: request_headers; attempt: 2; cause: SocketException)");
    keeper.delegateReturned("cnv_parent", "fixture", "research_analyst", failure);
    verify(records)
        .append(
            eq("orc_root"),
            eq("orc_root"),
            eq("conductor"),
            eq(RecordKind.DELEGATE_RETURNED),
            eq("research_analyst → conductor: unavailable: The model connection failed."),
            eq(failure.detail()),
            isNull(),
            eq(failure.failureText()));
  }
}
