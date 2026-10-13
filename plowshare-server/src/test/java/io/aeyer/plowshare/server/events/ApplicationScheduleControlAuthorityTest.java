package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.ApplicationResources;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationScheduleControlAuthorityTest {
  @TempDir Path root;
  private final ApplicationResources resources = mock(ApplicationResources.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ScheduleDefinitionStore.Source source =
      new ScheduleDefinitionStore.Source(1, "installer", 7L, "application", "server");
  private final ScheduleControlAuthority controls =
      new ApplicationScheduleControlAuthority(resources, members);

  @BeforeEach
  void configure() throws Exception {
    manifest("@service/executor");
    when(resources.root(7L)).thenReturn(Optional.of(root));
    when(members.mayWork("application", "@service/executor")).thenReturn(true);
  }

  private void manifest(String executor) throws Exception {
    Files.writeString(
        root.resolve("plowshare.json"),
        "{\"version\":1,\"name\":\"application\",\"executionAccount\":\"" + executor + "\"}");
  }

  @Test
  void explicitExecutorHasOperationalAuthorityWithoutManagerAuthority() {
    assertDoesNotThrow(() -> controls.requireApplicationControl(source, "@service/executor"));
    verify(members).mayWork("application", "@service/executor");
    verify(members).mayManage("application", "@service/executor");
  }

  @Test
  void anUnrelatedContributorCannotControlThisApplication() {
    when(members.mayWork("application", "other")).thenReturn(true);
    assertThrows(RuntimeException.class, () -> controls.requireApplicationControl(source, "other"));
  }

  @Test
  void aProjectManagerCanControlAServiceRegisteredApplication() {
    when(members.mayManage("application", "manager")).thenReturn(true);
    assertDoesNotThrow(() -> controls.requireApplicationControl(source, "manager"));
  }

  @Test
  void tokenRoleCeilingsAndRevokedMembershipAreRechecked() {
    controls.requireApplicationControl(source, "@service/executor");
    when(members.mayWork("application", "@service/executor")).thenReturn(false);
    assertThrows(
        RuntimeException.class,
        () -> controls.requireApplicationControl(source, "@service/executor"));
  }

  @Test
  void manifestRevocationAppliesOnTheNextCall() throws Exception {
    controls.requireApplicationControl(source, "@service/executor");
    manifest("replacement");
    assertThrows(
        RuntimeException.class,
        () -> controls.requireApplicationControl(source, "@service/executor"));
  }

  @Test
  void handleBindingAllowsItsScopedTokenButNotAnotherService() throws Exception {
    manifest("worker");
    when(members.authorityAccount("@service/executor")).thenReturn(Optional.of("worker"));
    controls.requireApplicationControl(source, "@service/executor");
    when(members.authorityAccount("@service/executor")).thenReturn(Optional.of("different-worker"));
    assertThrows(
        RuntimeException.class,
        () -> controls.requireApplicationControl(source, "@service/executor"));
  }

  @Test
  void declaringAPrincipalDoesNotGrantOtherTokensForTheSameHandle() {
    when(members.authorityAccount("@service/other-token")).thenReturn(Optional.of("worker"));
    when(members.mayWork("application", "@service/other-token")).thenReturn(true);
    assertThrows(
        RuntimeException.class,
        () -> controls.requireApplicationControl(source, "@service/other-token"));
  }

  @Test
  void unavailableMalformedAndLinkedManifestsFailClosed() throws Exception {
    when(resources.root(7L)).thenReturn(Optional.empty());
    assertThrows(
        RuntimeException.class,
        () -> controls.requireApplicationControl(source, "@service/executor"));
    when(resources.root(7L)).thenReturn(Optional.of(root));
    Files.writeString(root.resolve("plowshare.json"), "{broken}");
    assertThrows(
        RuntimeException.class,
        () -> controls.requireApplicationControl(source, "@service/executor"));
    Files.delete(root.resolve("plowshare.json"));
    Files.createSymbolicLink(root.resolve("plowshare.json"), root.resolve("missing"));
    assertThrows(
        RuntimeException.class,
        () -> controls.requireApplicationControl(source, "@service/executor"));
  }

  @Test
  void globalWorkspaceAndDifferentApplicationSourcesAreNotDelegated() {
    var global = new ScheduleDefinitionStore.Source(2, "installer", null, null, "server");
    var workspace =
        new ScheduleDefinitionStore.Source(3, "installer", 7L, "application", "workspace");
    var foreign =
        new ScheduleDefinitionStore.Source(4, "installer", 8L, "other-application", "server");
    assertThrows(
        RuntimeException.class,
        () -> controls.requireApplicationControl(global, "@service/executor"));
    assertThrows(
        RuntimeException.class,
        () -> controls.requireApplicationControl(workspace, "@service/executor"));
    assertThrows(
        RuntimeException.class,
        () -> controls.requireApplicationControl(foreign, "@service/executor"));
  }
}
