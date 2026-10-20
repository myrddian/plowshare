package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.events.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SchedulePagingTest {
  final ScheduleStore schedules = mock(ScheduleStore.class);
  final ScheduleDefinitions definitions = mock(ScheduleDefinitions.class);
  final Asking asking = new Asking("session", "owner");
  final ScheduleListHandler list = new ScheduleListHandler(schedules, () -> definitions);

  @Test
  void defaultsAndExclusiveCursorReachBoundedOwnedReadWithoutLoadingDefinitions() {
    assertEquals(List.of(), list.handle(Map.of(), asking).payload());
    list.handle(Map.of("after", "daily", "limit", 3), asking);
    verify(schedules).page("owner", null, new SchedulePage(null, 50));
    verify(schedules).page("owner", null, new SchedulePage("daily", 3));
    verify(schedules, never()).list();
    verifyNoInteractions(definitions);
  }

  @Test
  void scopedRuntimeReadChecksViewerBeforeAndAfterTheRepositoryPage() {
    list.handle(Map.of("project", "application", "limit", 2), asking);
    var order = inOrder(definitions, schedules);
    order.verify(definitions).requireRead("owner", "application");
    order.verify(schedules).page("owner", "application", new SchedulePage(null, 2));
    order.verify(definitions).requireRead("owner", "application");
    doNothing()
        .doThrow(new CallerFault("revoked"))
        .when(definitions)
        .requireRead("owner", "application");
    assertThrows(CallerFault.class, () -> list.handle(Map.of("project", "application"), asking));
    verify(definitions, never()).list(anyString(), anyString());
  }

  @Test
  void invalidPageValuesAreRefusedBeforeAuthorityOrDatabaseReads() {
    var invalid = new ArrayList<Map<String, Object>>();
    for (Object value : List.of("", " daily", "daily ", "a\nb", "x".repeat(513), 7))
      invalid.add(Map.of("after", value));
    var nullCursor = new HashMap<String, Object>();
    nullCursor.put("after", null);
    invalid.add(nullCursor);
    var nullLimit = new HashMap<String, Object>();
    nullLimit.put("limit", null);
    invalid.add(nullLimit);
    invalid.add(Map.of("offset", 50));
    for (Object value : List.of(0, -1, 101, 1.5, "2", true)) invalid.add(Map.of("limit", value));
    for (var payload : invalid)
      assertThrows(CallerFault.class, () -> list.handle(payload, asking), payload.toString());
    verifyNoInteractions(schedules, definitions);
  }

  @Test
  void fileFramesShareValidationAndUseInternalNameCursor() {
    @SuppressWarnings("unchecked")
    ObjectProvider<ScheduleDefinitions> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(definitions);
    var files = new ScheduleFileFrames(provider).frames().get(FrameTypes.SCHEDULE_FILES);
    files.handle(
        Map.of("project", "application", "after", "scheduled-2-daily", "limit", 4), asking);
    verify(definitions).page("owner", "application", new SchedulePage("scheduled-2-daily", 4));
    assertThrows(CallerFault.class, () -> files.handle(Map.of("limit", 101), asking));
    verify(definitions, never()).list(anyString());
    verify(definitions, never()).list(anyString(), anyString());
  }
}
