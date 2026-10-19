package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/** Explicit contexts cannot degrade to another definition tier, on reads or writes. */
class RequestedProjectIdTest {
  final ProjectStore projects = mock(ProjectStore.class);
  final List<BiFunction<ProjectStore, String, Long>> lookups =
      List.of(
          RequestedProjectId::lenient,
          RequestedProjectId::forListing,
          RequestedProjectId::forWrite);

  @Test
  void onlyOmissionSelectsTheExistingDefaultCatalogue() {
    for (var lookup : lookups) {
      assertNull(lookup.apply(projects, null));
      for (String invalid : List.of("", " ", " payments "))
        assertThrows(CallerFault.class, () -> lookup.apply(projects, invalid));
    }
    verifyNoInteractions(projects);
  }

  @Test
  void missingProjectAndAccountUuidTextCannotSelectRootOrAnAccountScope() {
    for (String name : List.of("missing", UUID.randomUUID().toString())) {
      for (var lookup : lookups)
        assertThrows(CallerFault.class, () -> lookup.apply(projects, name));
      assertThrows(CallerFault.class, () -> RequestedProjectId.of(projects, Home.of(name)));
    }
    verify(projects, times(4)).id("missing");
  }

  @Test
  void outagesPropagateAndAnOrdinaryProjectNamedGlobalStaysAProject() {
    var outage = new ArchiveUnavailableException("lookup", new IllegalStateException());
    when(projects.id("offline")).thenThrow(outage);
    when(projects.id("global")).thenReturn(9L);
    for (var lookup : lookups) {
      assertSame(
          outage,
          assertThrows(ArchiveUnavailableException.class, () -> lookup.apply(projects, "offline")));
      assertEquals(9L, lookup.apply(projects, "global"));
    }
    assertEquals(9L, RequestedProjectId.of(projects, Home.of("global")));
    assertNull(RequestedProjectId.of(projects, Home.global()));
  }
}
