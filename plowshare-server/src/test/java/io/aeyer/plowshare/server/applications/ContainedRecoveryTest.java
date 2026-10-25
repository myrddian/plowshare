package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationDeployment.File;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.allowances.*;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crash recovery, which ADR 0005 deliberately does not provide for top-level staging and which this
 * capability must: for a contained Application destruction is the normal path, so a staged area
 * with no committed child is garbage rather than evidence.
 *
 * <p>The collector's entire search space is the configured containment root. That is the structural
 * reason it can never reach an ordinary Application's retained revisions — those live under a
 * FileStore destination and are addressed through {@code application_releases}, neither of which
 * this object holds — and {@code sweepLeavesTopLevelRetainedRevisionsAlone} asserts it rather than
 * taking it on trust.
 *
 * <p>Mocked repository interfaces and real temporary directories throughout. The cascade, the row
 * lock and concurrent create-versus-activate are {@code ContainedConcurrencyTest}.
 */
class ContainedRecoveryTest {
  @TempDir Path directory;

  ContainedApplicationRepository records = mock(ContainedApplicationRepository.class);
  ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  ApplicationPackageValidator validator = mock(ApplicationPackageValidator.class);
  OperationAuthority authority = mock(OperationAuthority.class);
  ContainedPrincipals principals = mock(ContainedPrincipals.class);
  ContainedDestructionTest.Allowances allowances = new ContainedDestructionTest.Allowances();
  ContainedDestructionTest.Work work = new ContainedDestructionTest.Work();

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
  ContainmentConfig config;
  SourceContainedApplications service;
  ContainedSourceCollector collector;

  @BeforeEach
  void setup() throws Exception {
    root = Files.createDirectories(directory.resolve("containment"));
    parentWorkspace = Files.createDirectories(directory.resolve("parent"));
    Files.writeString(
        parentWorkspace.resolve("plowshare.json"),
        "{\"version\":1,\"name\":\"parent\",\"nesting\":true}");
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
    when(records.liveDescendants(parentScope.id())).thenReturn(0);
    when(records.receipt(any(), any())).thenReturn(Optional.empty());
    when(records.uncollectedSources()).thenReturn(List.of());
    config = new ContainmentConfig(root.toString(), 2, 4);
    service =
        new SourceContainedApplications(
            records, projects, validator, authority, principals, allowances, work, config);
    collector = new ContainedSourceCollector(records, work, config);
  }

  List<File> files() {
    return List.of(
        new File("plowshare.json", "{\"version\":1,\"name\":\"child\"}"),
        new File("agents/README.md", "notes"));
  }

  ContainedApplications.Create request() {
    return new ContainedApplications.Create("c-1", UUID.randomUUID(), 5, null, List.of(), files());
  }

  /** Every directory the containment root holds, by name. */
  Set<String> staged() throws Exception {
    try (var entries = Files.list(root)) {
      return entries
          .map(path -> path.getFileName().toString())
          .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }
  }

  /**
   * The interruption is modelled where it actually bites: the durable unit refuses after the source
   * has been staged. Nothing durable is committed, and the staged bytes go with the refusal.
   */
  @Test
  void anInterruptedCreateLeavesNoProjectRow() throws Exception {
    when(records.create(any())).thenThrow(new CallerFault("the server stopped here"));
    assertThrows(CallerFault.class, () -> service.create("@service/parent", request()));
    assertTrue(work.rolledBack);
    assertFalse(work.committed);
    assertEquals(Set.of(), staged());
  }

  @Test
  void anInterruptedCreateLeavesNoLivePrincipal() throws Exception {
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
    when(principals.issue(any())).thenThrow(new CallerFault("the server stopped here"));
    assertThrows(CallerFault.class, () -> service.create("@service/parent", request()));
    // The principal insert and the record insert are the same unit of work, so a failure of either
    // leaves neither: there is no state in which a principal is live without its record.
    assertTrue(work.rolledBack);
    assertEquals(Set.of(), staged());
    verify(authority, never()).contain(any(), any(), any());
  }

  /**
   * Creation takes the container's own project row lock inside its durable unit, and takes it
   * <em>before</em> the insert. That is the row a parent revision change locks, so the two contend:
   * activation cannot count zero live children a moment before this row lands. The lock's actual
   * serialization is {@code ContainedConcurrencyTest}, which needs the real engine; what is
   * asserted here is that creation asks for it at all, and in the right order.
   */
  @Test
  void creationTakesTheContainerLockBeforeTheChildRowLands() {
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
    when(authority.contain(any(), any(), any())).thenReturn(parentAdmission);
    service.create("@service/parent", request());
    var order = inOrder(records);
    order.verify(records).lockContainer(parentScope.id());
    order.verify(records).create(any());
  }

  @Test
  void sweepCollectsAStageWithNoCommittedChild() throws Exception {
    String orphan = UUID.randomUUID().toString();
    Files.createDirectories(root.resolve(orphan).resolve("revisions").resolve("r"));
    Files.writeString(root.resolve(orphan).resolve("revisions").resolve("r").resolve("x"), "bytes");
    when(records.committed("contained:" + orphan)).thenReturn(false);
    var swept = collector.sweep();
    assertEquals(1, swept.collected());
    assertEquals(0, swept.retained());
    assertEquals(Set.of(), staged());
    // Observable: a sweep writes the same collection record a destruction does, already marked,
    // because the bytes are gone by the time it is written.
    verify(records)
        .collected(
            argThat(
                entry ->
                    entry.project().equals("contained:" + orphan)
                        && entry.cause() == ContainedApplicationRepository.Cause.SWEPT
                        && entry.container() == null));
  }

  @Test
  void sweepLeavesACommittedChildSourceAlone() throws Exception {
    String live = UUID.randomUUID().toString();
    Files.createDirectories(root.resolve(live).resolve("revisions").resolve("r"));
    when(records.committed("contained:" + live)).thenReturn(true);
    var swept = collector.sweep();
    assertEquals(0, swept.collected());
    assertEquals(1, swept.retained());
    assertEquals(Set.of(live), staged());
    verify(records, never()).collected(any());
  }

  /**
   * A destruction that committed but whose source removal was interrupted leaves an unmarked
   * collection row. That row is the sweep's precise work list, and finishing it is what makes "no
   * orphan revision directory survives" true across a crash rather than only across a refusal.
   */
  @Test
  void sweepFinishesASourceRemovalThatWasInterrupted() throws Exception {
    String collected = UUID.randomUUID().toString();
    Path workspace = root.resolve(collected);
    Files.createDirectories(workspace.resolve("revisions").resolve("r"));
    UUID child = UUID.randomUUID();
    when(records.uncollectedSources())
        .thenReturn(
            List.of(
                new ContainedApplicationRepository.Collection(
                    child,
                    parentScope.id(),
                    "contained:" + collected,
                    UUID.randomUUID(),
                    workspace,
                    ContainedApplicationRepository.Cause.DESTROYED)));
    var swept = collector.sweep();
    assertFalse(Files.exists(workspace));
    assertEquals(1, swept.collected());
    verify(records).sourceCollected(child);
    // And it did not write a second collection record for work already recorded.
    verify(records, never()).collected(any());
  }

  /**
   * A stored row is data, not a command. A collection record names the source area it accounts for,
   * and the sweep still resolves the directory from the project name under the configured root
   * rather than following that path: a row pointing anywhere else — a mis-migrated path, a row
   * written by an older binary, a path a future caller lets through — removes nothing.
   */
  @Test
  void sweepIgnoresAPendingPathOutsideTheContainmentRoot() throws Exception {
    Path outside = Files.createDirectories(directory.resolve("elsewhere").resolve("important"));
    Files.writeString(outside.resolve("keep.txt"), "not ours");
    UUID child = UUID.randomUUID();
    when(records.uncollectedSources())
        .thenReturn(
            List.of(
                new ContainedApplicationRepository.Collection(
                    child,
                    parentScope.id(),
                    "contained:not-a-uuid",
                    UUID.randomUUID(),
                    outside,
                    ContainedApplicationRepository.Cause.DESTROYED)));
    var swept = collector.sweep();
    assertEquals(0, swept.collected());
    assertTrue(Files.exists(outside.resolve("keep.txt")));
    verify(records, never()).sourceCollected(child);
  }

  /**
   * ADR 0005's retained release history is not this collector's to touch. The guarantee is
   * structural: the sweep enumerates the configured containment root and nothing else, and it has
   * no collaborator through which a release, a deployment head or a FileStore destination could be
   * reached. The assertion exercises both halves — a retained top-level revision tree sitting
   * beside the containment root is untouched, and a contained-looking directory planted inside the
   * top-level tree is never even enumerated.
   */
  @Test
  void sweepLeavesTopLevelRetainedRevisionsAlone() throws Exception {
    Path releases = Files.createDirectories(directory.resolve("filestore").resolve("app"));
    Path retained = Files.createDirectories(releases.resolve("revisions").resolve("rev-1"));
    Files.writeString(retained.resolve("plowshare.json"), "{\"version\":1,\"name\":\"app\"}");
    // A directory inside the top-level tree whose name would pass for a contained project.
    Path decoy = Files.createDirectories(releases.resolve(UUID.randomUUID().toString()));
    when(records.committed(any())).thenReturn(false);
    var swept = collector.sweep();
    assertEquals(0, swept.collected());
    assertTrue(Files.isDirectory(retained));
    assertTrue(Files.exists(retained.resolve("plowshare.json")));
    assertTrue(Files.isDirectory(decoy));
    // Nothing was asked about a project outside the containment root, so nothing could have been
    // decided about one.
    verify(records, never())
        .committed(argThat(name -> name.contains(decoy.getFileName().toString())));
    for (java.lang.reflect.Field field : ContainedSourceCollector.class.getDeclaredFields())
      assertNotEquals(
          io.aeyer.plowshare.server.archive.ApplicationDeploymentStore.class, field.getType());
  }

  /** An unconfigured server has no containment root, so the sweep refuses to guess one. */
  @Test
  void sweepDoesNothingWhenNestingIsNotConfigured() {
    var unconfigured = new ContainedSourceCollector(records, work, ContainmentConfig.absent());
    var swept = unconfigured.sweep();
    assertEquals(0, swept.collected());
    assertEquals(0, swept.retained());
    verifyNoInteractions(records);
  }
}
