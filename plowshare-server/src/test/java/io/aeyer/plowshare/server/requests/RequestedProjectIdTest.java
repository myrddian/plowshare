package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.ValidationException;
import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

/**
 * The twelve cells of {@link RequestedProjectId}'s own class javadoc table, pinned against a stub
 * {@link ProjectStore} — the whole reason moving the four {@code projectIdFor*} variants out of
 * {@code AgentController} is safe.
 *
 * <p>Nothing before this test pinned that the four variants differ on a malformed name, a database
 * outage and a project with no row; this is that pin, one test per cell, named for the behaviour
 * rather than for the method under test.
 *
 * <p>{@link ProjectStore} is {@code final} and stubbed with Mockito rather than subclassed — {@code
 * AgentControllerTest} already mocks it the same way — so only {@link ProjectStore#id(String)} is
 * ever stubbed here; nothing else on the class is touched.
 */
class RequestedProjectIdTest {

  private final ProjectStore projects = mock(ProjectStore.class);

  // --- lenient(ProjectStore, String) -----------------------------------

  @Test
  void a_lenient_lookup_treats_a_malformed_name_as_no_project_silently() {
    when(projects.id(" payments ")).thenThrow(new ValidationException("has whitespace"));

    assertNull(RequestedProjectId.lenient(projects, " payments "));
  }

  @Test
  void a_lenient_lookup_propagates_a_database_outage() {
    when(projects.id("payments"))
        .thenThrow(
            new ArchiveUnavailableException("look up a project's id", new RuntimeException()));

    assertThrows(
        ArchiveUnavailableException.class, () -> RequestedProjectId.lenient(projects, "payments"));
  }

  @Test
  void a_lenient_lookup_answers_null_for_a_project_with_no_row() {
    when(projects.id("payments")).thenReturn(null);

    assertNull(RequestedProjectId.lenient(projects, "payments"));
  }

  @Test
  void a_lenient_lookup_answers_null_for_a_blank_or_absent_project_without_asking_the_store() {
    assertNull(RequestedProjectId.lenient(projects, null));
    assertNull(RequestedProjectId.lenient(projects, ""));
    verify(projects, never()).id(anyString());
  }

  @Test
  void a_lenient_lookup_resolves_an_ordinary_name() {
    when(projects.id("payments")).thenReturn(9L);

    assertEquals(9L, RequestedProjectId.lenient(projects, "payments"));
  }

  // --- of(ProjectStore, Home) -------------------------------------------

  @Test
  void a_home_lookup_delegates_a_malformed_name_to_the_lenient_rule() {
    // Home does not strip -- ProjectStore.named() is what refuses edge
    // whitespace -- so a Home can hold exactly the malformed name lenient
    // would be asked to swallow.
    when(projects.id(" payments ")).thenThrow(new ValidationException("has whitespace"));

    assertNull(RequestedProjectId.of(projects, Home.of(" payments ")));
  }

  @Test
  void a_home_lookup_delegates_a_database_outage_to_the_lenient_rule() {
    when(projects.id("payments"))
        .thenThrow(
            new ArchiveUnavailableException("look up a project's id", new RuntimeException()));

    assertThrows(
        ArchiveUnavailableException.class,
        () -> RequestedProjectId.of(projects, Home.of("payments")));
  }

  @Test
  void a_home_lookup_delegates_a_project_with_no_row_to_the_lenient_rule() {
    when(projects.id("payments")).thenReturn(null);

    assertNull(RequestedProjectId.of(projects, Home.of("payments")));
  }

  @Test
  void a_home_lookup_never_asks_the_store_about_the_global_tier() {
    assertNull(RequestedProjectId.of(projects, Home.global()));
    verify(projects, never()).id(anyString());
  }

  @Test
  void a_home_lookup_resolves_an_ordinary_project() {
    when(projects.id("payments")).thenReturn(9L);

    assertEquals(9L, RequestedProjectId.of(projects, Home.of("payments")));
  }

  // --- forListing(ProjectStore, String) ----------------------------------

  @Test
  void a_listing_refuses_a_malformed_project_name() {
    when(projects.id(" payments ")).thenThrow(new ValidationException("has whitespace"));

    assertThrows(CallerFault.class, () -> RequestedProjectId.forListing(projects, " payments "));
  }

  @Test
  void a_listing_degrades_to_the_boot_set_when_the_database_is_gone() {
    when(projects.id("payments"))
        .thenThrow(
            new ArchiveUnavailableException("look up a project's id", new RuntimeException()));

    assertNull(RequestedProjectId.forListing(projects, "payments"));
  }

  @Test
  void a_listing_answers_null_for_a_project_with_no_row() {
    when(projects.id("payments")).thenReturn(null);

    assertNull(RequestedProjectId.forListing(projects, "payments"));
  }

  @Test
  void a_listing_resolves_an_ordinary_project() {
    when(projects.id("payments")).thenReturn(9L);

    assertEquals(9L, RequestedProjectId.forListing(projects, "payments"));
  }

  // --- forWrite(ProjectStore, String) ------------------------------------

  @Test
  void a_write_refuses_a_malformed_project_name() {
    when(projects.id(" payments ")).thenThrow(new ValidationException("has whitespace"));

    assertThrows(CallerFault.class, () -> RequestedProjectId.forWrite(projects, " payments "));
  }

  @Test
  void a_write_propagates_a_database_outage_rather_than_degrading() {
    when(projects.id("payments"))
        .thenThrow(
            new ArchiveUnavailableException("look up a project's id", new RuntimeException()));

    assertThrows(
        ArchiveUnavailableException.class, () -> RequestedProjectId.forWrite(projects, "payments"));
  }

  @Test
  void a_write_refuses_a_project_with_no_row_rather_than_silently_using_global() {
    when(projects.id("payments")).thenReturn(null);

    CallerFault refused =
        assertThrows(CallerFault.class, () -> RequestedProjectId.forWrite(projects, "payments"));
    assertTrue(refused.getMessage().contains("no project called 'payments' exists"));
  }

  @Test
  void a_write_resolves_an_ordinary_project() {
    when(projects.id("payments")).thenReturn(9L);

    assertEquals(9L, RequestedProjectId.forWrite(projects, "payments"));
  }
}
