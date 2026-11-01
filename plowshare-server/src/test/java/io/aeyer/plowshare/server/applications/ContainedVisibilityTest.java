package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.allowances.*;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.information.InformationSql;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * One-way visibility: a container peers into what it contains, and nothing contained peers out.
 *
 * <p>Every collaborator here is a mocked interface or a small in-memory fake. Nothing asserted
 * below is PostgreSQL behaviour: the read-ceiling assertions in this class are about which {@code
 * KnowledgeReadScope} a contained admission is clamped to and that the clamp can only ever narrow,
 * which is a value decision. What the clamped ceiling then <em>admits</em> is a SQL decision, and
 * that is {@code ContainedKnowledgeBoundaryDatabaseTest}, which states its own reason.
 *
 * <p><strong>Both reads prove authority exactly as {@code ceiling} does</strong> and resolve no
 * scope: the caller's own retained conversation carries the admission, the admission's scope is
 * compared against the target's retained container row, and {@code SourceContainedApplications}
 * still holds no {@code IdentityRepository} through which a scope could be resolved from a
 * caller-supplied value. A sibling, a parent and an unrelated scope therefore fail the comparison
 * rather than being denied a permission later.
 */
class ContainedVisibilityTest {
  @TempDir Path directory;

  ContainedApplicationRepository records = mock(ContainedApplicationRepository.class);
  ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  ApplicationPackageValidator validator = mock(ApplicationPackageValidator.class);
  OperationAuthority authority = mock(OperationAuthority.class);
  ContainedPrincipals principals = mock(ContainedPrincipals.class);
  AllowanceRepository allowances = mock(AllowanceRepository.class);

  AccountId account = new AccountId(UUID.randomUUID());
  GrantRecipient recipient = GrantRecipient.account(account);

  ScopeId parentScope = new ScopeId(UUID.randomUUID());
  ScopeId firstScope = new ScopeId(UUID.randomUUID());
  ScopeId secondScope = new ScopeId(UUID.randomUUID());
  ScopeId grandchildScope = new ScopeId(UUID.randomUUID());
  AllowanceId parentBudget = new AllowanceId(UUID.randomUUID());

  SourceContainedApplications service;

  /** A conversation whose retained admission acts as one scope in one home. */
  OperationAuthority.Conversation conversation(String id, ScopeId scope, String project) {
    var admission =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            "@service/parent",
            account,
            scope,
            Home.of(project),
            recipient,
            List.of(
                new OperationAuthority.Support(AuthorityAction.READ, UUID.randomUUID()),
                new OperationAuthority.Support(AuthorityAction.WRITE, UUID.randomUUID())));
    var retained =
        new OperationAuthority.Conversation(
            id, "worker", admission, null, parentBudget, "0".repeat(64));
    when(authority.conversation(id)).thenReturn(Optional.of(retained));
    return retained;
  }

  ContainedApplicationRepository.Retained child(ScopeId scope, ScopeId container) {
    var retained =
        new ContainedApplicationRepository.Retained(
            UUID.randomUUID(),
            scope,
            42,
            "contained:" + scope.value(),
            container,
            UUID.randomUUID(),
            "a".repeat(64),
            UUID.randomUUID(),
            new AllowanceId(UUID.randomUUID()),
            5,
            true);
    when(records.find(retained.id())).thenReturn(Optional.of(retained));
    when(records.ofScope(scope)).thenReturn(Optional.of(retained));
    return retained;
  }

  @BeforeEach
  void setup() throws Exception {
    Path root = Files.createDirectories(directory.resolve("containment"));
    when(records.ofScope(any())).thenReturn(Optional.empty());
    when(records.find(any())).thenReturn(Optional.empty());
    when(records.children(any())).thenReturn(List.of());
    when(authority.conversationsIn(any())).thenReturn(List.of());
    service =
        new SourceContainedApplications(
            records,
            projects,
            validator,
            authority,
            principals,
            allowances,
            new UnitOfWork() {
              @Override
              public <T> T inTransaction(java.util.function.Supplier<T> body) {
                return body.get();
              }
            },
            new ContainmentConfig(root.toString(), 3, 4, null),
            new InProcessContainedStaging());
  }

  @Test
  void aParentReadsItsChildTrajectory() {
    var parent = conversation("c-parent", parentScope, "parent");
    var first = child(firstScope, parentScope);
    var envelope =
        new OperationAuthority.Conversation(
            "c-child",
            "worker",
            new OperationAuthority.Admission(
                UUID.randomUUID(),
                "@service/parent",
                account,
                firstScope,
                Home.of(first.project()),
                recipient,
                List.of(new OperationAuthority.Support(AuthorityAction.READ, UUID.randomUUID()))),
            "c-parent",
            new AllowanceId(UUID.randomUUID()),
            "1".repeat(64));
    when(authority.conversationsIn(firstScope)).thenReturn(List.of(envelope));

    var steps = service.trajectory("@service/parent", "c-parent", first.id());

    assertEquals(1, steps.size());
    assertEquals("c-child", steps.get(0).conversation());
    assertEquals("c-parent", steps.get(0).parent());
    assertEquals("1".repeat(64), steps.get(0).capabilities());
    // Retained evidence is never a live permission, so the container's authority is rechecked.
    verify(authority).require(parent.admission());
  }

  @Test
  void aParentReadsItsChildResources() {
    conversation("c-parent", parentScope, "parent");
    var first = child(firstScope, parentScope);
    when(records.children(parentScope)).thenReturn(List.of(first));

    var children = service.list("@service/parent", "c-parent");

    assertEquals(1, children.size());
    assertEquals(first.id(), children.get(0).id());
    assertEquals(firstScope.value(), children.get(0).scope());
    assertEquals(first.project(), children.get(0).project());
  }

  @Test
  void aParentCannotReadAChildItDoesNotContain() {
    conversation("c-parent", parentScope, "parent");
    var stranger = child(secondScope, new ScopeId(UUID.randomUUID()));

    assertEquals(
        "This Application does not contain that Application",
        assertThrows(
                CallerFault.class,
                () -> service.trajectory("@service/parent", "c-parent", stranger.id()))
            .getMessage());
    verify(authority, never()).conversationsIn(secondScope);
  }

  /**
   * The container is itself contained here, so it <em>has</em> a record its child could name: the
   * refusal has to come from the chain climbing away from the asker, not from a missing row.
   */
  @Test
  void aChildCannotReadItsParent() {
    var middle = child(firstScope, parentScope);
    var grandchild = child(grandchildScope, firstScope);
    conversation("c-grandchild", grandchildScope, grandchild.project());

    assertEquals(
        "This Application does not contain that Application",
        assertThrows(
                CallerFault.class,
                () -> service.trajectory("@service/parent", "c-grandchild", middle.id()))
            .getMessage());
    verify(authority, never()).conversationsIn(firstScope);
    // And it cannot list its own level either: the listing is keyed on the asker's own scope.
    assertEquals(List.of(), service.list("@service/parent", "c-grandchild"));
    verify(records, never()).children(firstScope);
  }

  @Test
  void aChildCannotReadASibling() {
    var first = child(firstScope, parentScope);
    var second = child(secondScope, parentScope);
    conversation("c-first", firstScope, first.project());

    assertEquals(
        "This Application does not contain that Application",
        assertThrows(
                CallerFault.class,
                () -> service.trajectory("@service/parent", "c-first", second.id()))
            .getMessage());
    verify(authority, never()).conversationsIn(secondScope);
  }

  @Test
  void aSiblingCannotObserveAnotherSiblingDeliverables() {
    var first = child(firstScope, parentScope);
    child(secondScope, parentScope);
    conversation("c-first", firstScope, first.project());
    // A sibling's own listing is of what it contains, which is nothing, and never of its level.
    when(records.children(parentScope))
        .thenThrow(new AssertionError("A sibling never lists its container's children"));

    assertEquals(List.of(), service.list("@service/parent", "c-first"));
  }

  @Test
  void aGrandparentReadsThroughTheContainerChain() {
    conversation("c-parent", parentScope, "parent");
    child(firstScope, parentScope);
    var grandchild = child(grandchildScope, firstScope);
    when(authority.conversationsIn(grandchildScope)).thenReturn(List.of());

    assertEquals(List.of(), service.trajectory("@service/parent", "c-parent", grandchild.id()));
    verify(authority).conversationsIn(grandchildScope);
  }

  // The two knowledge tests below pin PRE-EXISTING boundary machinery -- KnowledgeReadScope,
  // Admission's Global-READ invariant and InformationSql.boundary -- and would pass with every
  // line of this task's own code deleted. They belong here because the fence they pin is the
  // fence this task's operations read through, not because they cover those operations: the
  // coverage of list and trajectory is the eight tests above.
  @Test
  void aChildKnowledgeReadReturnsNothingFromOutsideItsSubtree() {
    var first = child(firstScope, parentScope);
    var contained =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            "@service/parent",
            account,
            firstScope,
            Home.of(first.project()),
            recipient,
            List.of(new OperationAuthority.Support(AuthorityAction.READ, UUID.randomUUID())),
            false);
    var ceiling = KnowledgeReadScope.retained(contained);

    assertEquals(Home.of(first.project()), ceiling.home());
    assertFalse(ceiling.globalRead(), "Global sits outside every containment subtree");
    assertEquals(List.of(Home.of(first.project())), ceiling.memoryHomes());
    // A contained admission cannot be constructed carrying Global READ in the first place.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationAuthority.Admission(
                UUID.randomUUID(),
                "@service/parent",
                account,
                firstScope,
                Home.of(first.project()),
                recipient,
                List.of(new OperationAuthority.Support(AuthorityAction.WRITE, UUID.randomUUID())),
                true));
  }

  @Test
  void aChildKnowledgeReadCannotReachOutwardThroughDependencyClosure() {
    var first = child(firstScope, parentScope);
    var ceiling = KnowledgeReadScope.homeOnly(Home.of(first.project()));
    // The ceiling is what the closure predicate is built from, and a selection that arrives on the
    // wire asking for Global cannot lift it: the intersection only narrows.
    assertFalse(
        ceiling.intersect(new KnowledgeReadScope(Home.of(first.project()), true)).globalRead(),
        "A caller's selection cannot restore Global read above a contained ceiling");
    assertThrows(IllegalArgumentException.class, () -> ceiling.requireHome(Home.of("parent")));
    var context =
        new InformationContext(
                "@service/parent", InformationContext.Selection.project(first.project()))
            .within(ceiling);
    var filter = InformationSql.boundary(context, "r.id");
    assertTrue(filter.sql().contains("knowledge_document_admitted"), filter.sql());
    assertEquals(
        List.of(false, "@service/parent", "@service/parent", first.project()),
        filter.arguments(),
        "The closure predicate is bound to the child's own home with Global read off");
  }

  // There is deliberately no aChildWritesKnowledgeInsideItsOwnHome here. The version that stood
  // in this place reduced to homeOnly(h).requireHome(h), which cannot fail, so it named the
  // user's decision that a contained Application has its own writable knowledge home without
  // exercising it. A ceiling value cannot express where a write lands; the row's resolved home
  // can, and does, in
  // ContainedKnowledgeBoundaryDatabaseTest.aChildWritesKnowledgeInsideItsOwnHome.

  /** Still no collaborator through which either read could resolve a scope. */
  @Test
  void theReadingPathHoldsNoIdentityCollaborator() {
    var held =
        java.util.Arrays.stream(SourceContainedApplications.class.getConstructors())
            .flatMap(one -> java.util.Arrays.stream(one.getParameterTypes()))
            .map(Class::getSimpleName)
            .collect(java.util.stream.Collectors.toSet());
    assertFalse(held.contains("IdentityRepository"), held.toString());
  }
}
