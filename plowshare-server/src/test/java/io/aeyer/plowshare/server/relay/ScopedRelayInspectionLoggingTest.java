package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ScopedRelayInspectionLoggingTest {
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final RelayLogRepository logs = mock(RelayLogRepository.class);
  private final RelayInspection inspection = new ScopedRelayInspection(members, projects, logs);
  private static final String ACCOUNT = "private-account";

  @Test
  void authorizedFailuresLogEffectiveSelectorsAtWarnAndPreserveTheOriginalException() {
    when(members.mayUse("project", ACCOUNT)).thenReturn(true);
    when(projects.id("project")).thenReturn(7L);
    when(members.isServerAdmin(ACCOUNT)).thenReturn(true);
    try (var captured = new CapturedWarnings()) {
      for (var query :
          List.of(
              new RelayLog.Query("project", false, "notices.status", null, null),
              new RelayLog.Query(null, true, "notices.status", "42", 3))) {
        for (var failure :
            List.of(
                new NotFoundFault("private-failure-detail"),
                new CallerFault("private-failure-detail"),
                new IllegalStateException("private-failure-detail"))) {
          when(logs.read(any(), anyLong(), anyInt(), eq(ACCOUNT))).thenThrow(failure);
          assertSame(
              failure, assertThrows(RuntimeException.class, () -> inspection.log(ACCOUNT, query)));
          var event = captured.events.list.getLast();
          String message = event.getFormattedMessage();
          assertEquals(Level.WARN, event.getLevel());
          assertTrue(message.contains("operation=relay.log"));
          assertTrue(message.contains(query.system() ? "scope=system" : "scope=project:7"));
          assertTrue(message.contains("project=" + query.project()));
          assertTrue(message.contains("topic=notices.status"));
          assertTrue(message.contains(query.system() ? "after=42 limit=3" : "after=0 limit=100"));
          assertTrue(message.contains("failure=" + failure.getClass().getSimpleName()));
          assertFalse(message.contains(ACCOUNT));
          assertFalse(message.contains("private-failure-detail"));
          assertNull(event.getThrowableProxy());
        }
      }
      assertEquals(6, captured.events.list.size());
    }
  }

  @Test
  void authorizationRefusalsAndSuccessfulReadsDoNotEmitSelectionWarnings() {
    var query = new RelayLog.Query("project", false, "notices.status", null, null);
    try (var captured = new CapturedWarnings()) {
      assertThrows(CallerFault.class, () -> inspection.log(ACCOUNT, query));
      verifyNoInteractions(logs);
      when(members.mayUse("project", ACCOUNT)).thenReturn(true);
      when(projects.id("project")).thenReturn(7L);
      var key = new Relay.TopicKey(7, query.topic());
      var topic = new Relay.Topic(key, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault(), 0, 0);
      when(logs.read(key, 0, 100, ACCOUNT))
          .thenReturn(new RelayLogRepository.Snapshot(topic, 0, List.of(), List.of(), List.of()));
      inspection.log(ACCOUNT, query);
      assertTrue(captured.events.list.isEmpty());
    }
  }

  private static final class CapturedWarnings implements AutoCloseable {
    final Logger logger = (Logger) LoggerFactory.getLogger(ScopedRelayInspection.class);
    final Level previous = logger.getLevel();
    final ListAppender<ILoggingEvent> events = new ListAppender<>();

    CapturedWarnings() {
      logger.setLevel(Level.WARN);
      events.start();
      logger.addAppender(events);
    }

    @Override
    public void close() {
      logger.detachAppender(events);
      events.stop();
      logger.setLevel(previous);
    }
  }
}
