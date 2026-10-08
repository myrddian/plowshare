package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationDeployment.*;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.FileStores;
import io.aeyer.plowshare.server.ws.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SourceApplicationDeploymentsTest {
  @TempDir Path directory;
  ApplicationDeploymentStore store;
  ProjectMembers members;
  ProjectWorkspaces projects;
  FileStores stores;
  ApplicationPackageValidator validator;
  SourceApplicationDeployments service;
  final FileStoreReference destination = new FileStoreReference("applications", "app");

  List<File> files(String text) {
    return List.of(
        new File("plowshare.json", "{\"version\":1,\"name\":\"app\"}"),
        new File("README.md", text));
  }

  Deploy deploy(UUID id) {
    return new Deploy("app", id, null, destination, List.of(), files("source"));
  }

  @BeforeEach
  void setup() {
    store = mock(ApplicationDeploymentStore.class);
    members = mock(ProjectMembers.class);
    projects = mock(ProjectWorkspaces.class);
    stores = mock(FileStores.class);
    validator = mock(ApplicationPackageValidator.class);
    when(members.isServerAdmin("owner")).thenReturn(true);
    when(stores.permits(any(), eq("owner"), eq(ProjectRole.MANAGER))).thenReturn(true);
    when(stores.resolve(any()))
        .thenAnswer(
            call -> {
              ApplicationPlacement placement = call.getArgument(0);
              return new FileStores.Placement(
                  directory.resolve(placement.applicationRoot().path()),
                  placement.writableAreas().stream()
                      .map(area -> directory.resolve(area.path()))
                      .toList());
            });
    when(projects.effectiveExclusions(any())).thenReturn(List.of());
    when(store.commit(any(), any()))
        .thenAnswer(
            call -> {
              ApplicationDeploymentStore.Mutation mutation = call.getArgument(1);
              return new Receipt(
                  mutation.requestId(), mutation.project(), mutation.release().release());
            });
    service = new SourceApplicationDeployments(store, members, projects, stores, validator);
  }

  @Test
  void stages_complete_source_before_activation_and_preserves_a_lost_reply_receipt()
      throws Exception {
    var request = deploy(UUID.randomUUID());
    var receipt = service.deploy("owner", request);
    Path source = directory.resolve("app/revisions/" + receipt.release().revision());
    assertEquals("source", Files.readString(source.resolve("README.md")));
    verify(validator).validate("app", source);
    when(store.receipt(eq("owner"), eq("app"), eq(request.requestId()), anyString()))
        .thenReturn(Optional.of(receipt));
    Files.delete(source.resolve("plowshare.json"));
    assertEquals(receipt, service.deploy("owner", request));
    verify(store, times(1)).commit(any(), any());
    verify(validator, times(1)).validate(any(), any());
  }

  @Test
  void receipt_fingerprints_distinguish_area_lists_with_the_same_display_text() {
    UUID id = UUID.randomUUID();
    var one =
        List.of(new FileStoreReference("outputs", "x], FileStoreReference[store=reports, path=y"));
    var two =
        List.of(new FileStoreReference("outputs", "x"), new FileStoreReference("reports", "y"));
    assertEquals(one.toString(), two.toString());
    service.deploy("owner", new Deploy("app", id, null, destination, one, files("source")));
    service.deploy("owner", new Deploy("app", id, null, destination, two, files("source")));
    var fingerprints = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(store, times(2)).receipt(eq("owner"), eq("app"), eq(id), fingerprints.capture());
    assertNotEquals(fingerprints.getAllValues().getFirst(), fingerprints.getAllValues().getLast());
  }

  @Test
  void refusal_does_not_activate_partial_source() {
    doThrow(new CallerFault("Invalid resources")).when(validator).validate(any(), any());
    assertThrows(CallerFault.class, () -> service.deploy("owner", deploy(UUID.randomUUID())));
    verify(store, never()).commit(any(), any());
  }

  @Test
  void authorization_and_exclusions_refuse_before_writing() {
    assertThrows(CallerFault.class, () -> service.deploy("other", deploy(UUID.randomUUID())));
    verifyNoInteractions(store, validator, stores);
    when(projects.effectiveExclusions(any())).thenReturn(List.of(directory));
    assertThrows(CallerFault.class, () -> service.deploy("owner", deploy(UUID.randomUUID())));
    assertFalse(Files.exists(directory.resolve("app")));
    verify(store, never()).commit(any(), any());
  }

  @Test
  void rollback_checks_retained_content_instead_of_trusting_revision_identity() throws Exception {
    var receipt = service.deploy("owner", deploy(UUID.randomUUID()));
    var placement =
        new ApplicationPlacement(
            new FileStoreReference("applications", "app/revisions/" + receipt.release().revision()),
            List.of());
    when(store.release("app", receipt.release().revision()))
        .thenReturn(
            new ApplicationDeploymentStore.Retained(receipt.release(), destination, placement));
    Files.writeString(
        directory.resolve(placement.applicationRoot().path()).resolve("README.md"), "changed");
    assertThrows(
        CallerFault.class,
        () ->
            service.activate(
                "owner",
                new Activate(
                    "app",
                    UUID.randomUUID(),
                    receipt.release().revision(),
                    receipt.release().revision())));
    verify(store, times(1)).commit(any(), any());
  }

  @Test
  void rejects_source_overlap_with_runtime_writable_areas() {
    var request =
        new Deploy(
            "app", UUID.randomUUID(), null, destination, List.of(destination), files("source"));
    assertThrows(CallerFault.class, () -> service.deploy("owner", request));
    assertFalse(Files.exists(directory.resolve("app")));
  }

  @Test
  void malformed_wire_packages_never_reach_application_logic() throws Exception {
    var applications = mock(ApplicationDeployments.class);
    var handler =
        new ApplicationDeploymentFrames(applications).frames().get(FrameTypes.APPLICATION_DEPLOY);
    Map<String, Object> body = new HashMap<>();
    body.put("project", "app");
    body.put("requestId", UUID.randomUUID().toString());
    body.put("expectedRevision", null);
    body.put("destination", Map.of("store", "applications", "path", "app"));
    body.put("writableAreas", List.of());
    for (Object file :
        List.of(
            Map.of("path", "../escape", "text", "x"),
            Map.of("path", "plowshare.json", "text", 1),
            Map.of("path", "plowshare.json", "text", "x", "extra", "x"))) {
      body.put("files", List.of(file));
      assertThrows(CallerFault.class, () -> handler.handle(body, new Asking("session", "owner")));
    }
    verifyNoInteractions(applications);
  }
}
