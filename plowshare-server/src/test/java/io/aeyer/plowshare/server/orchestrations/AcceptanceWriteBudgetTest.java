package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class AcceptanceWriteBudgetTest {
  OrchestrationAcceptance.Registered command(String run, int position) {
    return new OrchestrationAcceptance.Registered(
        run,
        position,
        "inspect " + position,
        List.of("inspect", Integer.toString(position)),
        null,
        0,
        null,
        "server",
        null,
        "once",
        "approval-" + position,
        Instant.parse("2026-10-01T12:00:00Z"),
        null);
  }

  @Test
  void replacementUsesBoundedValueStatementsAndRetainsEveryOrderedCommand() {
    var jdbc = mock(JdbcTemplate.class);
    var commands = IntStream.range(0, 65).mapToObj(index -> command("run", index)).toList();
    new JdbcOrchestrationAcceptance(jdbc, UnitOfWork.NONE).replace("run", "requirements", commands);
    var parameters = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc)
        .update(
            startsWith("INSERT INTO orchestration_acceptance_requirements"),
            eq("run"),
            eq("requirements"));
    verify(jdbc).update("DELETE FROM orchestration_acceptance WHERE orchestration = ?", "run");
    verify(jdbc, times(2))
        .update(startsWith("INSERT INTO orchestration_acceptance ("), parameters.capture());
    assertEquals(64 * 13, parameters.getAllValues().getFirst().length);
    assertEquals(13, parameters.getAllValues().getLast().length);
    assertEquals(0, parameters.getAllValues().getFirst()[1]);
    assertEquals(64, parameters.getAllValues().getLast()[1]);
    assertEquals("[\"inspect\",\"64\"]", parameters.getAllValues().getLast()[3]);
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void mismatchedRunIsRejectedBeforeReplacingAnyExistingConsent() {
    var jdbc = mock(JdbcTemplate.class);
    var repository = new JdbcOrchestrationAcceptance(jdbc, UnitOfWork.NONE);
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.replace("run", "requirements", List.of(command("other", 0))));
    verifyNoInteractions(jdbc);
  }
}
