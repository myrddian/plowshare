package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationDeployment.File;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.allowances.*;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parent-authorized creation of a confined child Application.
 *
 * <p>Every repository here is a mocked interface or a small in-memory fake, per the database test
 * policy: nothing asserted below is PostgreSQL behaviour. The one real-database assertion in this
 * change is {@code ContainedApplicationSchemaTest}, where a constraint is the fence.
 *
 * <p><strong>The absence of a dependency is an assertion.</strong> {@code
 * SourceContainedApplications} is constructed below with no {@code ProjectMembers} and no {@code
 * FileStores}, so there is no object through which a server-administrator check or a destination
 * FileStore {@code MANAGER} check could be made. The child path's authority is the parent's own
 * admission narrowed by {@code contain}, and its destination is allocated by the server under the
 * configured containment root. The top-level {@code application.deploy} gate is untouched and is
 * still asserted by {@code SourceApplicationDeploymentsTest}.
 */
class ContainedApplicationCreationTest {
  @TempDir Path directory;

  ContainedApplicationRepository records = mock(ContainedApplicationRepository.class);
  ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  ApplicationPackageValidator validator = mock(ApplicationPackageValidator.class);
  OperationAuthority authority = mock(OperationAuthority.class);
  IdentityRepository identities = mock(IdentityRepository.class);
  ContainedPrincipals principals = mock(ContainedPrincipals.class);
  Allowances allowances = new Allowances();
  Work work = new Work();

  AccountId account = new AccountId(UUID.randomUUID());
  GrantRecipient recipient = GrantRecipient.account(account);
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
          recipient,
          List.of(
              new OperationAuthority.Support(AuthorityAction.READ, UUID.randomUUID()),
              new OperationAuthority.Support(AuthorityAction.WRITE, UUID.randomUUID()),
              new OperationAuthority.Support(AuthorityAction.RUN_MODEL, UUID.randomUUID()),
              new OperationAuthority.Support(
                  AuthorityAction.CONVERSATION_CREATE, UUID.randomUUID())));

  Path root;
  Path parentWorkspace;
  SourceContainedApplications service;

  /** Spends and ceilings are real here, because four of the budget assertions are about them. */
  static final class Allowances implements AllowanceRepository {
    final Map<AllowanceId, AllowanceSnapshot> held = new LinkedHashMap<>();

    @Override
    public void retain(AllowanceSnapshot selected) {
      if (held.putIfAbsent(selected.id(), selected) != null)
        throw new IllegalStateException("An existing allowance is never reset");
    }

    @Override
    public Optional<AllowanceSnapshot> find(AllowanceId id) {
      return Optional.ofNullable(held.get(id));
    }

    @Override
    public Optional<io.aeyer.plowshare.server.agents.Budget.Claim> claim(
        AllowanceId id, int reserve, Integer expectedSpent) {
      var snapshot = held.get(id);
      if (snapshot == null) return Optional.empty();
      if (snapshot.ceiling() != null && snapshot.spent() + reserve >= snapshot.ceiling())
        return Optional.empty();
      held.put(id, new AllowanceSnapshot(id, snapshot.ceiling(), snapshot.spent() + 1));
      return Optional.of(
          new io.aeyer.plowshare.server.agents.Budget.Claim(
              id.value(), snapshot.spent() + 1, snapshot.ceiling()));
    }

    @Override
    public void accept(io.aeyer.plowshare.server.agents.Budget.Claim claim) {}

    @Override
    public void ceiling(AllowanceId id, Integer total) {
      var snapshot = held.get(id);
      if (snapshot == null) throw new IllegalStateException("Missing allowance");
      held.put(id, new AllowanceSnapshot(id, total, snapshot.spent()));
    }

    @Override
    public void increase(AllowanceId id, int total, int observedSpent) {
      ceiling(id, total);
    }

    @Override
    public void add(AllowanceId id, int calls) {
      var snapshot = held.get(id);
      ceiling(id, snapshot.ceiling() == null ? null : snapshot.ceiling() + calls);
    }
  }

  /** Records whether the durable unit rolled back, which is what refusal totality rests on. */
  static final class Work implements UnitOfWork {
    boolean rolledBack;
    int entered;

    @Override
    public <T> T inTransaction(java.util.function.Supplier<T> body) {
      entered++;
      try {
        return body.get();
      } catch (RuntimeException failed) {
        rolledBack = true;
        throw failed;
      }
    }
  }

  @BeforeEach
  void setup() throws Exception {
    root = Files.createDirectories(directory.resolve("containment"));
    parentWorkspace = Files.createDirectories(directory.resolve("parent"));
    manifest(parentWorkspace, "{\"version\":1,\"name\":\"parent\",\"nesting\":true}");
    allowances.retain(new AllowanceSnapshot(parentBudget, 100, 10));
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
              AuthorityScope.Contained child = call.getArgument(1);
              Set<AuthorityAction> actions = call.getArgument(2);
              return new OperationAuthority.Admission(
                  UUID.randomUUID(),
                  parentAdmission.executor(),
                  parentAdmission.account(),
                  child.id(),
                  Home.of("contained:" + child.id().value()),
                  parentAdmission.source(),
                  parentAdmission.support().stream()
                      .filter(item -> actions.contains(item.action()))
                      .toList());
            });
    service = service(new ContainmentConfig(root.toString(), 2, 4));
  }

  SourceContainedApplications service(ContainmentConfig config) {
    return new SourceContainedApplications(
        records, projects, validator, authority, identities, principals, allowances, work, config);
  }

  static void manifest(Path workspace, String text) throws Exception {
    Files.writeString(workspace.resolve("plowshare.json"), text);
  }

  List<File> files() {
    return List.of(
        new File("plowshare.json", "{\"version\":1,\"name\":\"child\"}"),
        new File("agents/README.md", "notes"));
  }

  ContainedApplications.Create request() {
    return new ContainedApplications.Create("c-1", UUID.randomUUID(), 5, null, List.of(), files());
  }

  Path staged(ContainedApplications.Child child) {
    return root.resolve(child.project().substring("contained:".length()))
        .resolve("revisions")
        .resolve(child.revision().toString());
  }

  @Test
  void aParentCreatesAChildWithoutServerAdministratorAuthority() throws Exception {
    var child = service.create("@service/parent", request());
    assertTrue(child.project().startsWith("contained:"));
    assertEquals("notes", Files.readString(staged(child).resolve("agents/README.md")));
    // The parent's own admission, narrowed across the hop, is the whole authority for this path.
    verify(authority).require(parentAdmission);
    verify(authority).contain(eq(parentAdmission), any(), any());
    verify(principals).issue(any());
  }

  @Test
  void creationRefusesWhenTheParentManifestDoesNotDeclareNesting() throws Exception {
    manifest(parentWorkspace, "{\"version\":1,\"name\":\"parent\"}");
    assertEquals(
        "This Application does not declare nesting",
        assertThrows(CallerFault.class, () -> service.create("@service/parent", request()))
            .getMessage());
    verifyNoInteractions(principals);
    verify(records, never()).create(any());
    assertFalse(Files.exists(root.resolve("revisions")));
    assertEquals(0, children());
  }

  @Test
  void creationRefusesWhenTheNestingRootIsNotConfigured() {
    var unconfigured = service(ContainmentConfig.absent());
    assertThrows(CallerFault.class, () -> unconfigured.create("@service/parent", request()));
    verify(records, never()).create(any());
    // A present-but-incomplete configuration is a startup failure, never a silent default.
    assertThrows(IllegalStateException.class, () -> new ContainmentConfig(null, 2, 4));
    assertThrows(IllegalStateException.class, () -> new ContainmentConfig("relative/path", 2, 4));
    assertThrows(IllegalStateException.class, () -> new ContainmentConfig(root.toString(), 0, 4));
    assertThrows(IllegalStateException.class, () -> new ContainmentConfig(root.toString(), 2, 0));
    assertThrows(
        IllegalStateException.class, () -> new ContainmentConfig(root.toString(), 2, null));
  }

  @Test
  void creationAllocatesTheDestinationAndIgnoresAnyRequestedOne() throws Exception {
    var requested = new FileStoreReference("elsewhere", "parent/own/source");
    var child =
        service.create(
            "@service/parent",
            new ContainedApplications.Create(
                "c-1", UUID.randomUUID(), 5, requested, List.of(), files()));
    var creation = creation();
    assertTrue(creation.workspace().startsWith(root));
    assertFalse(creation.workspace().toString().contains("elsewhere"));
    assertTrue(Files.isDirectory(staged(child)));
    // Nothing resolved or honoured the requested store: there is no FileStore collaborator at all.
    assertFalse(Files.exists(directory.resolve("elsewhere")));
  }

  @Test
  void theChildProjectNameIsOpaqueAndCarriesNoContainmentPath() {
    var child = service.create("@service/parent", request());
    String suffix = child.project().substring("contained:".length());
    assertDoesNotThrow(() -> UUID.fromString(suffix));
    assertFalse(child.project().contains("parent"));
    assertFalse(child.project().contains("/"));
    // A second child is not named relative to the first, so no name grows with depth or breadth.
    var sibling = service.create("@service/parent", request());
    assertNotEquals(child.project(), sibling.project());
    assertFalse(sibling.project().contains(suffix));
  }

  @Test
  void creationAppliesPackageLimitsPerChild() {
    List<File> many = new ArrayList<>();
    many.add(new File("plowshare.json", "{\"version\":1,\"name\":\"child\"}"));
    for (int index = 0; index < SourceApplicationDeployments.MAX_FILES; index++)
      many.add(new File("notes/" + index + ".md", "x"));
    assertThrows(
        CallerFault.class,
        () ->
            service.create(
                "@service/parent",
                new ContainedApplications.Create(
                    "c-1", UUID.randomUUID(), 5, null, List.of(), many)));
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
                    List.of(),
                    List.of(
                        new File("plowshare.json", "{\"version\":1,\"name\":\"child\"}"),
                        new File(
                            "big.md",
                            "x".repeat(SourceApplicationDeployments.MAX_FILE_BYTES + 1))))));
    verify(records, never()).create(any());
    assertEquals(0, children());
  }

  @Test
  void creationRefusesAtTheLiveDescendantBound() {
    when(records.liveDescendants(parentScope.id())).thenReturn(4);
    assertEquals(
        "This Application already holds its permitted live contained Applications",
        assertThrows(CallerFault.class, () -> service.create("@service/parent", request()))
            .getMessage());
    verify(records, never()).create(any());
    assertEquals(0, children());
    // The bound is an aggregate across descendants, not a per-parent sibling count: the count the
    // service asks for is the subtree's, and one below the bound still admits.
    when(records.liveDescendants(parentScope.id())).thenReturn(3);
    assertNotNull(service.create("@service/parent", request()));
  }

  @Test
  void creationRefusesAnIdenticalRequestUuidWithChangedContent() {
    UUID requestId = UUID.randomUUID();
    var first =
        service.create(
            "@service/parent",
            new ContainedApplications.Create("c-1", requestId, 5, null, List.of(), files()));
    var retained = retained(requestId);
    when(records.receipt(parentScope.id(), requestId)).thenReturn(Optional.of(retained));
    assertThrows(
        CallerFault.class,
        () ->
            service.create(
                "@service/parent",
                new ContainedApplications.Create(
                    "c-1",
                    requestId,
                    5,
                    null,
                    List.of(),
                    List.of(
                        new File("plowshare.json", "{\"version\":1,\"name\":\"child\"}"),
                        new File("agents/README.md", "different")))));
    verify(records, times(1)).create(any());
    assertNotNull(first);
  }

  @Test
  void anIdenticalRetryReadsItsReceiptBeforeTouchingFiles() throws Exception {
    UUID requestId = UUID.randomUUID();
    var create = new ContainedApplications.Create("c-1", requestId, 5, null, List.of(), files());
    var first = service.create("@service/parent", create);
    var receipt = retained(requestId);
    when(records.receipt(parentScope.id(), requestId)).thenReturn(Optional.of(receipt));
    // Breaking the staged source proves the retry answered from its receipt rather than restaging.
    Files.delete(staged(first).resolve("plowshare.json"));
    var again = service.create("@service/parent", create);
    assertEquals(first.project(), again.project());
    assertEquals(first.revision(), again.revision());
    verify(records, times(1)).create(any());
    verify(validator, times(1)).validate(any(), any());
    verify(principals, times(1)).issue(any());
  }

  @Test
  void creationAllocatesTheChildOwnAllowanceUnderAParentSetCeiling() {
    var child = service.create("@service/parent", request());
    var creation = creation();
    assertNotEquals(parentBudget, creation.allowance());
    assertEquals(5, creation.ceiling());
    var snapshot = allowances.find(creation.allowance()).orElseThrow();
    assertEquals(5, snapshot.ceiling());
    assertEquals(0, snapshot.spent());
    // The parent's own allowance is not debited, resized, or shared by the creation.
    assertEquals(100, allowances.find(parentBudget).orElseThrow().ceiling());
    assertEquals(10, allowances.find(parentBudget).orElseThrow().spent());
    assertNotNull(child);
  }

  @Test
  void creationRefusesACeilingAboveTheParentRemainingAllowance() {
    // The parent holds 100 with 10 spent, so 90 remain; 91 is more authority than it has.
    assertEquals(
        "A contained ceiling cannot exceed the creating Application's remaining allowance",
        assertThrows(
                CallerFault.class,
                () ->
                    service.create(
                        "@service/parent",
                        new ContainedApplications.Create(
                            "c-1", UUID.randomUUID(), 91, null, List.of(), files())))
            .getMessage());
    assertDoesNotThrow(
        () ->
            service.create(
                "@service/parent",
                new ContainedApplications.Create(
                    "c-1", UUID.randomUUID(), 90, null, List.of(), files())));
    // An absent ceiling is unlimited by AllowanceSnapshot's own contract, so it is never accepted.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ContainedApplications.Create(
                "c-1", UUID.randomUUID(), null, null, List.of(), files()));
  }

  @Test
  void theParentCanReduceAChildCeilingToZero() {
    var child = service.create("@service/parent", request());
    var retained = retained(creation().requestId());
    when(records.find(child.id())).thenReturn(Optional.of(retained));
    when(identities.home(parentScope)).thenReturn(Home.of("parent"));
    when(authority.admit(eq("@service/parent"), eq(parentScope), any()))
        .thenReturn(parentAdmission);
    service.ceiling("@service/parent", child.id(), 0);
    assertEquals(0, allowances.find(retained.allowance()).orElseThrow().ceiling());
    verify(records).ceiling(child.id(), 0);
    // Reduction is the parent's own authority over the container, not an administrative act.
    verify(authority).admit(eq("@service/parent"), eq(parentScope), any());
    // Absent stays unrepresentable: zero disables calls, null would mean unlimited.
    assertThrows(CallerFault.class, () -> service.ceiling("@service/parent", child.id(), null));
  }

  @Test
  void aChildCallIsRefusedOnceItsCeilingIsReached() {
    var child =
        service.create(
            "@service/parent",
            new ContainedApplications.Create(
                "c-1", UUID.randomUUID(), 1, null, List.of(), files()));
    var allowance = creation().allowance();
    assertTrue(allowances.claim(allowance, 0).isPresent());
    assertTrue(allowances.claim(allowance, 0).isEmpty());
    var retained = retained(creation().requestId());
    when(records.find(child.id())).thenReturn(Optional.of(retained));
    when(identities.home(parentScope)).thenReturn(Home.of("parent"));
    when(authority.admit(eq("@service/parent"), eq(parentScope), any()))
        .thenReturn(parentAdmission);
    service.ceiling("@service/parent", child.id(), 0);
    assertTrue(allowances.claim(allowance, 0).isEmpty());
  }

  @Test
  void childSpendIsAttributedToTheChildNotTheParent() {
    service.create("@service/parent", request());
    var allowance = creation().allowance();
    allowances.claim(allowance, 0);
    allowances.claim(allowance, 0);
    assertEquals(2, allowances.find(allowance).orElseThrow().spent());
    // ADR 0025's immutable attribution: the hop changed no accounting account.
    assertEquals(10, allowances.find(parentBudget).orElseThrow().spent());
    assertNotEquals(parentBudget, allowance);
  }

  /**
   * Finding (b), asserted rather than reasoned about. A contained principal's identifier is a bare
   * UUID, so {@code ServiceCredentials.principal} is false for it and any path that classifies a
   * caller by the {@code @service/} prefix would route it into the <em>user</em> branch — of {@code
   * RequestProjects}, {@code ProjectAuthorization} and {@code ApplicationProjectMembers}. That is
   * safe today only because no {@code admins} row exists for it, which is an absence rather than a
   * rule. This is the creating path, so what it must guarantee is that the identifier never becomes
   * a handle in the first place: the hop's admission answers as the parent's own executor and
   * account, and nothing here resolves an account from any string at all.
   */
  @Test
  void aContainedPrincipalIsNeverUsedAsAnAccountHandle() {
    service.create("@service/parent", request());
    var issued = org.mockito.ArgumentCaptor.forClass(AuthorityScope.Contained.class);
    verify(principals).issue(issued.capture());
    var principal = principals.issue(issued.getValue());
    var hop = org.mockito.ArgumentCaptor.forClass(OperationAuthority.Admission.class);
    verify(authority).contain(hop.capture(), any(), any());
    assertEquals(parentAdmission, hop.getValue());
    // Not an @service/ name, so service-token rules do not classify it; and not presented as a
    // handle anywhere, so the user branch it would otherwise fall into is never reached.
    assertFalse(
        io.aeyer.plowshare.server.auth.ServiceCredentials.principal(principal.id().toString()));
    verify(identities, never()).account(anyString());
    verify(identities, never()).project(anyString());
    verify(projects, never()).find(principal.id().toString());
  }

  int children() {
    try (var entries = Files.list(root)) {
      return (int) entries.count();
    } catch (java.io.IOException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  ContainedApplicationRepository.Creation creation() {
    var captor = org.mockito.ArgumentCaptor.forClass(ContainedApplicationRepository.Creation.class);
    verify(records, atLeastOnce()).create(captor.capture());
    return captor.getValue();
  }

  ContainedApplicationRepository.Retained retained(UUID requestId) {
    var creation = creation();
    return new ContainedApplicationRepository.Retained(
        UUID.randomUUID(),
        new ScopeId(UUID.randomUUID()),
        99,
        creation.project(),
        creation.container(),
        requestId,
        creation.fingerprint(),
        creation.revision(),
        creation.allowance(),
        creation.ceiling(),
        true);
  }
}
