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
import java.time.Duration;
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
  InProcessContainedStaging staging = new InProcessContainedStaging();
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
    config = new ContainmentConfig(root.toString(), 2, 4, null);
    service =
        new SourceContainedApplications(
            records, projects, validator, authority, principals, allowances, work, config, staging);
    // Duration.ZERO grace in the tests that are not about the grace, so every other assertion is
    // about what the sweep decides rather than about a clock.
    collector = new ContainedSourceCollector(records, work, config, staging, Duration.ZERO);
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
                    new ScopeId(UUID.randomUUID()),
                    parentScope.id(),
                    "contained:" + collected,
                    UUID.randomUUID(),
                    workspace,
                    ContainedApplicationRepository.Cause.DESTROYED,
                    false)));
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
                    new ScopeId(UUID.randomUUID()),
                    parentScope.id(),
                    "contained:not-a-uuid",
                    UUID.randomUUID(),
                    outside,
                    ContainedApplicationRepository.Cause.DESTROYED,
                    false)));
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

  /**
   * Fix round 1, Finding A. A sweep that collected a stage a live create is still writing would be
   * worse than the leak it exists to close, so the claim is exact and not a guess: {@code create}
   * holds a staging claim on the project from before the first byte until its unit of work has
   * finished, and the sweep skips a claimed project whatever its record or its age says.
   */
  @Test
  void sweepDoesNotCollectAStageAnInFlightCreateIsStillWriting() throws Exception {
    String inflight = UUID.randomUUID().toString();
    Files.createDirectories(root.resolve(inflight).resolve("revisions").resolve("r"));
    when(records.committed("contained:" + inflight)).thenReturn(false);
    staging.claim("contained:" + inflight);
    var swept = collector.sweep();
    assertEquals(0, swept.collected());
    assertEquals(1, swept.retained());
    assertEquals(Set.of(inflight), staged());
    // Released, and the same sweep now collects it: the claim is a window, not an exemption.
    staging.release("contained:" + inflight);
    assertEquals(1, collector.sweep().collected());
    assertEquals(Set.of(), staged());
  }

  /**
   * The claim is exact only inside one process. Across processes the bound is the directory's own
   * age: a configured grace below which a stage is assumed to belong to a create that is still
   * running. The grace errs long on purpose -- too long leaks for one more interval, too short
   * collects live work -- and {@code ContainmentConfig} floors it for the same reason.
   */
  @Test
  void sweepDoesNotCollectADirectoryYoungerThanTheGrace() throws Exception {
    String young = UUID.randomUUID().toString();
    Files.createDirectories(root.resolve(young).resolve("revisions").resolve("r"));
    when(records.committed("contained:" + young)).thenReturn(false);
    var patient = new ContainedSourceCollector(records, work, config, staging, Duration.ofHours(1));
    var swept = patient.sweep();
    assertEquals(0, swept.collected());
    assertEquals(1, swept.retained());
    assertEquals(Set.of(young), staged());
    // An older directory under the same grace is collected, so the grace is an age and not a stop.
    Files.setLastModifiedTime(
        root.resolve(young),
        java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(7200)));
    assertEquals(1, patient.sweep().collected());
    // And the floor is in the configuration, so a zero cannot be configured into production.
    assertEquals(
        Duration.ofMinutes(1), new ContainmentConfig(root.toString(), 2, 4, "PT0S").sweepGrace());
    assertEquals(
        Duration.ofMinutes(30), new ContainmentConfig(root.toString(), 2, 4, null).sweepGrace());
  }

  /**
   * A creation holds its claim across the whole staging-and-commit window, including a refusal: the
   * claim is released in a {@code finally}, so a refused create cannot leave a project permanently
   * exempt from collection.
   */
  @Test
  void creationHoldsAndReleasesItsStagingClaim() {
    when(records.create(any())).thenThrow(new CallerFault("the server stopped here"));
    assertThrows(CallerFault.class, () -> service.create("@service/parent", request()));
    assertEquals(Set.of(), staging.claimed());
  }

  /**
   * Finding A's other half: the sweep now has production callers. Startup reconciliation answers
   * the crash that happened while the server was down, and the repeating sweep answers the crash
   * that happens while it is up. Both run through one object, so there is one place that holds the
   * readiness gate -- nothing sweeps before the context is ready.
   */
  @Test
  void startupAndTheRepeatingSweepBothCollect() {
    var sweeps = new ContainedCollectionSweeps(collector);
    sweeps.sweep();
    verifyNoInteractions(records);
    sweeps.onApplicationEvent(
        new org.springframework.boot.context.event.ApplicationReadyEvent(
            new org.springframework.boot.SpringApplication(), new String[0], null, Duration.ZERO));
    verify(records, times(1)).uncollectedSources();
    sweeps.sweep();
    verify(records, times(2)).uncollectedSources();
  }

  /** An unconfigured server has no containment root, so the sweep refuses to guess one. */
  @Test
  void sweepDoesNothingWhenNestingIsNotConfigured() {
    var unconfigured =
        new ContainedSourceCollector(
            records, work, ContainmentConfig.absent(), staging, Duration.ZERO);
    var swept = unconfigured.sweep();
    assertEquals(0, swept.collected());
    assertEquals(0, swept.retained());
    verifyNoInteractions(records);
  }
}
