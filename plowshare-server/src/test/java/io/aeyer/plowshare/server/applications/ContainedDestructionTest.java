package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.allowances.*;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.AccountKnowledgePermissions;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Destruction, and the fence that keeps a parent revision change from orphaning what it authorized.
 *
 * <p>Every collaborator here is a mocked interface or a small in-memory fake, per the database test
 * policy. What genuinely needs PostgreSQL — the two-hop cascade, the project row lock, and
 * concurrent creation against activation — is {@code ContainedConcurrencyTest}, which states its
 * database-specific reason.
 *
 * <p>Ordering is the contract that matters most here and is asserted rather than described: a
 * descendant's rows go before its container's, because {@code authority_scopes.container} is {@code
 * ON DELETE RESTRICT} and a parent-first delete would be refused by the schema; and the staged
 * source is removed only <em>after</em> the durable unit commits, because the filesystem cannot
 * join the transaction and a directory outliving a rolled-back delete is collectable while a
 * deleted directory under a surviving record is not.
 */
class ContainedDestructionTest {
  @TempDir Path directory;

  ContainedApplicationRepository records = mock(ContainedApplicationRepository.class);
  ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  ApplicationPackageValidator validator = mock(ApplicationPackageValidator.class);
  OperationAuthority authority = mock(OperationAuthority.class);
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
              new OperationAuthority.Support(AuthorityAction.WRITE, UUID.randomUUID())));

  Path root;
  SourceContainedApplications service;

  /** The child and the grandchild beneath it, as the records read them back. */
  ContainedApplicationRepository.Retained child;

  ContainedApplicationRepository.Retained grandchild;

  /** Ceilings are real, because "revoking stops descendant work" is asserted on them. */
  static final class Allowances implements AllowanceRepository {
    final Map<AllowanceId, AllowanceSnapshot> held = new LinkedHashMap<>();

    @Override
    public void retain(AllowanceSnapshot selected) {
      held.put(selected.id(), selected);
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

  /**
   * Records whether the durable unit committed, which the source removal order rests on.
   *
   * <p>{@code failOnCommit} models the case Finding A is about: the body succeeded and the commit
   * reported a failure, so the outcome is <em>unknown</em> rather than rolled back. It is thrown
   * from {@code inTransaction} and not from the body, which is exactly the distinction the
   * production code now draws.
   */
  static final class Work implements UnitOfWork {
    boolean rolledBack;
    boolean committed;
    boolean inside;
    RuntimeException failOnCommit;

    @Override
    public <T> T inTransaction(java.util.function.Supplier<T> body) {
      inside = true;
      try {
        T answer = body.get();
        if (failOnCommit != null) throw failOnCommit;
        committed = true;
        return answer;
      } catch (RuntimeException failed) {
        if (failed != failOnCommit) rolledBack = true;
        throw failed;
      } finally {
        inside = false;
      }
    }
  }

  @BeforeEach
  void setup() throws Exception {
    root = Files.createDirectories(directory.resolve("containment"));
    allowances.retain(new AllowanceSnapshot(parentBudget, 100, 10));
    when(authority.conversation("c-1"))
        .thenReturn(
            Optional.of(
                new OperationAuthority.Conversation(
                    "c-1", "worker", parentAdmission, null, parentBudget, "0".repeat(64))));
    child = retained(parentScope.id(), 11);
    grandchild = retained(child.scope(), 12);
    when(records.find(child.id())).thenReturn(Optional.of(child));
    when(records.find(grandchild.id())).thenReturn(Optional.of(grandchild));
    // Deepest first, which is the order the RESTRICT on authority_scopes.container requires.
    when(records.subtree(child.scope())).thenReturn(List.of(grandchild, child));
    when(records.subtree(grandchild.scope())).thenReturn(List.of(grandchild));
    service =
        new SourceContainedApplications(
            records,
            projects,
            validator,
            authority,
            principals,
            allowances,
            work,
            new ContainmentConfig(root.toString(), 3, 4, null),
            new InProcessContainedStaging());
  }

  /** A retained child whose staged source really exists under the containment root. */
  ContainedApplicationRepository.Retained retained(ScopeId container, long projectId)
      throws Exception {
    String project = "contained:" + UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    var allowance = new AllowanceId(UUID.randomUUID());
    allowances.retain(new AllowanceSnapshot(allowance, 5, 1));
    Path workspace = root.resolve(project.substring("contained:".length()));
    Files.createDirectories(workspace.resolve("revisions").resolve(revision.toString()));
    Files.writeString(
        workspace.resolve("revisions").resolve(revision.toString()).resolve("plowshare.json"),
        "{\"version\":1,\"name\":\"" + project + "\"}");
    return new ContainedApplicationRepository.Retained(
        UUID.randomUUID(),
        new ScopeId(UUID.randomUUID()),
        projectId,
        project,
        container,
        UUID.randomUUID(),
        "a".repeat(64),
        revision,
        allowance,
        5,
        true);
  }

  /**
   * A service wired for the creation path, which the destruction fixture otherwise has no need of.
   * The parent's manifest declares nesting and its workspace exists, so a create gets as far as
   * staging and then as far as the unit of work.
   */
  SourceContainedApplications creator(ContainedStaging claims, Work unit) {
    try {
      Path parentWorkspace = Files.createDirectories(directory.resolve("parent-source"));
      Files.writeString(
          parentWorkspace.resolve("plowshare.json"),
          "{\"version\":1,\"name\":\"parent\",\"nesting\":true}");
      when(projects.find("parent"))
          .thenReturn(
              Optional.of(
                  new io.aeyer.plowshare.server.archive.ProjectRecord(
                      "parent", parentWorkspace, List.of(), List.of(), "MANAGED", List.of())));
      when(projects.effectiveExclusions(any())).thenReturn(List.of());
      when(records.receipt(any(), any())).thenReturn(Optional.empty());
      when(records.liveDescendants(parentScope.id())).thenReturn(0);
      when(records.create(any()))
          .thenAnswer(
              call -> {
                ContainedApplicationRepository.Creation made = call.getArgument(0);
                return new ContainedApplicationRepository.Retained(
                    UUID.randomUUID(),
                    new ScopeId(UUID.randomUUID()),
                    99,
                    made.project(),
                    made.container(),
                    made.requestId(),
                    made.fingerprint(),
                    made.revision(),
                    made.allowance(),
                    made.ceiling(),
                    true);
              });
      when(principals.issue(any()))
          .thenAnswer(
              call ->
                  new ContainedPrincipals.ContainedPrincipal(
                      UUID.randomUUID(), ((AuthorityScope.Contained) call.getArgument(0)).id()));
      when(authority.contain(any(), any(), any())).thenReturn(parentAdmission);
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
    return new SourceContainedApplications(
        records,
        projects,
        validator,
        authority,
        principals,
        allowances,
        unit,
        new ContainmentConfig(root.toString(), 3, 4, null),
        claims);
  }

  ContainedApplications.Create creation() {
    return new ContainedApplications.Create(
        "c-1",
        UUID.randomUUID(),
        1,
        null,
        List.of(),
        List.of(
            new io.aeyer.plowshare.protocol.ApplicationDeployment.File(
                "plowshare.json", "{\"version\":1,\"name\":\"child\"}"),
            new io.aeyer.plowshare.protocol.ApplicationDeployment.File("agents/README.md", "x")));
  }

  /** Directories under the containment root that a creation staged, excluding the two fixtures. */
  Set<String> stagedProjects() {
    try (var entries = Files.list(root)) {
      var fixtures =
          Set.of(
              child.project().substring("contained:".length()),
              grandchild.project().substring("contained:".length()));
      return entries
          .map(path -> path.getFileName().toString())
          .filter(name -> !fixtures.contains(name))
          .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    } catch (java.io.IOException unreadable) {
      throw new IllegalStateException(unreadable);
    }
  }

  Path workspace(ContainedApplicationRepository.Retained held) {
    return root.resolve(held.project().substring("contained:".length()));
  }

  @Test
  void destroyingAParentDestroysItsDescendants() {
    var destroyed = service.destroy("@service/parent", "c-1", child.id());
    assertEquals(List.of(grandchild.id(), child.id()), destroyed.collected());
    assertEquals(child.id(), destroyed.child());
    // Deepest first: a container's rows cannot go before the descendant that references them.
    var order = inOrder(records);
    order.verify(records).destroy(grandchild.id());
    order.verify(records).destroy(child.id());
    assertTrue(work.committed);
    assertFalse(work.rolledBack);
  }

  @Test
  void destroyingAChildRevokesItsPrincipal() {
    service.destroy("@service/parent", "c-1", child.id());
    // Revocation names the full contained scope -- id, project and container -- so the repository
    // checks the claim against the authority record exactly as issuing does.
    verify(principals)
        .revoke(new AuthorityScope.Contained(child.scope(), child.projectId(), child.container()));
    verify(principals)
        .revoke(
            new AuthorityScope.Contained(
                grandchild.scope(), grandchild.projectId(), grandchild.container()));
  }

  @Test
  void destroyingAChildCollectsItsSourceDirectory() {
    assertTrue(Files.isDirectory(workspace(child)));
    assertTrue(Files.isDirectory(workspace(grandchild)));
    var destroyed = service.destroy("@service/parent", "c-1", child.id());
    assertFalse(Files.exists(workspace(child)));
    assertFalse(Files.exists(workspace(grandchild)));
    assertEquals(2, destroyed.sources());
    // Observable, and in both directions: a collection record is written inside the durable unit
    // and then marked once the bytes are gone, so a row that is not marked is exactly the work a
    // sweep has left to do.
    verify(records).collected(argThat(entry -> entry.child().equals(child.id())));
    verify(records).sourceCollected(child.id());
    verify(records).sourceCollected(grandchild.id());
  }

  /**
   * The source is removed only after the durable unit commits. A rolled-back destruction that had
   * already deleted the bytes would leave a live record pointing at nothing, which no sweep can
   * repair; a committed destruction whose removal is interrupted leaves an unmarked collection row,
   * which a sweep collects.
   */
  @Test
  void aRefusedDestructionLeavesTheSourceOnDisk() {
    doThrow(new CallerFault("durable refusal")).when(records).destroy(child.id());
    assertThrows(CallerFault.class, () -> service.destroy("@service/parent", "c-1", child.id()));
    assertTrue(work.rolledBack);
    assertTrue(Files.isDirectory(workspace(child)));
    assertTrue(Files.isDirectory(workspace(grandchild)));
    verify(records, never()).sourceCollected(any());
  }

  @Test
  void destructionRefusesAnApplicationTheCallerDoesNotContain() {
    var stranger = new ScopeId(UUID.randomUUID());
    when(records.find(child.id()))
        .thenReturn(
            Optional.of(
                new ContainedApplicationRepository.Retained(
                    child.id(),
                    child.scope(),
                    child.projectId(),
                    child.project(),
                    stranger,
                    child.requestId(),
                    child.fingerprint(),
                    child.revision(),
                    child.allowance(),
                    child.ceiling(),
                    true)));
    assertEquals(
        "This Application does not contain that Application",
        assertThrows(CallerFault.class, () -> service.destroy("@service/parent", "c-1", child.id()))
            .getMessage());
    verify(records, never()).destroy(any());
    verifyNoInteractions(principals);
    assertTrue(Files.isDirectory(workspace(child)));
  }

  /**
   * Retained evidence is not a live permission. A conversation whose parent authority has since
   * been revoked destroys nothing, which is the same refusal creation takes.
   */
  @Test
  void destructionRefusesWhenTheParentAuthorityIsNoLongerLive() {
    doThrow(new CallerFault("denied")).when(authority).require(parentAdmission);
    assertThrows(CallerFault.class, () -> service.destroy("@service/parent", "c-1", child.id()));
    verify(records, never()).destroy(any());
    assertTrue(Files.isDirectory(workspace(child)));
  }

  /**
   * Revoking the parent's authority stops descendant work, and it does so through the parent's own
   * grants rather than through anything stored against the child: a contained admission's live
   * check walks its container chain to the nearest enclosing uncontained scope and asks there. This
   * exercises the real {@code PinnedOperationAuthority} rather than the mocked authority above,
   * because the mechanism being asserted is that walk.
   */
  @Test
  void revokingParentAuthorityMidExecutionStopsDescendantWork() {
    var identities = mock(IdentityRepository.class);
    var grants = mock(ScopedGrantRepository.class);
    var scoped = mock(ScopedAuthority.class);
    var members = mock(ProjectMembers.class);
    var operations = mock(OperationRecords.class);
    var resources = mock(ResourceScopeRepository.class);
    var policy =
        new PinnedOperationAuthority(
            identities,
            grants,
            scoped,
            members,
            operations,
            resources,
            AccountKnowledgePermissions.DENY,
            new ContainmentBounds(2, 4));
    var container = new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 1);
    var confined = new AuthorityScope.Contained(new ScopeId(UUID.randomUUID()), 2, container.id());
    Home confinedHome = Home.of("contained:" + UUID.randomUUID());
    UUID grant = UUID.randomUUID();
    var holder = new AccountId(UUID.randomUUID());
    var subject = GrantRecipient.account(holder);
    when(identities.account("alice"))
        .thenReturn(
            new IdentityRepository.Account(
                holder, "alice", IdentityRepository.AccountKind.USER, false));
    when(identities.project("one")).thenReturn(container);
    when(identities.home(container)).thenReturn(Home.of("one"));
    when(identities.scope(container.id())).thenReturn(container);
    when(identities.containedHome(confined, container.id())).thenReturn(confinedHome);
    when(identities.contained(confined.id())).thenReturn(Optional.of(confined));
    // Faithful to JdbcIdentityRepository: a contained scope is never resolvable from a project
    // name, so the live-authority path cannot reach one through this read.
    doThrow(new CallerFault("A contained scope has no addressable project home"))
        .when(identities)
        .project(argThat(name -> name != null && name.startsWith("contained:")));
    when(members.authorityAccount("alice")).thenReturn(Optional.of("alice"));
    when(members.role("one", "alice")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    var binding = mock(ScopedGrantRepository.Binding.class);
    when(binding.id()).thenReturn(grant);
    when(grants.live(subject)).thenReturn(List.of(binding));
    when(scoped.allowsUsing(subject, container, AuthorityAction.RUN_MODEL, grant)).thenReturn(true);

    var holderAdmission = policy.admit("alice", container, Set.of(AuthorityAction.RUN_MODEL));
    var descendant = policy.contain(holderAdmission, confined, Set.of(AuthorityAction.RUN_MODEL));
    when(operations.parent(descendant.id())).thenReturn(Optional.of(holderAdmission.id()));
    when(operations.find(holderAdmission.id())).thenReturn(Optional.of(holderAdmission));
    assertDoesNotThrow(() -> policy.require(descendant));
    // Mid-execution revocation of the PARENT's grant. Nothing about the child row changes.
    when(scoped.allowsUsing(subject, container, AuthorityAction.RUN_MODEL, grant))
        .thenReturn(false);
    assertThrows(CallerFault.class, () -> policy.require(descendant));
  }

  @Test
  void parentActivationRefusesWhileAChildIsLive() {
    var fence = new SourceContainmentFence(records);
    when(records.liveChildrenOfProject("parent")).thenReturn(1);
    assertEquals(
        "This Application holds a live contained Application; destroy it before changing the"
            + " revision that authorized it",
        assertThrows(CallerFault.class, () -> fence.requireNoLiveChildren("parent")).getMessage());
    when(records.liveChildrenOfProject("parent")).thenReturn(0);
    assertDoesNotThrow(() -> fence.requireNoLiveChildren("parent"));
  }

  /**
   * The fence fails closed, and it structurally cannot do anything else: it holds the repository
   * and nothing through which a destruction, a principal revocation or a filesystem removal could
   * be reached. "Activation does not destroy a live child" is therefore a property of the object,
   * not a behaviour a later edit could quietly change.
   */
  @Test
  void parentActivationDoesNotDestroyALiveChild() {
    var fence = new SourceContainmentFence(records);
    when(records.liveChildrenOfProject("parent")).thenReturn(2);
    assertThrows(CallerFault.class, () -> fence.requireNoLiveChildren("parent"));
    verify(records, never()).destroy(any());
    verify(records, never()).collected(any());
    verifyNoInteractions(principals);
    for (Field field : SourceContainmentFence.class.getDeclaredFields())
      assertEquals(
          ContainedApplicationRepository.class,
          field.getType(),
          "The activation fence holds only the containment read: " + field);
  }

  /**
   * ADR 0005's release history is not this capability's to write. A destroyed child leaves no
   * retained release and no deployment head, because nothing on this path can reach either: the
   * service holds no {@code ApplicationDeploymentStore} and no {@code ApplicationDeployments}.
   * Promotion is a separate explicit act and is out of this slice.
   */
  @Test
  void aDestroyedChildIsNotPromotedToARetainedRelease() {
    service.destroy("@service/parent", "c-1", child.id());
    for (Field field : SourceContainedApplications.class.getDeclaredFields()) {
      assertNotEquals(
          io.aeyer.plowshare.server.archive.ApplicationDeploymentStore.class, field.getType());
      assertNotEquals(ApplicationDeployments.class, field.getType());
    }
    // And the collection record carries the revision as history rather than as a release: it has
    // no digest, no file count and no destination, which is what a retained release is made of.
    var names =
        Arrays.stream(ContainedApplicationRepository.Collection.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();
    assertFalse(names.contains("digest"));
    assertFalse(names.contains("fileCount"));
    assertFalse(names.contains("destination"));
  }

  /**
   * The removal is marked only when the bytes are actually gone. A tree that cannot be removed —
   * here because its own directory is not writable — leaves the collection record unmarked, and an
   * unmarked record is exactly what the sweep picks up. Claiming a removal that did not happen
   * would lose that recovery, which is the only thing making an interrupted destruction repairable.
   */
  @Test
  void aSourceRemovalThatCannotFinishLeavesTheCollectionRecordUnmarked() throws Exception {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "Permission-based interruption needs POSIX attributes");
    Path held = workspace(child);
    var original = Files.getPosixFilePermissions(held);
    Files.setPosixFilePermissions(
        held, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
    try {
      var destroyed = service.destroy("@service/parent", "c-1", child.id());
      assertTrue(Files.isDirectory(held), "the unwritable tree survives");
      // The grandchild's source was removable, so exactly one of the two is accounted for.
      assertEquals(1, destroyed.sources());
      assertEquals(2, destroyed.collected().size());
      verify(records, never()).sourceCollected(child.id());
      verify(records).sourceCollected(grandchild.id());
      // Both collection records were still written, so neither child is unaccounted for.
      verify(records, times(2)).collected(any());
    } finally {
      Files.setPosixFilePermissions(held, original);
    }
  }

  /**
   * Fix round 1, Finding B. ADR 0005's rule for this family is that delivery uncertainty is
   * resolved by inspection and never by automatic replay, and {@code create} carries a request-UUID
   * receipt for exactly that. {@code destroy} resolves it the other way, against durable evidence
   * it already writes: a retry for a child this caller destroyed succeeds and replays the same
   * answer, because the collection record says the destruction happened and whose child it was.
   *
   * <p>The answer is reconstructed and not invented: the subtree is rebuilt by walking collection
   * rows down from the named child's recorded scope, so {@code collected} is the same list in the
   * same deepest-first order the original gave.
   */
  @Test
  void aRetriedDestroyAfterALostResponseSucceedsFromItsCollectionRecord() {
    destroyed(child, grandchild);
    var replay = service.destroy("@service/parent", "c-1", child.id());
    assertEquals(List.of(grandchild.id(), child.id()), replay.collected());
    assertEquals(child.id(), replay.child());
    assertEquals(2, replay.sources());
    // Nothing was destroyed a second time, and no principal was revoked again.
    verify(records, never()).destroy(any());
    verify(records, never()).collected(any());
    verifyNoInteractions(principals);
  }

  /**
   * The subtree a destruction acts on is read under the same lock creation takes, inside the unit
   * of work. Read outside it, a nested create committing in that window left the cascade to meet an
   * FK RESTRICT — a server fault for a conflict creation answers with a plain refusal.
   */
  @Test
  void destructionLocksTheSubtreeAndReadsItInsideTheUnitOfWork() {
    var inside = new ArrayList<Boolean>();
    when(records.subtree(child.scope()))
        .thenAnswer(
            call -> {
              inside.add(work.inside);
              return List.of(grandchild, child);
            });
    service.destroy("@service/parent", "c-1", child.id());
    assertEquals(List.of(true), inside, "the subtree is read inside the durable unit");
    var order = inOrder(records);
    order.verify(records).lockSubtree(child.scope());
    order.verify(records).subtree(child.scope());
  }

  /**
   * And the genuine race refuses. A nested create that wins the lock leaves a row referencing a
   * scope this cascade is deleting, which {@code authority_scopes.container}'s RESTRICT turns into
   * a {@code DataIntegrityViolationException}. Creation already answers that class of conflict with
   * a {@code CallerFault}; destruction answering it with a 500 was the inconsistency.
   */
  @Test
  void aConcurrentNestedCreateMakesDestructionRefuseRatherThanFail() {
    doThrow(
            new org.springframework.dao.DataIntegrityViolationException(
                "update or delete on table \"authority_scopes\" violates foreign key constraint"))
        .when(records)
        .destroy(child.id());
    var refused =
        assertThrows(
            CallerFault.class, () -> service.destroy("@service/parent", "c-1", child.id()));
    assertTrue(refused.getMessage().contains("concurrently"), refused.getMessage());
    // The unit rolled back and the bytes are untouched, so nothing is half-destroyed.
    assertTrue(work.rolledBack);
    assertTrue(Files.isDirectory(workspace(child)));
    assertTrue(Files.isDirectory(workspace(grandchild)));
    verify(records, never()).sourceCollected(any());
  }

  /**
   * A replay answers for the destruction that wrote the records and no other. A grandchild
   * destroyed earlier in its own separate destruction leaves a collection row on the same
   * containment edge, and including it would make a retry report a longer {@code collected} list
   * and a different {@code sources} than the original answer — the exact idempotency claim this
   * contract was accepted on instead of a second request-UUID receipt.
   */
  @Test
  void aReplayAnswersOnlyTheRecordsThatDestructionWrote() throws Exception {
    destroyed(child, grandchild);
    // An earlier, separate destruction of a third child under the same scope. Its root is itself,
    // so it belongs to no later destruction's answer.
    var earlier = retained(child.scope(), 13);
    when(records.collectedUnder(child.scope(), earlier.id()))
        .thenReturn(List.of(collection(earlier, child.scope(), earlier.id(), true)));
    var replay = service.destroy("@service/parent", "c-1", child.id());
    assertEquals(List.of(grandchild.id(), child.id()), replay.collected());
    assertEquals(2, replay.sources());
    // The walk was scoped to this destruction's own root at every edge it descended.
    verify(records).collectedUnder(child.scope(), child.id());
    verify(records).collectedUnder(grandchild.scope(), child.id());
    verify(records, never()).collectedUnder(any(), eq(earlier.id()));
  }

  /**
   * "Already destroyed, and it was yours" is distinguished from "not yours". The collection record
   * retains the container as a plain value precisely so this question is still answerable after the
   * scope it names has been cascaded away.
   */
  @Test
  void aRetriedDestroyRefusesAChildThatWasNotYours() {
    destroyed(child, grandchild);
    var stranger = new ScopeId(UUID.randomUUID());
    when(records.collection(child.id())).thenReturn(Optional.of(collection(child, stranger, true)));
    assertEquals(
        "This Application does not contain that Application",
        assertThrows(CallerFault.class, () -> service.destroy("@service/parent", "c-1", child.id()))
            .getMessage());
    verify(records, never()).destroy(any());
  }

  /** And from "never existed", which has no record of any kind. */
  @Test
  void aRetriedDestroyRefusesAChildThatNeverExisted() {
    when(records.find(any())).thenReturn(Optional.empty());
    when(records.collection(any())).thenReturn(Optional.empty());
    assertEquals(
        "No such contained Application",
        assertThrows(
                CallerFault.class,
                () -> service.destroy("@service/parent", "c-1", UUID.randomUUID()))
            .getMessage());
    // Authority is still proved before the collection history is read, so a refusal learns nothing.
    verify(authority).require(parentAdmission);
  }

  /**
   * A retry is also how an interrupted removal finishes, so calling twice is not merely safe but
   * useful: the second call collects the bytes the first could not and marks the record.
   */
  @Test
  void aRetriedDestroyFinishesAnInterruptedSourceRemoval() {
    destroyed(child, grandchild);
    when(records.collection(child.id()))
        .thenReturn(Optional.of(collection(child, parentScope.id(), false)));
    assertTrue(Files.isDirectory(workspace(child)));
    var replay = service.destroy("@service/parent", "c-1", child.id());
    assertFalse(Files.exists(workspace(child)));
    assertEquals(2, replay.sources());
    verify(records).sourceCollected(child.id());
  }

  /**
   * Puts the records in the state a committed destruction leaves: no live row, a collection row.
   */
  void destroyed(
      ContainedApplicationRepository.Retained named,
      ContainedApplicationRepository.Retained beneath) {
    when(records.find(any())).thenReturn(Optional.empty());
    when(records.collection(named.id()))
        .thenReturn(Optional.of(collection(named, named.container(), true)));
    // Rows written by the destruction the named child rooted, which is the scope a replay walks.
    when(records.collectedUnder(named.scope(), named.id()))
        .thenReturn(List.of(collection(beneath, named.scope(), named.id(), true)));
    when(records.collectedUnder(beneath.scope(), named.id())).thenReturn(List.of());
  }

  /** A record of the destruction the child itself rooted: the normal case for a replayable row. */
  ContainedApplicationRepository.Collection collection(
      ContainedApplicationRepository.Retained held, ScopeId container, boolean removed) {
    return collection(held, container, held.id(), removed);
  }

  ContainedApplicationRepository.Collection collection(
      ContainedApplicationRepository.Retained held, ScopeId container, UUID root, boolean removed) {
    return new ContainedApplicationRepository.Collection(
        held.id(),
        held.scope(),
        container,
        root,
        held.project(),
        held.revision(),
        workspace(held),
        ContainedApplicationRepository.Cause.DESTROYED,
        removed);
  }

  /**
   * Fix round 3, Finding A — the inverse orphan, which is the one direction ADR 0042 calls
   * unrecoverable.
   *
   * <p>A failure thrown out of {@code work.inTransaction} <em>itself</em> is a failure of the
   * commit, and a commit that reports failure may still have committed. Collecting the staged
   * source on that would leave a live child row whose source is gone: {@code committed(project)}
   * answers true, so the sweep neither collects it nor repairs it, and no record says anything is
   * wrong. Leaving the bytes is safe in both branches and requires distinguishing nothing — if it
   * committed they are the child's correct source, and if it did not there is no row, so the sweep
   * collects them.
   *
   * <p>A failure thrown by the transactional <em>body</em> is the opposite case: the unit of work
   * will roll back, so the bytes are collected there, which is where the pre-existing refusal
   * totality tests already exercise it.
   */
  @Test
  void anAmbiguousCommitLeavesTheStagedSourceOnDisk() {
    var staging = new InProcessContainedStaging();
    var creating = creator(staging, work);
    work.failOnCommit = new org.springframework.transaction.TransactionSystemException("uncertain");
    assertThrows(
        org.springframework.transaction.TransactionSystemException.class,
        () -> creating.create("@service/parent", creation()));
    // The bytes survive, because the row may exist. A sweep will not touch them if it does, and
    // will collect them if it does not.
    assertFalse(stagedProjects().isEmpty(), "an ambiguous commit leaves its staged source");
    // And the claim is still released, so the stage is not exempt from collection forever.
    assertEquals(Set.of(), staging.claimed());
  }

  /**
   * The other side of the same ordering, asserted here rather than only in {@code
   * ContainedRecoveryTest}: a failure thrown by the body is a definite rollback, so its bytes go.
   */
  @Test
  void aFailureInsideTheTransactionalBodyStillCollectsItsStagedSource() {
    var staging = new InProcessContainedStaging();
    var creating = creator(staging, work);
    doThrow(new CallerFault("the body refused")).when(records).create(any());
    assertThrows(CallerFault.class, () -> creating.create("@service/parent", creation()));
    assertEquals(Set.of(), stagedProjects());
    assertEquals(Set.of(), staging.claimed());
  }

  /**
   * Fix round 3, Finding C. The fence was a nullable collaborator guarded by a null check, so a
   * silently fenceless production store was one constructor call away — and the fence is what stops
   * a child outliving the revision that authorized it. It is required now, and the absence has a
   * name, so omitting it is a visible choice rather than a default nobody reads.
   */
  @Test
  void theDeploymentStoreCannotBeWiredWithoutAContainmentFence() {
    var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    var unit = mock(UnitOfWork.class);
    var servers = mock(io.aeyer.plowshare.server.archive.ServerProjects.class);
    var schedules = mock(io.aeyer.plowshare.server.events.ScheduleDefinitionStore.class);
    assertThrows(
        NullPointerException.class,
        () ->
            new io.aeyer.plowshare.server.archive.JdbcApplicationDeploymentStore(
                jdbc, unit, servers, schedules, null, null, null));
    // The named absence is constructible and refuses nothing, which is what makes it a choice.
    var unfenced =
        new io.aeyer.plowshare.server.archive.JdbcApplicationDeploymentStore(
            jdbc, unit, servers, schedules, null, null, ContainmentFence.unfenced());
    assertNotNull(unfenced);
    assertDoesNotThrow(() -> ContainmentFence.unfenced().requireNoLiveChildren("anything"));
    // And the shorter overloads are not a back door: they supply the named absence, not a null.
    assertNotNull(
        new io.aeyer.plowshare.server.archive.JdbcApplicationDeploymentStore(
            jdbc, unit, servers, schedules));
  }

  /** Zeroing precedes deletion, so a descendant in flight cannot claim another call. */
  @Test
  void destructionZeroesEveryDescendantAllowanceBeforeItsRowsGo() {
    assertTrue(allowances.claim(grandchild.allowance(), 1, null).isPresent());
    service.destroy("@service/parent", "c-1", child.id());
    assertEquals(0, allowances.find(child.allowance()).orElseThrow().ceiling());
    assertEquals(0, allowances.find(grandchild.allowance()).orElseThrow().ceiling());
    assertTrue(allowances.claim(grandchild.allowance(), 1, null).isEmpty());
  }
}
