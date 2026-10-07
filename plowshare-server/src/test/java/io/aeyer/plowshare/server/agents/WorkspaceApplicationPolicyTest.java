package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ApplicationRegistrations;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceApplicationPolicyTest {
  @TempDir Path root;
  ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  ApplicationRegistrations registrations = mock(ApplicationRegistrations.class);
  WorkspaceApplicationPolicy policy = new WorkspaceApplicationPolicy(projects, registrations);

  @BeforeEach
  void source() {
    var project = new ProjectRecord("chatbot", root, List.of(), List.of(), "DISJOINT", List.of());
    when(projects.find("chatbot")).thenReturn(Optional.of(project));
    when(projects.effectiveExclusions(project)).thenReturn(List.of());
  }

  @Test
  void legacy_markers_are_external_but_root_json_wins_and_is_closed_by_default() throws Exception {
    Files.createDirectory(root.resolve(".plowshare"));
    Files.writeString(root.resolve(".plowshare/project"), "chatbot");
    assertEquals(ApplicationPolicy.Kind.EXTERNAL, policy.read("chatbot").kind());
    Files.writeString(root.resolve("plowshare.json"), "{\"version\":1,\"name\":\"chatbot\"}");
    var application = policy.read("chatbot");
    assertEquals(ApplicationPolicy.Kind.APPLICATION, application.kind());
    assertTrue(application.accounts().isEmpty());
    verify(registrations).require("chatbot");
    when(registrations.required("chatbot")).thenReturn(true);
    Files.delete(root.resolve("plowshare.json"));
    assertEquals(ApplicationPolicy.Kind.INVALID, policy.read("chatbot").kind());
    when(projects.find("chatbot")).thenReturn(Optional.empty());
    assertEquals(ApplicationPolicy.Kind.INVALID, policy.read("chatbot").kind());
  }

  @Test
  void managed_sources_require_application_identity_even_before_manifest_adoption()
      throws Exception {
    var managed = new ProjectRecord("chatbot", root, List.of(), List.of(), "MANAGED", List.of("."));
    when(projects.find("chatbot")).thenReturn(Optional.of(managed));
    when(projects.effectiveExclusions(managed)).thenReturn(List.of());
    assertEquals(ApplicationPolicy.Kind.INVALID, policy.read("chatbot").kind());
    verify(registrations).require("chatbot");
    Files.writeString(root.resolve("plowshare.json"), "{\"version\":1,\"name\":\"chatbot\"}");
    assertEquals(ApplicationPolicy.Kind.APPLICATION, policy.read("chatbot").kind());
    assertTrue(policy.read("chatbot").accounts().isEmpty());
  }

  @Test
  void malformed_nested_policy_never_uses_legacy_access() throws Exception {
    for (String fields :
        List.of(
            "\"access\":null",
            "\"access\":{}",
            "\"access\":{\"accounts\":null}",
            "\"access\":{\"accounts\":[{\"handle\":\"alice\",\"role\":\"OWNER\"}]}",
            "\"access\":{\"accounts\":[{\"handle\":7,\"role\":\"VIEWER\"}]}",
            "\"access\":{\"accounts\":[{\"handle\":\"alice\",\"role\":\"VIEWER\",\"other\":true}]}",
            "\"access\":{\"accounts\":[{\"handle\":\"alice\",\"role\":\"VIEWER\"},{\"handle\":\"alice\",\"role\":\"MANAGER\"}]}",
            "\"access\":{\"accounts\":[]},\"access\":{\"accounts\":[]}",
            "\"routing\":{\"sendTo\":\"other\"}",
            "\"routing\":{\"routeFiles\":[\"../routes.json\"]}")) {
      Files.writeString(
          root.resolve("plowshare.json"), "{\"version\":1,\"name\":\"chatbot\"," + fields + "}");
      assertEquals(ApplicationPolicy.Kind.INVALID, policy.read("chatbot").kind(), fields);
    }
    for (String contents :
        List.of(
            "chatbot",
            "",
            "{\"version\":2,\"name\":\"chatbot\"}",
            "{\"version\":1,\"name\":\"other\"}",
            "{\"version\":1,\"name\":\"chatbot\"} {}")) {
      Files.writeString(root.resolve("plowshare.json"), contents);
      assertEquals(ApplicationPolicy.Kind.INVALID, policy.read("chatbot").kind());
    }
  }

  @Test
  void excluded_linked_and_oversized_manifests_close_the_boundary() throws Exception {
    Path file = root.resolve("plowshare.json"), target = root.resolve("source.json");
    Files.writeString(target, "{\"version\":1,\"name\":\"chatbot\"}");
    Files.createSymbolicLink(file, target);
    assertEquals(ApplicationPolicy.Kind.INVALID, policy.read("chatbot").kind());
    Files.delete(file);
    Files.move(target, file);
    var project = projects.find("chatbot").orElseThrow();
    when(projects.effectiveExclusions(project)).thenReturn(List.of(file));
    assertEquals(ApplicationPolicy.Kind.INVALID, policy.read("chatbot").kind());
    when(projects.effectiveExclusions(project)).thenReturn(List.of());
    Files.writeString(file, " ".repeat(65537));
    assertEquals(ApplicationPolicy.Kind.INVALID, policy.read("chatbot").kind());
    Files.write(file, new byte[] {(byte) 0xff});
    assertEquals(ApplicationPolicy.Kind.INVALID, policy.read("chatbot").kind());
  }
}
