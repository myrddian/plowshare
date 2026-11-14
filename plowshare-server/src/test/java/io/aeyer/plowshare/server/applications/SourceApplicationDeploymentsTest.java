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
  void malformed_service_is_a_caller_refusal_before_staging_or_reservation() {
    for (String declaration :
        List.of("null", "{\"handle\":\"worker\",\"role\":\"ADMIN\"}", "{\"handle\":\"worker\"}")) {
      var request =
          new Deploy(
              "app",
              UUID.randomUUID(),
              null,
              destination,
              List.of(),
              List.of(
                  new File(
                      "plowshare.json",
                      "{\"version\":1,\"name\":\"app\",\"service\":" + declaration + "}")));
      assertThrows(CallerFault.class, () -> service.deploy("owner", request));
    }
    assertFalse(Files.exists(directory.resolve("app")));
    verifyNoInteractions(validator);
    verify(store, never()).commit(any(), any());
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

  // ADR 0068: a project Manager's save on a MANAGED Application lands as a schedule-only revision.

  final UUID active = UUID.randomUUID();
  final FileStoreReference outputs = new FileStoreReference("applications", "outputs");

  /** Formatting a re-resolved manifest would never reproduce: proves the bytes are copied. */
  final String manifest =
      "{ \"version\":1,\"name\":\"app\" ,\n"
          + "  \"service\":{\"handle\":\"worker\",\"role\":\"CONTRIBUTOR\"},"
          + "\"executionAccount\":\"@service/00000000-0000-0000-0000-000000000001\"}";

  ProjectNames names;
  ApplicationIdentities identities;

  /** An active revision as a prior deployment retained it, with the store reporting it as head. */
  Path activeRevision() throws Exception {
    Path root = Files.createDirectories(directory.resolve("app/revisions/" + active));
    Files.writeString(root.resolve("plowshare.json"), manifest);
    Files.writeString(root.resolve("README.md"), "caf\u00e9 \u2014 \uD83D\uDE80\r\nno newline");
    Files.createDirectories(root.resolve("schedules"));
    Files.writeString(root.resolve("schedules/daily.json"), "{\"old\":true}");
    Files.createDirectories(root.resolve("agents/deep"));
    Files.writeString(root.resolve("agents/deep/helper.md"), "# helper\n");
    var retained =
        List.of(
            new File("plowshare.json", manifest),
            new File("README.md", Files.readString(root.resolve("README.md"))),
            new File("schedules/daily.json", "{\"old\":true}"),
            new File("agents/deep/helper.md", "# helper\n"));
    var release = new Release(active, SourceApplicationDeployments.digest(retained), 4);
    when(store.status("app")).thenReturn(new Status("app", active, List.of(release)));
    when(store.release("app", active))
        .thenReturn(
            new ApplicationDeploymentStore.Retained(
                release,
                destination,
                new ApplicationPlacement(
                    new FileStoreReference("applications", "app/revisions/" + active),
                    List.of(outputs))));
    names = mock(ProjectNames.class);
    when(names.nameForId(7L)).thenReturn(Optional.of("app"));
    identities = mock(ApplicationIdentities.class);
    when(members.mayManage("app", "manager")).thenReturn(true);
    service =
        new SourceApplicationDeployments(
            store, members, projects, stores, validator, identities, names);
    return root;
  }

  ApplicationDeploymentStore.Mutation committed() {
    var mutation = org.mockito.ArgumentCaptor.forClass(ApplicationDeploymentStore.Mutation.class);
    verify(store).commit(eq("manager"), mutation.capture());
    return mutation.getValue();
  }

  List<Path> revisions() throws Exception {
    try (var listed = Files.list(directory.resolve("app/revisions"))) {
      return listed.sorted().toList();
    }
  }

  @Test
  void scheduleRevisionCopiesEveryOtherFileByteForByte() throws Exception {
    Path old = activeRevision();
    String revision = service.revise("manager", 7L, "daily", "{\"new\":true}", true);
    var mutation = committed();
    assertEquals(revision, mutation.release().release().revision().toString());
    Path fresh = directory.resolve("app/revisions/" + revision);
    assertEquals(fresh, mutation.verifiedRoot());
    for (String path : List.of("plowshare.json", "README.md", "agents/deep/helper.md"))
      assertArrayEquals(
          Files.readAllBytes(old.resolve(path)), Files.readAllBytes(fresh.resolve(path)), path);
    assertEquals("{\"new\":true}", Files.readString(fresh.resolve("schedules/daily.json")));
    // The retained revision is never touched.
    assertEquals("{\"old\":true}", Files.readString(old.resolve("schedules/daily.json")));
    try (var walked = Files.walk(fresh)) {
      assertEquals(4, walked.filter(Files::isRegularFile).count());
    }
    assertTrue(mutation.scheduleOnly());
    assertTrue(mutation.install());
    assertEquals(active, mutation.expectedRevision());
    assertEquals(4, mutation.release().release().fileCount());
    verify(validator).validate("app", fresh);
  }

  @Test
  void scheduleRevisionCopiesManifestWithoutResolving() throws Exception {
    activeRevision();
    String revision = service.revise("manager", 7L, "weekly", "{}", false);
    assertEquals(
        manifest,
        Files.readString(directory.resolve("app/revisions/" + revision + "/plowshare.json")));
    // No reservation, activation or status read: the owned identity is carried, not re-resolved.
    verifyNoInteractions(identities);
    var identity = committed().identity();
    assertEquals("@service/00000000-0000-0000-0000-000000000001", identity.principal());
  }

  @Test
  void scheduleRevisionAddsNewScheduleFile() throws Exception {
    activeRevision();
    String revision = service.revise("manager", 7L, "weekly", "{\"w\":1}", false);
    Path fresh = directory.resolve("app/revisions/" + revision);
    assertEquals("{\"w\":1}", Files.readString(fresh.resolve("schedules/weekly.json")));
    assertEquals("{\"old\":true}", Files.readString(fresh.resolve("schedules/daily.json")));
    var release = committed().release().release();
    assertEquals(5, release.fileCount());
    assertEquals(
        SourceApplicationDeployments.digest(
            List.of(
                new File("plowshare.json", manifest),
                new File("README.md", Files.readString(fresh.resolve("README.md"))),
                new File("schedules/daily.json", "{\"old\":true}"),
                new File("schedules/weekly.json", "{\"w\":1}"),
                new File("agents/deep/helper.md", "# helper\n"))),
        release.digest());
  }

  @Test
  void scheduleRevisionRefusesExistingWithoutOverwrite() throws Exception {
    activeRevision();
    var refusal =
        assertThrows(CallerFault.class, () -> service.revise("manager", 7L, "daily", "{}", false));
    assertEquals("A schedule named daily already exists", refusal.getMessage());
    assertEquals(1, revisions().size());
    verify(store, never()).commit(any(), any());
    // Names follow the schedule identity rule before anything is read or staged.
    assertThrows(CallerFault.class, () -> service.revise("manager", 7L, "../escape", "{}", true));
    assertThrows(
        CallerFault.class, () -> service.revise("manager", 7L, "big", "x".repeat(65537), true));
    assertEquals(1, revisions().size());
    verify(store, never()).commit(any(), any());
  }

  @Test
  void scheduleRevisionKeepsDestinationAndWritableAreas() throws Exception {
    activeRevision();
    String revision = service.revise("manager", 7L, "daily", "{}", true);
    var retained = committed().release();
    assertEquals(destination, retained.destination());
    assertEquals(List.of(outputs), retained.placement().writableAreas());
    assertEquals(
        new FileStoreReference("applications", "app/revisions/" + revision),
        retained.placement().applicationRoot());
    // The Manager needs no FileStore authority and no server administration.
    verify(stores, never()).permits(any(), any(), any());
    verify(members, never()).isServerAdmin(any());
  }

  @Test
  void failedValidationRemovesStagedRevision() throws Exception {
    activeRevision();
    doThrow(new CallerFault("Invalid resources")).when(validator).validate(eq("app"), any());
    assertThrows(CallerFault.class, () -> service.revise("manager", 7L, "daily", "{}", true));
    assertEquals(1, revisions().size());
    verify(store, never()).commit(any(), any());
    // A definite refusal by the head fence rolls the transaction back; its staging goes too.
    doNothing().when(validator).validate(any(), any());
    doThrow(
            new CallerFault(
                "Application revision changed; read deployment status before submitting a new request"))
        .when(store)
        .commit(any(), any());
    assertThrows(CallerFault.class, () -> service.revise("manager", 7L, "daily", "{}", true));
    assertEquals(1, revisions().size());
  }

  @Test
  void uncertainCommitRetainsStagedRevision() throws Exception {
    activeRevision();
    // The outcome is unknown, so the staged directory may be the committed head: keep it.
    doThrow(new IllegalStateException("connection lost")).when(store).commit(any(), any());
    assertThrows(
        IllegalStateException.class, () -> service.revise("manager", 7L, "daily", "{}", true));
    assertEquals(2, revisions().size());
  }

  @Test
  void contributorRefused() throws Exception {
    activeRevision();
    when(members.mayWork("app", "contributor")).thenReturn(true);
    when(members.mayManage("app", "@service/worker")).thenReturn(true);
    for (String caller : List.of("contributor", "stranger", "@service/worker"))
      assertThrows(
          CallerFault.class, () -> service.revise(caller, 7L, "daily", "{}", true), caller);
    assertEquals(1, revisions().size());
    verify(store, never()).commit(any(), any());
    verify(store, never()).status(any());
    verifyNoInteractions(validator);
  }
}
