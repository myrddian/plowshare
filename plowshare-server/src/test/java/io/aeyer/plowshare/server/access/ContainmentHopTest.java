package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.AccountKnowledgePermissions;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * The containment hop. {@code derive} narrows actions while holding scope, account and home fixed,
 * so it cannot express a hop into a confined child; {@code contain} is its sibling and the only way
 * a contained admission comes into being. Every assertion here is a narrowing or a refusal: there
 * is no call sequence that produces an admission holding an action its parent does not hold.
 *
 * <p>Mocked repository interfaces throughout, per the database test policy. The only real-database
 * assertions in this change are the container-immutability ones in {@code
 * ContainedScopeSchemaTest}, because a trigger is the fence there.
 */
class ContainmentHopTest {
  IdentityRepository identities = mock(IdentityRepository.class);
  ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
  ScopedAuthority authority = mock(ScopedAuthority.class);
  ProjectMembers members = mock(ProjectMembers.class);
  OperationRecords records = mock(OperationRecords.class);
  ResourceScopeRepository resources = mock(ResourceScopeRepository.class);

  AccountId account = new AccountId(UUID.randomUUID());
  GrantRecipient recipient = GrantRecipient.account(account);
  UUID grant = UUID.randomUUID();
  AuthorityScope.Project target = new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 1);

  /** A child project's name carries only the fixed classification prefix; ancestry is a column. */
  String childName = "contained:" + UUID.randomUUID();

  Home childHome = Home.of(childName);
  AuthorityScope.Contained child =
      new AuthorityScope.Contained(new ScopeId(UUID.randomUUID()), 2, target.id());

  PinnedOperationAuthority policy = policy(new ContainmentBounds(2, 4));

  PinnedOperationAuthority policy(ContainmentBounds bounds) {
    return new PinnedOperationAuthority(
        identities,
        grants,
        authority,
        members,
        records,
        resources,
        AccountKnowledgePermissions.DENY,
        bounds);
  }

  @BeforeEach
  void setup() {
    when(identities.account("alice"))
        .thenReturn(
            new IdentityRepository.Account(
                account, "alice", IdentityRepository.AccountKind.USER, false));
    when(identities.project("one")).thenReturn(target);
    when(identities.home(target)).thenReturn(Home.of("one"));
    when(identities.scope(target.id())).thenReturn(target);
    when(identities.containedHome(child, target.id())).thenReturn(childHome);
    // The child's stored containment row, which is what both live-authority gates resolve. Every
    // non-contained scope answers empty, which is why no pre-existing admission changes behaviour.
    when(identities.contained(child.id())).thenReturn(Optional.of(child));
    // Faithful to JdbcIdentityRepository: a contained scope is never resolvable from a project
    // name, so the live-authority path cannot reach one. Nothing in this test mocks that away.
    when(identities.project(childName))
        .thenThrow(new CallerFault("A contained scope has no addressable project home"));
    when(members.authorityAccount("alice")).thenReturn(Optional.of("alice"));
    when(members.role("one", "alice")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    var binding = mock(ScopedGrantRepository.Binding.class);
    when(binding.id()).thenReturn(grant);
    when(grants.live(recipient)).thenReturn(List.of(binding));
    for (var action :
        List.of(
            AuthorityAction.READ,
            AuthorityAction.WRITE,
            AuthorityAction.RUN_MODEL,
            AuthorityAction.CONVERSATION_CREATE))
      when(authority.allowsUsing(recipient, target, action, grant)).thenReturn(true);
  }

  /** A live parent holding three of the four derivable actions. */
  OperationAuthority.Admission parent() {
    return policy.admit(
        "alice",
        target,
        Set.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.RUN_MODEL));
  }

  @Test
  void containNarrowsTheActionSetToASubsetOfTheParent() {
    var parent = parent();
    var contained = policy.contain(parent, child, Set.of(AuthorityAction.READ));
    assertEquals(Set.of(AuthorityAction.READ), contained.actions());
    assertTrue(parent.actions().containsAll(contained.actions()));
    assertNotEquals(parent.actions(), contained.actions());
    // The hop changes scope and home together; the child answers as itself, never as its parent.
    assertEquals(child.id(), contained.scope());
    assertEquals(childHome, contained.home());
    assertNotEquals(parent.id(), contained.id());
    // Global knowledge sits outside every containment subtree, so the hop drops it unconditionally.
    assertFalse(contained.globalRead());
  }

  @Test
  void containRefusesAnActionTheParentDoesNotHold() {
    var parent = parent();
    assertEquals(
        "Operation is outside its retained scoped authority",
        assertThrows(
                CallerFault.class,
                () -> policy.contain(parent, child, Set.of(AuthorityAction.CONVERSATION_CREATE)))
            .getMessage());
    // Asking for everything the parent holds plus one is still asking for the one.
    assertThrows(
        CallerFault.class,
        () ->
            policy.contain(
                parent,
                child,
                Set.of(
                    AuthorityAction.READ,
                    AuthorityAction.WRITE,
                    AuthorityAction.RUN_MODEL,
                    AuthorityAction.CONVERSATION_CREATE)));
    // An empty hop is not a narrowing, and the non-derivable actions are refused as in derive.
    assertThrows(CallerFault.class, () -> policy.contain(parent, child, Set.of()));
    for (var action :
        Set.of(
            AuthorityAction.ADMINISTER,
            AuthorityAction.MANAGE_ACCESS,
            AuthorityAction.MANAGE_SCOPE,
            AuthorityAction.SET_AUDIENCE))
      assertThrows(CallerFault.class, () -> policy.contain(parent, child, Set.of(action)));
    verify(records, never()).retainChild(any(), any());
  }

  /**
   * Reaching above or beside itself is a resolution failure, not a denied permission: the child's
   * own record must name this parent's scope as its container.
   */
  @Test
  void containRefusesAChildScopeThatNamesADifferentContainer() {
    var parent = parent();
    for (var foreign :
        List.of(
            new AuthorityScope.Contained(
                new ScopeId(UUID.randomUUID()), 2, new ScopeId(UUID.randomUUID())),
            // A sibling's container is a scope this parent does not hold either.
            new AuthorityScope.Contained(new ScopeId(UUID.randomUUID()), 3, child.id())))
      assertThrows(
          CallerFault.class, () -> policy.contain(parent, foreign, Set.of(AuthorityAction.READ)));
    verify(records, never()).retainChild(any(), any());
    verify(identities, never()).containedHome(any(), any());
  }

  @Test
  void containNeverChangesTheAccount() {
    var parent = parent();
    var contained = policy.contain(parent, child, Set.of(AuthorityAction.READ));
    assertEquals(parent.account(), contained.account());
    assertEquals(parent.executor(), contained.executor());
    assertEquals(parent.source(), contained.source());
  }

  /**
   * The hop issues no authority. The child's support is exactly the parent's own grant rows for the
   * narrowed actions, so a child's authority is a projection of the parent's and nothing else.
   */
  @Test
  void containRetainsTheParentSupportingGrantsAsAncestry() {
    var parent = parent();
    var contained =
        policy.contain(parent, child, Set.of(AuthorityAction.READ, AuthorityAction.WRITE));
    assertTrue(parent.support().containsAll(contained.support()));
    assertEquals(2, contained.support().size());
    assertEquals(
        Set.of(grant),
        contained.support().stream()
            .map(OperationAuthority.Support::grant)
            .collect(java.util.stream.Collectors.toSet()));
    // The immutable parent edge, as derive records it. No new grant was resolved for the child:
    // the only live-grant read in this test belongs to the parent's own admission.
    verify(records).retainChild(contained, parent.id());
    verify(grants, times(1)).live(recipient);
  }

  /**
   * A contained parent sits one hop down, so its child sits two. The bound refuses that hop before
   * any identity, role or grant is read, and with room for it the same call succeeds — which is the
   * transitive narrowing ADR 0042 claims and which nothing could exercise while a contained
   * admission was inert. Depth is walked through the containment relation, not through the general
   * scope read that refuses a contained row, so a configured depth above 1 is reachable.
   */
  @Test
  void containRefusesBeyondTheConfiguredDepth() {
    var top = parent();
    var enclosing = new AuthorityScope.Contained(new ScopeId(UUID.randomUUID()), 3, target.id());
    Home enclosingHome = Home.of("contained:" + UUID.randomUUID());
    var deep =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            "alice",
            account,
            enclosing.id(),
            enclosingHome,
            recipient,
            top.support());
    // A real depth-1 admission carries the ancestry edge its own hop retained; a contained
    // admission with no parent edge is refused, which its own test asserts separately.
    when(records.parent(deep.id())).thenReturn(Optional.of(top.id()));
    when(records.find(top.id())).thenReturn(Optional.of(top));
    var grandchild =
        new AuthorityScope.Contained(new ScopeId(UUID.randomUUID()), 4, enclosing.id());
    when(identities.contained(enclosing.id())).thenReturn(Optional.of(enclosing));
    when(identities.containedHome(enclosing, target.id())).thenReturn(enclosingHome);
    when(identities.containedHome(grandchild, enclosing.id()))
        .thenReturn(Home.of("contained:" + UUID.randomUUID()));

    clearInvocations(identities);
    assertThrows(
        CallerFault.class,
        () ->
            policy(new ContainmentBounds(1, 4))
                .contain(deep, grandchild, Set.of(AuthorityAction.READ)));
    verify(identities, never()).account(anyString());
    verify(records, never()).retainChild(any(), any());

    var second =
        policy(new ContainmentBounds(2, 4)).contain(deep, grandchild, Set.of(AuthorityAction.READ));
    assertEquals(Set.of(AuthorityAction.READ), second.actions());
    // Still a subset of what the outermost container holds: every hop only ever drops.
    assertTrue(top.actions().containsAll(second.actions()));
    assertFalse(second.globalRead());
    verify(records).retainChild(second, deep.id());
  }

  /** An absent or non-positive configured bound refuses. It is never an unlimited default. */
  @Test
  void containRefusesWhenTheConfiguredDepthIsAbsent() {
    var parent = parent();
    for (var absent :
        List.of(
            ContainmentBounds.absent(),
            new ContainmentBounds(0, 4),
            new ContainmentBounds(-1, 4),
            new ContainmentBounds(2, 0)))
      assertThrows(
          CallerFault.class,
          () -> policy(absent).contain(parent, child, Set.of(AuthorityAction.READ)));
    verify(records, never()).retainChild(any(), any());
    verify(identities, never()).containedHome(any(), any());
  }

  /**
   * Lateral reach. A {@code Contained} value is freely constructible and no repository path returns
   * one, so every field of the argument arrives caller-supplied: comparing {@code
   * child.container()} with the parent's scope only asks the caller to agree with itself. A sibling
   * subtree's project carries the same reserved prefix, so a name-only check cannot tell the two
   * apart either. The stored containment row is what decides, and the hop refuses when it does not
   * resolve — reaching beside yourself is a resolution failure, not a permission the parent happens
   * to lack.
   */
  @Test
  void containRefusesAChildWhoseStoredRowDoesNotResolve() {
    var parent = parent();
    // Same container field, a sibling subtree's project key. The row for this id, under this
    // container, mapping to this project does not exist, so the repository refuses.
    var sibling = new AuthorityScope.Contained(new ScopeId(UUID.randomUUID()), 9, target.id());
    when(identities.containedHome(sibling, target.id()))
        .thenThrow(new CallerFault("Identity or scope mapping is unavailable or restricted"));
    assertThrows(
        CallerFault.class, () -> policy.contain(parent, sibling, Set.of(AuthorityAction.READ)));
    verify(records, never()).retainChild(any(), any());
    // The resolution is not optional and not replaceable by the caller's own assertion: the hop
    // asks for it on every call, with the parent's scope as the container it must match.
    verify(identities).containedHome(sibling, target.id());
  }

  /**
   * GATE TWO, asserted on its own. The ancestry walk demands an ancestor with an identical scope
   * and home, which a hop changes by design; for a contained admission that equality is replaced by
   * the containment edge itself and by nothing else. Task 3 asserted here that the walk refused a
   * hop even when the scope resolved, because the two gates had to be opened together or not at
   * all; this is the same assertion from the other side, now that both are open.
   */
  @Test
  void theAncestryWalkAcceptsAHopOnlyFromItsRecordedContainer() {
    var parent = parent();
    var contained = policy.contain(parent, child, Set.of(AuthorityAction.READ));
    when(records.parent(contained.id())).thenReturn(Optional.of(parent.id()));
    when(records.find(parent.id())).thenReturn(Optional.of(parent));
    // Nothing else was stubbed for this: the child's live authority resolves entirely through its
    // container's project, role and grant, which is what "a projection of its parent's" means.
    assertDoesNotThrow(() -> policy.require(contained));
    // An ancestor that is not this child's recorded container is refused exactly as a mismatched
    // derive is, so the relaxation reaches the hop and no further.
    var stranger =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            "alice",
            account,
            new ScopeId(UUID.randomUUID()),
            Home.of("one"),
            recipient,
            parent.support());
    when(records.find(parent.id())).thenReturn(Optional.of(stranger));
    assertThrows(CallerFault.class, () -> policy.require(contained));
  }

  /**
   * GATE ONE, asserted on its own. The replaced check proved an admission's retained home and scope
   * still agree by resolving the home's project name back to a scope. {@code project(String)} still
   * refuses to resolve a contained scope from a project name, so for a contained admission the same
   * proof runs the other way: the stored child row must still resolve to the exact home the
   * admission retained. A renamed, dropped or recreated child project makes the two disagree.
   */
  @Test
  void aContainedAdmissionDiesWhenItsStoredHomeNoLongerMatches() {
    var parent = parent();
    var contained = policy.contain(parent, child, Set.of(AuthorityAction.READ));
    when(records.parent(contained.id())).thenReturn(Optional.of(parent.id()));
    when(records.find(parent.id())).thenReturn(Optional.of(parent));
    assertDoesNotThrow(() -> policy.require(contained));
    doReturn(Home.of("contained:" + UUID.randomUUID()))
        .when(identities)
        .containedHome(child, target.id());
    assertThrows(CallerFault.class, () -> policy.require(contained));
  }

  /**
   * A contained admission comes into being only through a hop, so an ancestry edge is not optional
   * for one. With no retained parent there is nothing proving a container ever narrowed to it, and
   * the freely constructible {@code Contained} record must not be accepted for want of a parent.
   */
  @Test
  void aContainedAdmissionWithNoRetainedParentIsRefused() {
    var contained = policy.contain(parent(), child, Set.of(AuthorityAction.READ));
    assertTrue(records.parent(contained.id()).isEmpty());
    assertThrows(CallerFault.class, () -> policy.require(contained));
  }

  /**
   * An unconfigured server does not merely stop creating children: it stops accepting the ones that
   * exist. Absence is never an unlimited default on either side of the hop.
   */
  @Test
  void aContainedAdmissionIsRefusedWhenContainmentIsUnconfigured() {
    var parent = parent();
    var contained = policy.contain(parent, child, Set.of(AuthorityAction.READ));
    when(records.parent(contained.id())).thenReturn(Optional.of(parent.id()));
    when(records.find(parent.id())).thenReturn(Optional.of(parent));
    assertThrows(CallerFault.class, () -> policy(ContainmentBounds.absent()).require(contained));
    assertDoesNotThrow(() -> policy.require(contained));
  }
}
