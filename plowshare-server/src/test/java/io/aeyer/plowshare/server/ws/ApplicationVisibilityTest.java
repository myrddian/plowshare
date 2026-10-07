package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.AccessRequestDecoder;
import io.aeyer.plowshare.server.access.ProjectAuthorization;
import io.aeyer.plowshare.server.access.ResourceScopeRepository;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.api.ProjectController;
import io.aeyer.plowshare.server.api.ProjectView;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApplicationVisibilityTest {
  @Test
  void both_listings_hide_applications_and_direct_authority_uses_the_same_boundary() {
    ProjectStore projects = mock(ProjectStore.class);
    ProjectMembers stored = mock(ProjectMembers.class);
    ApplicationPolicy policy = mock(ApplicationPolicy.class);
    ProjectMembers members = new ApplicationProjectMembers(stored, policy);
    var application =
        new ProjectRecord(
            "chatbot", Path.of("/fixture/chatbot"), List.of(), List.of(), "DISJOINT", List.of());
    var legacy = new ProjectRecord("legacy", Path.of("/fixture/legacy"), List.of(), List.of());
    when(projects.allFor("reader")).thenReturn(List.of(application, legacy));
    when(projects.allForSession("reader", "session")).thenReturn(List.of(application, legacy));
    when(projects.effectiveExclusions(any(ProjectRecord.class))).thenReturn(List.of());
    when(stored.role(anyString(), eq("reader"))).thenReturn(Optional.of(ProjectRole.MANAGER));
    when(policy.read("chatbot"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.APPLICATION, Map.of()));
    when(policy.read("legacy"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of()));
    var frames = new ProjectListHandler(projects, members);
    var http = new ProjectController(projects, new PresenceRegistry(), members);
    var resources = mock(ResourceScopeRepository.class);
    when(resources.projects(any())).thenReturn(List.of("chatbot"));
    var access = new ProjectAuthorization(resources, members, mock(AdminStore.class));
    assertEquals(
        List.of("legacy"), http.list("reader").getBody().stream().map(ProjectView::name).toList());
    assertEquals(List.of("legacy"), frameRows(frames).stream().map(ProjectView::name).toList());
    assertFalse(
        access.allowed(
            "job.status",
            AccessRequestDecoder.decode("job.status", Map.of("job", "job-fixture")),
            "reader"));
    assertFalse(
        access.allowed(
            "agent.list",
            AccessRequestDecoder.decode("agent.list", Map.of("project", "chatbot")),
            "reader"));
    when(policy.read("chatbot"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("reader", ProjectRole.VIEWER)));
    assertEquals("application", http.list("reader").getBody().getFirst().kind());
    assertEquals(ProjectRole.VIEWER, frameRows(frames).getFirst().role());
    assertTrue(
        access.allowed(
            "agent.list",
            AccessRequestDecoder.decode("agent.list", Map.of("project", "chatbot")),
            "reader"));
    assertFalse(
        access.allowed(
            "board.send",
            AccessRequestDecoder.decode(
                "board.send", Map.of("project", "chatbot", "body", "hello", "to", "bot")),
            "reader"));
    when(policy.read("chatbot"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    assertFalse(access.readable("chatbot", "reader"));
    when(stored.isServerAdmin("reader")).thenReturn(true);
    assertTrue(
        access.allowed(
            "project.member.add",
            AccessRequestDecoder.decode(
                "project.member.add", Map.of("project", "chatbot", "handle", "worker")),
            "reader"));
    assertFalse(
        access.allowed(
            "agent.list",
            AccessRequestDecoder.decode("agent.list", Map.of("project", "chatbot")),
            "reader"));
    assertEquals(List.of("legacy"), frameRows(frames).stream().map(ProjectView::name).toList());
    assertFalse(
        access.allowed(
            "job.status",
            AccessRequestDecoder.decode("job.status", Map.of("job", "job-fixture")),
            "reader"));
  }

  @SuppressWarnings(
      "unchecked") // The frame returns the exact ProjectView list built by this handler.
  private List<ProjectView> frameRows(ProjectListHandler frames) {
    return (List<ProjectView>) frames.handle(Map.of(), new Asking("session", "reader")).payload();
  }
}
