package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.data.DataLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NamedSwarmTest {
  @TempDir Path root;

  private SwarmSelection selection(String name, String member) {
    return new SwarmSelection(name, "a".repeat(64), "Description", List.of(member), 20);
  }

  @Test
  void multiple_types_require_selection_and_unknown_or_traversing_names_are_refused() {
    var privacy = selection("privacy-review", "researcher");
    var incident = selection("incident-response", "critic");
    SwarmCatalog catalog =
        project ->
            List.of(
                new SwarmDefinitions.SwarmDefinition(
                    privacy.name(),
                    privacy.members(),
                    20,
                    Map.of(),
                    "swarm/privacy-review.md",
                    privacy),
                new SwarmDefinitions.SwarmDefinition(
                    incident.name(),
                    incident.members(),
                    20,
                    Map.of(),
                    "swarm/incident-response.json",
                    incident));
    assertThrows(Board.Refused.class, () -> catalog.select("app", null));
    assertThrows(Board.Refused.class, () -> catalog.select("app", "unknown"));
    assertThrows(Board.Refused.class, () -> catalog.select("app", "../privacy-review"));
    assertEquals(privacy, catalog.select("app", "privacy-review").selection());
  }

  @Test
  void files_are_bounded_sorted_and_duplicate_formats_or_links_refuse_the_catalog()
      throws Exception {
    Path directory = Files.createDirectory(root.resolve("swarm"));
    Files.writeString(
        directory.resolve("privacy-review.md"), "---\nmembers: [researcher]\n---\nPrivacy");
    Files.writeString(directory.resolve("incident-response.json"), "{\"members\":[\"critic\"]}");
    var sources =
        ServerSwarmSources.directory(directory, FileAccess.of(List.of(root), List.of()), "swarm/");
    assertEquals(
        List.of("incident-response", "privacy-review"),
        sources.stream().map(SwarmSources.Source::name).toList());
    Files.writeString(directory.resolve("privacy-review.json"), "{}");
    assertThrows(
        Board.Refused.class, () -> ServerSwarmSources.directory(directory, null, "swarm/"));
    Files.delete(directory.resolve("privacy-review.json"));
    Files.createSymbolicLink(
        directory.resolve("linked.md"), directory.resolve("privacy-review.md"));
    assertThrows(
        Board.Refused.class, () -> ServerSwarmSources.directory(directory, null, "swarm/"));
    Files.delete(directory.resolve("linked.md"));
    Files.write(directory.resolve("invalid.md"), new byte[] {(byte) 0xff});
    assertThrows(
        Board.Refused.class, () -> ServerSwarmSources.directory(directory, null, "swarm/"));
    Files.delete(directory.resolve("invalid.md"));
    Files.writeString(directory.resolve("huge.md"), "x".repeat(65537));
    assertThrows(
        Board.Refused.class, () -> ServerSwarmSources.directory(directory, null, "swarm/"));
  }

  @Test
  void applications_read_the_root_swarm_and_honor_manifest_and_workspace_fences() throws Exception {
    var projects = mock(ProjectWorkspaces.class);
    var policy = mock(ApplicationPolicy.class);
    var row = new ProjectRecord("app", root, List.of(), List.of(), "DISJOINT", List.of());
    when(projects.find("app")).thenReturn(Optional.of(row));
    when(projects.effectiveExclusions(row)).thenReturn(List.of());
    when(policy.read("app"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.APPLICATION, Map.of()));
    var sources = new ServerSwarmSources(projects, policy, new DataLayout(root.resolve("data")));
    assertTrue(
        sources.read("app").isEmpty(),
        "A missing Application directory must not pick a global default");
    Path directory = Files.createDirectory(root.resolve("swarm"));
    Files.writeString(directory.resolve("privacy.md"), "---\nmembers: [critic]\n---\n");
    assertEquals("swarm/privacy.md", sources.read("app").getFirst().origin());
    when(projects.effectiveExclusions(row)).thenReturn(List.of(directory));
    assertThrows(Board.Refused.class, () -> sources.read("app"));
    when(policy.read("app"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    assertThrows(Board.Refused.class, () -> sources.read("app"));
  }

  @Test
  void later_posts_validate_mentions_against_the_snapshot_without_reading_current_files() {
    var selection = selection("privacy", "researcher");
    var topic =
        new BoardTopic(
            "topic",
            "app",
            null,
            "topic",
            0,
            "t",
            "l",
            "enzo",
            "person",
            "enzo",
            null,
            "open",
            null,
            20,
            0,
            2,
            null,
            Instant.EPOCH,
            null,
            selection);
    var store = mock(BoardStore.class);
    var catalog = mock(SwarmCatalog.class);
    when(store.topic("topic")).thenReturn(Optional.of(topic));
    var board =
        new Board(
            store,
            catalog,
            mock(io.aeyer.plowshare.server.archive.ConversationStore.class),
            mock(io.aeyer.plowshare.server.events.FiringStore.class),
            ignored -> {},
            UnitOfWork.NONE,
            () -> 10,
            () -> Instant.EPOCH);
    assertThrows(
        Board.Refused.class,
        () ->
            board.post(
                new Board.Post(
                    "topic",
                    "person",
                    "enzo",
                    null,
                    null,
                    "post",
                    null,
                    "hello",
                    null,
                    List.of("critic"),
                    false)));
    verifyNoInteractions(catalog);
    verify(store, never()).post(any());
  }

  @Test
  void personal_named_swarms_use_the_private_union_and_refuse_linked_ancestors() throws Exception {
    var data = new DataLayout(root);
    data.usePersonalProjects(id -> id == 7L);
    Path directory = data.swarmFor(7L).getParent();
    assertEquals(data.unionFor(7).resolve("tree/Resources/swarm"), directory);
    Path outside = Files.createDirectories(root.resolve("outside/swarm"));
    Files.writeString(outside.resolve("privacy.md"), "---\nmembers: [critic]\n---\n");
    Files.createDirectories(data.unionFor(7).resolve("tree"));
    Files.createSymbolicLink(data.unionFor(7).resolve("tree/Resources"), root.resolve("outside"));
    assertThrows(
        Board.Refused.class,
        () -> ServerSwarmSources.directory(root, directory, null, "personal:swarm/"));
  }
}
