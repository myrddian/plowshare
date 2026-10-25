package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationIdentity;
import io.aeyer.plowshare.protocol.ContainedProjects;
import io.aeyer.plowshare.protocol.HostedToolScopes;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy;
import io.aeyer.plowshare.server.archive.ApplicationDeploymentStore;
import io.aeyer.plowshare.server.archive.ApplicationRegistrations;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.auth.ServiceAccountRepository;
import io.aeyer.plowshare.server.auth.ServiceAccounts;
import io.aeyer.plowshare.server.board.Board;
import io.aeyer.plowshare.server.board.MessagingProperties;
import io.aeyer.plowshare.server.board.ProjectMessageRouting;
import io.aeyer.plowshare.server.events.FileScheduleDefinitions;
import io.aeyer.plowshare.server.events.ScheduleControlAuthority;
import io.aeyer.plowshare.server.events.ScheduleDefinitionStore;
import io.aeyer.plowshare.server.events.ScheduleDefinitions;
import io.aeyer.plowshare.server.events.ScheduleFiles;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.FileStores;
import io.aeyer.plowshare.server.information.InformationEventRecords;
import io.aeyer.plowshare.server.information.JdbcInformationEventRecords;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR 0042's third inventory, as tests: every surface that makes a project addressable without
 * anyone naming it, fenced or refused, asserted at the site that decides.
 *
 * <p><strong>Every collaborator here is a mocked interface or a concrete class mocked without a
 * database.</strong> That is deliberate and it bounds what this class can claim. The assertions
 * below are all Java-side decisions: a prefix refusal, a boundary value, a loud unaddressable
 * failure, a declaration subset test. The sites whose fence is a SQL predicate — the project
 * listing, the authority functions a contained scope kind excludes, the union's {@code
 * project_type} filter and the scheduler's emitter authorization — cannot be proved here, because a
 * mock cannot evaluate a {@code LIKE} or fire a trigger. They are proved in {@link
 * ContainedAddressabilityDatabaseTest}, which states its own reason.
 *
 * <p>Each test is written so that the <em>containment</em> decision is the only thing that can make
 * it pass: where a site has a second, pre-existing fence — a membership check, a server-admin check
 * — that fence is mocked <em>open</em>, so removing the contained check turns the test red rather
 * than leaving it green on the other gate.
 */
class ContainedAddressabilityTest {
  private static final String CHILD = "contained:" + UUID.randomUUID();
  private static final String PARENT = "parent";

  // --- The reserved prefix itself -------------------------------------------------------------

  @Test
  void theContainedPrefixIsReservedCaseInsensitively() {
    assertTrue(ContainedProjects.name("contained:abc"));
    // The SQL exclusions are case-sensitive LIKE, so a Java fence that accepted this spelling
    // would admit a project the database had already mapped to an ordinary PROJECT scope.
    assertTrue(ContainedProjects.name("Contained:abc"));
    assertTrue(ContainedProjects.name("CONTAINED:abc"));
    assertFalse(ContainedProjects.name("containedly-named"));
    assertFalse(ContainedProjects.name("personal:someone"));
    assertFalse(ContainedProjects.name(null));
  }

  // --- Membership: a child project is not a thing accounts are added to ------------------------

  @Test
  void projectMembershipRefusesAContainedProject() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.getDataSource()).thenReturn(mock(javax.sql.DataSource.class));
    var members = new JdbcProjectMembers(jdbc);
    assertTrue(
        assertThrows(RuntimeException.class, () -> members.add(CHILD, "alice"))
            .getMessage()
            .contains("container"));
    assertTrue(
        assertThrows(RuntimeException.class, () -> members.remove(CHILD, "alice"))
            .getMessage()
            .contains("container"));
  }

  // --- Service credentials: no external principal gets a scope on a child ----------------------

  @Test
  void serviceTokenScopesRefuseAContainedProject() {
    var repository = mock(ServiceAccountRepository.class);
    var transactions = mock(UnitOfWork.class);
    var accounts = mock(AdminStore.class);
    var members = mock(ProjectMembers.class);
    when(transactions.inTransaction(any()))
        .thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
    // Both pre-existing gates are open, so only the containment decision can refuse.
    when(repository.lockOrdinaryProject(anyString())).thenReturn(true);
    when(repository.account(eq("@service/worker"), anyBoolean()))
        .thenReturn(
            new ServiceAccounts.Account(
                "@service/worker", true, java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)));
    var service = new ServiceAccounts(repository, transactions, accounts, members, null);
    var scope = new ServiceAccounts.Scope(CHILD, ProjectRole.VIEWER);
    assertThrows(
        CallerFault.class,
        () -> service.issue("admin", "@service/worker", "token", List.of(scope), 30));
  }

  // --- Deployment: a child's source area is its container's, not a deployment target -----------

  @Test
  void applicationDeploymentRefusesAContainedProject() {
    var members = mock(ProjectMembers.class);
    when(members.isServerAdmin("admin")).thenReturn(true); // the pre-existing gate, open
    var deployments =
        new SourceApplicationDeployments(
            mock(ApplicationDeploymentStore.class),
            members,
            mock(ProjectWorkspaces.class),
            mock(FileStores.class),
            mock(ApplicationPackageValidator.class));
    var refusal = assertThrows(CallerFault.class, () -> deployments.status("admin", CHILD));
    assertTrue(refusal.getMessage().contains("ordinary"), refusal.getMessage());
    // And at the protocol boundary, so no request naming a child can even be constructed.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new io.aeyer.plowshare.protocol.ApplicationDeployment.Activate(
                CHILD, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
  }

  // --- The agent file boundary: a child denies every account, it is not an external project -----

  @Test
  void theApplicationBoundaryOfAContainedProjectDeniesEveryAccount() {
    var workspaces = mock(ProjectWorkspaces.class);
    var registrations = mock(ApplicationRegistrations.class);
    var policy = new WorkspaceApplicationPolicy(workspaces, registrations);
    var boundary = policy.read(CHILD);
    assertEquals(ApplicationPolicy.Kind.INVALID, boundary.kind());
    // INVALID and not EXTERNAL: EXTERNAL passes a caller's membership straight through, which is
    // the shape that would let any role a child project ever acquired become file authority.
    assertTrue(boundary.limit("alice", Optional.of(ProjectRole.MANAGER)).isEmpty());
    // Nothing was read about the project at all; a child is refused before any workspace lookup.
    verifyNoInteractions(workspaces, registrations);
  }

  // --- Schedules: not inspectable, and never a registered source -------------------------------

  @Test
  void scheduleInspectionRefusesAContainedProject() {
    var members = mock(ProjectMembers.class);
    var schedules =
        new FileScheduleDefinitions(
            mock(ScheduleDefinitionStore.class),
            mock(ScheduleFiles.class),
            mock(ProjectStore.class),
            members,
            mock(ScheduleDefinitions.Authority.class),
            mock(ScheduleControlAuthority.class));
    var refusal = assertThrows(CallerFault.class, () -> schedules.requireRead("alice", CHILD));
    assertTrue(refusal.getMessage().contains("ordinary"), refusal.getMessage());
    // The role check is never reached, so the refusal does not depend on the authority predicate.
    verify(members, never()).requireRole(anyString(), anyString(), any());
  }

  // --- Messaging: a narrowing of existing scoping, in both directions --------------------------

  private ProjectMessageRouting routing(ProjectStore projects, ProjectMembers members) {
    return new ProjectMessageRouting(projects, members, new MessagingProperties(), null);
  }

  private ProjectStore openProjects() {
    var projects = mock(ProjectStore.class);
    when(projects.personalOwner(anyString())).thenReturn(Optional.empty());
    when(projects.id(anyString())).thenReturn(1L);
    return projects;
  }

  private ProjectMembers openMembers() {
    var members = mock(ProjectMembers.class);
    when(members.mayWork(anyString(), anyString())).thenReturn(true);
    return members;
  }

  @Test
  void messagingRefusesAContainedProjectAsFromProject() {
    // mayWork is open for both ends, so the refusal is the containment decision and not the
    // authority predicate that would also, separately, deny a child. The message is asserted
    // because an unconfigured route is also refused here, and that refusal would pass for the
    // wrong reason.
    var refusal =
        assertThrows(
            Board.Refused.class,
            () -> routing(openProjects(), openMembers()).require("@service/child", CHILD, PARENT));
    assertTrue(refusal.getMessage().contains("contained"), refusal.getMessage());
  }

  @Test
  void messagingRefusesAContainedProjectAsToProject() {
    var refusal =
        assertThrows(
            Board.Refused.class,
            () -> routing(openProjects(), openMembers()).require("alice", PARENT, CHILD));
    assertTrue(refusal.getMessage().contains("contained"), refusal.getMessage());
  }

  @Test
  void aChildCannotMessageAnythingItsParentCannot() {
    var routing = routing(openProjects(), openMembers());
    // The parent may message its own project; the child may not message even that, because there
    // is no forwarding path in this capability and a child's reach is not its container's.
    assertDoesNotThrow(() -> routing.require("alice", PARENT, PARENT));
    assertTrue(
        assertThrows(Board.Refused.class, () -> routing.require("@service/child", CHILD, CHILD))
            .getMessage()
            .contains("contained"));
    assertTrue(
        assertThrows(
                Board.Refused.class,
                () -> routing.require("@service/child", CHILD, "somewhere-else"))
            .getMessage()
            .contains("contained"));
  }

  // --- Relay: fail loudly, no fallback topic ---------------------------------------------------

  @Test
  void relayRefusesAContainedScopeLoudlyWithNoFallbackTopic() {
    var jdbc = mock(JdbcTemplate.class);
    UUID revision = UUID.randomUUID();
    when(jdbc.queryForMap(anyString(), (Object[]) any()))
        .thenReturn(
            Map.of(
                "kind",
                "CONTAINED",
                "project_id",
                7L,
                "document_type",
                "note",
                "document_subtype",
                "plain",
                "owner",
                "alice"));
    var records = new JdbcInformationEventRecords(jdbc);
    // ADR 0037 maps ADMINISTRATION and RESTRICTED to a loud failure with no fallback topic; a
    // contained scope follows, and ADR 0039 defers re-keying Relay, so no scope kind is added.
    var refusal =
        assertThrows(InformationEventRecords.Unaddressable.class, () -> records.resolve(revision));
    assertTrue(refusal.getMessage().contains("CONTAINED"), refusal.getMessage());
    // The project key a contained scope does carry must not have become a topic.
    assertFalse(refusal.getMessage().contains("ProjectScope"), refusal.getMessage());
  }

  // --- Protocol-side identity surfaces ---------------------------------------------------------

  @Test
  void anExternalPrincipalCannotNameAChildAsAnApplicationOrToolHostProject() {
    assertThrows(IllegalArgumentException.class, () -> ApplicationIdentity.project(CHILD));
    assertThrows(IllegalArgumentException.class, () -> HostedToolScopes.project(CHILD));
  }

  // --- Tool bindings and grants: the gap Task 5 recorded ---------------------------------------

  private ContainedDeclarations.Hold hold(Set<String> bindings, Set<String> workers) {
    return new ContainedDeclarations.Hold(
        Set.of(), Map.of(), Set.of(), Set.of(), bindings, workers);
  }

  @Test
  void aContainedToolBindingMustBeOneItsContainerAlreadyBinds() {
    var container = hold(Set.of("github/issue"), Set.of());
    var child = hold(Set.of("github/secrets"), Set.of());
    var refusal =
        assertThrows(
            CallerFault.class, () -> ContainedDeclarations.requireWithin(child, container));
    assertTrue(refusal.getMessage().contains("tool binding"), refusal.getMessage());
    // A binding identical to one the container holds stays admissible: this is a subset test and
    // not a ban on the surface, which is what Task 5 recorded as still missing.
    assertDoesNotThrow(() -> ContainedDeclarations.requireWithin(container, container));
  }

  @Test
  void aContainedRelayWorkerMustNameAnAccountItsContainerDeclares() {
    var container = hold(Set.of(), Set.of("@service/own"));
    var child = hold(Set.of(), Set.of("@service/other"));
    var refusal =
        assertThrows(
            CallerFault.class, () -> ContainedDeclarations.requireWithin(child, container));
    assertTrue(refusal.getMessage().contains("Relay worker"), refusal.getMessage());
    assertDoesNotThrow(() -> ContainedDeclarations.requireWithin(container, container));
  }
}
