package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.FileStores;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationPlacementFramesTest {
  @TempDir Path directory;
  final Asking asking = new Asking("session", "owner");

  @Test
  void validates_references_before_provisioning_and_never_reinterprets_legacy_creation() {
    var projects = mock(ServerProjects.class);
    var stores = mock(FileStores.class);
    var frames = new ServerProjectFrames(projects, directory.toString(), stores).frames();
    var root = Map.of("store", "applications", "path", "chatbot");
    for (Map<String, Object> body :
        List.<Map<String, Object>>of(
            Map.of("name", "chatbot", "applicationRoot", root),
            Map.of(
                "name", "chatbot", "applicationRoot", root, "writableAreas", List.of(root, root)),
            Map.of(
                "name",
                "chatbot",
                "applicationRoot",
                Map.of("store", "applications", "path", "../escape"),
                "writableAreas",
                List.of()),
            Map.of(
                "name",
                "chatbot",
                "workspace",
                directory.toString(),
                "applicationRoot",
                root,
                "writableAreas",
                List.of())))
      assertThrows(
          CallerFault.class, () -> frames.get(FrameTypes.APPLICATION_CREATE).handle(body, asking));
    assertThrows(
        CallerFault.class,
        () ->
            frames
                .get(FrameTypes.PROJECT_CREATE)
                .handle(
                    Map.of("name", "chatbot", "applicationRoot", root, "writableAreas", List.of()),
                    asking));
    verifyNoInteractions(projects, stores);
  }

  @Test
  void managed_creation_provisions_only_its_source_and_disjoint_requires_an_existing_manifest()
      throws Exception {
    var projects = mock(ServerProjects.class);
    Path root = directory.resolve("chatbot"),
        outputs = Files.createDirectory(directory.resolve("outputs"));
    var source = new FileStoreReference("applications", "chatbot");
    var area = new FileStoreReference("outputs", "");
    var placement = new ApplicationPlacement(source, List.of(area));
    var stores = mock(FileStores.class);
    when(stores.resolve(placement)).thenReturn(new FileStores.Placement(root, List.of(outputs)));
    when(projects.createServer(eq("chatbot"), anyString(), eq(List.of()), eq("owner"), any()))
        .thenAnswer(
            call -> {
              Supplier<ServerProjects.ServerWorkspace> provision = call.getArgument(4);
              var supplied = provision.get();
              assertEquals(placement, supplied.placement());
              return new ProjectRecord(
                  "chatbot",
                  root,
                  List.of(),
                  List.of(),
                  call.getArgument(1),
                  List.of(),
                  placement,
                  List.of(outputs));
            });
    var create =
        new ServerProjectFrames(projects, directory.toString(), stores)
            .frames()
            .get(FrameTypes.APPLICATION_CREATE);
    var payload =
        Map.<String, Object>of(
            "name",
            "chatbot",
            "applicationRoot",
            Map.of("store", "applications", "path", "chatbot"),
            "writableAreas",
            List.of(Map.of("store", "outputs", "path", "")));
    var disjoint = new HashMap<>(payload);
    disjoint.put("type", "DISJOINT");
    assertThrows(CallerFault.class, () -> create.handle(disjoint, asking));
    assertFalse(Files.exists(root));
    create.handle(payload, asking);
    String manifest = Files.readString(root.resolve("plowshare.json"));
    assertTrue(manifest.contains("MANAGER"));
    assertTrue(manifest.contains("owner"));
    try (var children = Files.list(outputs)) {
      assertEquals(0, children.count());
    }
    create.handle(disjoint, asking);
    assertEquals(manifest, Files.readString(root.resolve("plowshare.json")));
  }

  @Test
  void explicit_adoption_rejects_an_external_before_changing_admitted_areas() {
    var projects = mock(ServerProjects.class);
    var applications = mock(ApplicationPolicy.class);
    when(applications.read("external"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of()));
    var frame =
        new ApplicationStorageFrames(projects, applications)
            .frames()
            .get(FrameTypes.APPLICATION_STORAGE_SET);
    assertThrows(
        CallerFault.class,
        () ->
            frame.handle(
                Map.of(
                    "project",
                    "external",
                    "applicationRoot",
                    Map.of("store", "applications", "path", "external"),
                    "writableAreas",
                    List.of()),
                asking));
    verifyNoInteractions(projects);
  }
}
