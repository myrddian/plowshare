package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationDeployment.File;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceSnapshot;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first of ADR 0042's three confinement inventories: the surfaces a child manifest can declare.
 * Each is reduced to the parent's hold or refused, and never honoured as written. Refusing a
 * surface is an acceptable first slice; honouring a child's declaration of one is not, and neither
 * is accepting the package while silently dropping the declaration — the last test is the one that
 * holds both halves of that together.
 *
 * <p>Mocked repositories throughout. Every refusal below is decided before the durable unit of work
 * opens, which is why the totality assertion can be {@code verifyNoInteractions} rather than a
 * rollback inspection.
 */
class ContainedManifestRefusalTest {
  @TempDir Path directory;

  ContainedApplicationRepository records = mock(ContainedApplicationRepository.class);
  ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  ApplicationPackageValidator validator = mock(ApplicationPackageValidator.class);
  OperationAuthority authority = mock(OperationAuthority.class);
  IdentityRepository identities = mock(IdentityRepository.class);
  ContainedPrincipals principals = mock(ContainedPrincipals.class);
  ContainedApplicationCreationTest.Allowances allowances =
      new ContainedApplicationCreationTest.Allowances();
  ContainedApplicationCreationTest.Work work = new ContainedApplicationCreationTest.Work();

  AccountId account = new AccountId(UUID.randomUUID());
  AuthorityScope.Project parentScope =
      new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 7);
  AllowanceId parentBudget = new AllowanceId(UUID.randomUUID());
  OperationAuthority.Admission parentAdmission =
      new OperationAuthority.Admission(
          UUID.randomUUID(),
          "@service/parent",
          account,
          parentScope.id(),
          Home.of("parent"),
          GrantRecipient.account(account),
          List.of(
              new OperationAuthority.Support(AuthorityAction.READ, UUID.randomUUID()),
              new OperationAuthority.Support(AuthorityAction.WRITE, UUID.randomUUID())));

  Path root;
  Path parentWorkspace;
  SourceContainedApplications service;

  @BeforeEach
  void setup() throws Exception {
    root = Files.createDirectories(directory.resolve("containment"));
    parentWorkspace = Files.createDirectories(directory.resolve("parent"));
    Files.writeString(
        parentWorkspace.resolve("plowshare.json"),
        "{\"version\":1,\"name\":\"parent\",\"nesting\":true}");
    Files.createDirectories(parentWorkspace.resolve("server"));
    Files.writeString(
        parentWorkspace.resolve("server/tools.json"),
        """
        {"version":1,"bindings":[],"providers":[
          {"provider":"alpha","account":"@service/parent","prefix":"a_","leaseSeconds":30}]}
        """);
    Files.writeString(
        parentWorkspace.resolve("server/ports.json"),
        """
        {"version":1,"bindings":[
          {"topic":"held.topic","account":"@service/parent","direction":"EGRESS",
           "groups":["team"]}]}
        """);
    Files.createDirectories(parentWorkspace.resolve("swarm"));
    Files.writeString(parentWorkspace.resolve("swarm/review.md"), "# review");
    Files.createDirectories(parentWorkspace.resolve("schedules"));
    Files.writeString(parentWorkspace.resolve("schedules/nightly.json"), "{}");

    allowances.retain(new AllowanceSnapshot(parentBudget, 100, 0));
    when(projects.find("parent"))
        .thenReturn(
            Optional.of(
                new ProjectRecord(
                    "parent", parentWorkspace, List.of(), List.of(), "MANAGED", List.of())));
    when(projects.effectiveExclusions(any())).thenReturn(List.of());
    when(authority.conversation("c-1"))
        .thenReturn(
            Optional.of(
                new OperationAuthority.Conversation(
                    "c-1", "worker", parentAdmission, null, parentBudget, "0".repeat(64))));
    when(identities.scope(parentScope.id())).thenReturn(parentScope);
    when(identities.containedHome(any(), any()))
        .thenAnswer(
            call ->
                Home.of(
                    "contained:" + ((AuthorityScope.Contained) call.getArgument(0)).id().value()));
    when(records.liveDescendants(parentScope.id())).thenReturn(0);
    when(records.receipt(any(), any())).thenReturn(Optional.empty());
    when(records.create(any()))
        .thenAnswer(
            call -> {
              ContainedApplicationRepository.Creation creation = call.getArgument(0);
              return new ContainedApplicationRepository.Retained(
                  UUID.randomUUID(),
                  new ScopeId(UUID.randomUUID()),
                  99,
                  creation.project(),
                  creation.container(),
                  creation.requestId(),
                  creation.fingerprint(),
                  creation.revision(),
                  creation.allowance(),
                  creation.ceiling(),
                  true);
            });
    when(principals.issue(any()))
        .thenAnswer(
            call ->
                new ContainedPrincipals.ContainedPrincipal(
                    UUID.randomUUID(), ((AuthorityScope.Contained) call.getArgument(0)).id()));
    when(authority.contain(any(), any(), any()))
        .thenAnswer(
            call -> {
              AuthorityScope.Contained target = call.getArgument(1);
              Set<AuthorityAction> actions = call.getArgument(2);
              return new OperationAuthority.Admission(
                  UUID.randomUUID(),
                  parentAdmission.executor(),
                  parentAdmission.account(),
                  target.id(),
                  Home.of("contained:" + target.id().value()),
                  parentAdmission.source(),
                  parentAdmission.support().stream()
                      .filter(item -> actions.contains(item.action()))
                      .toList());
            });
    service =
        new SourceContainedApplications(
            records,
            projects,
            validator,
            authority,
            identities,
            principals,
            allowances,
            work,
            new ContainmentConfig(root.toString(), 2, 4));
  }

  /** A child package whose extra files are added to the minimum valid Application. */
  ContainedApplications.Create child(File... extra) {
    List<File> files = new ArrayList<>();
    files.add(new File("plowshare.json", "{\"version\":1,\"name\":\"child\"}"));
    files.addAll(List.of(extra));
    return new ContainedApplications.Create("c-1", UUID.randomUUID(), 5, null, List.of(), files);
  }

  CallerFault refused(ContainedApplications.Create request) {
    return assertThrows(CallerFault.class, () -> service.create("@service/parent", request));
  }

  @Test
  void aChildProviderDeclarationBeyondTheParentIsRefused() {
    var refusal =
        refused(
            child(
                new File(
                    "server/tools.json",
                    "{\"version\":1,\"bindings\":[],\"providers\":[{\"provider\":\"beta\","
                        + "\"account\":\"@service/parent\",\"prefix\":\"b_\","
                        + "\"leaseSeconds\":30}]}")));
    assertTrue(refusal.getMessage().contains("provider"), refusal.getMessage());
    // The one the parent does hold narrows rather than refusing, so the comparison is a subset
    // test and not a blanket ban on the surface.
    assertDoesNotThrow(
        () ->
            service.create(
                "@service/parent",
                child(
                    new File(
                        "server/tools.json",
                        "{\"version\":1,\"bindings\":[],\"providers\":[{\"provider\":\"alpha\","
                            + "\"account\":\"@service/parent\",\"prefix\":\"a_\","
                            + "\"leaseSeconds\":30}]}"))));
  }

  @Test
  void aChildRelayPortBeyondTheParentIsRefused() {
    var refusal =
        refused(
            child(
                new File(
                    "server/ports.json",
                    "{\"version\":1,\"bindings\":[{\"topic\":\"other.topic\","
                        + "\"account\":\"@service/parent\",\"direction\":\"INGRESS\"}]}")));
    assertTrue(refusal.getMessage().contains("Relay"), refusal.getMessage());
    verifyNoInteractions(principals);
  }

  @Test
  void aChildRelayAudienceBeyondTheParentIsRefused() {
    var refusal =
        refused(
            child(
                new File(
                    "server/ports.json",
                    "{\"version\":1,\"bindings\":[{\"topic\":\"held.topic\","
                        + "\"account\":\"@service/parent\",\"direction\":\"EGRESS\","
                        + "\"groups\":[\"outsiders\"]}]}")));
    assertTrue(refusal.getMessage().contains("audience"), refusal.getMessage());
    // The parent's own audience on its own topic is inside its hold and narrows.
    assertDoesNotThrow(
        () ->
            service.create(
                "@service/parent",
                child(
                    new File(
                        "server/ports.json",
                        "{\"version\":1,\"bindings\":[{\"topic\":\"held.topic\","
                            + "\"account\":\"@service/parent\",\"direction\":\"EGRESS\","
                            + "\"groups\":[\"team\"]}]}"))));
  }

  @Test
  void aChildSwarmTypeBeyondTheParentIsRefused() {
    var refusal = refused(child(new File("swarm/unheld.md", "# unheld")));
    assertTrue(refusal.getMessage().contains("swarm"), refusal.getMessage());
    assertDoesNotThrow(
        () -> service.create("@service/parent", child(new File("swarm/review.md", "# review"))));
  }

  @Test
  void aChildScheduleDeclarationIsRefusedInSliceOne() {
    // The parent holds a schedule of its own, so this is not a missing-hold refusal: schedule
    // ownership is ADR 0020's and is refused outright for a child in this slice.
    assertTrue(Files.exists(parentWorkspace.resolve("schedules/nightly.json")));
    var refusal = refused(child(new File("schedules/nightly.json", "{}")));
    assertTrue(refusal.getMessage().contains("schedule"), refusal.getMessage());
  }

  @Test
  void aChildFileStoreDestinationOutsideTheParentFenceIsRefused() {
    var refusal =
        assertThrows(
            CallerFault.class,
            () ->
                service.create(
                    "@service/parent",
                    new ContainedApplications.Create(
                        "c-1",
                        UUID.randomUUID(),
                        5,
                        null,
                        List.of(new FileStoreReference("elsewhere", "outside")),
                        List.of(
                            new File("plowshare.json", "{\"version\":1,\"name\":\"child\"}")))));
    assertTrue(refusal.getMessage().contains("FileStore"), refusal.getMessage());
    verify(records, never()).create(any());
  }

  /**
   * The assertion that matters. A refused manifest leaves no project row, no scope, no principal,
   * no allowance and no staged source: every declaration comparison completes before the durable
   * unit opens and before any byte is written under the containment root.
   */
  @Test
  void aRefusedManifestCreatesNoProjectScopeOrPrincipal() throws Exception {
    for (var request :
        List.of(
            child(
                new File(
                    "server/tools.json",
                    "{\"version\":1,\"bindings\":[],\"providers\":[{\"provider\":\"beta\","
                        + "\"account\":\"@service/parent\",\"prefix\":\"b_\","
                        + "\"leaseSeconds\":30}]}")),
            child(
                new File(
                    "server/ports.json",
                    "{\"version\":1,\"bindings\":[{\"topic\":\"other.topic\","
                        + "\"account\":\"@service/parent\",\"direction\":\"INGRESS\"}]}")),
            child(new File("swarm/unheld.md", "# unheld")),
            child(new File("schedules/nightly.json", "{}"))))
      assertThrows(CallerFault.class, () -> service.create("@service/parent", request));
    verify(records, never()).create(any());
    verifyNoInteractions(principals);
    assertTrue(allowances.held.keySet().equals(Set.of(parentBudget)));
    assertEquals(0, work.entered);
    verify(authority, never()).contain(any(), any(), any());
    try (var entries = Files.list(root)) {
      assertEquals(List.of(), entries.toList());
    }
  }
}
