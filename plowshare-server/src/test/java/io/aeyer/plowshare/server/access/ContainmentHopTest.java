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
    when(identities.containedHome(child)).thenReturn(childHome);
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
    verify(identities, never()).containedHome(any());
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
   * A contained parent sits one hop down, so its child sits two. The bound refuses the hop before
   * any authority is resolved at all, which is what the two {@code identities.account} assertions
   * distinguish: with room for the second hop the same call reaches the parent's live authority.
   */
  @Test
  void containRefusesBeyondTheConfiguredDepth() {
    var support = parent().support();
    var enclosing = new AuthorityScope.Contained(new ScopeId(UUID.randomUUID()), 3, target.id());
    var deep =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            "alice",
            account,
            enclosing.id(),
            Home.of("one"),
            recipient,
            support);
    var grandchild =
        new AuthorityScope.Contained(new ScopeId(UUID.randomUUID()), 4, enclosing.id());
    when(identities.scope(enclosing.id())).thenReturn(enclosing);
    when(identities.containedHome(grandchild))
        .thenReturn(Home.of("contained:" + UUID.randomUUID()));

    clearInvocations(identities);
    assertThrows(
        CallerFault.class,
        () ->
            policy(new ContainmentBounds(1, 4))
                .contain(deep, grandchild, Set.of(AuthorityAction.READ)));
    verify(identities, never()).account(anyString());

    clearInvocations(identities);
    assertThrows(
        CallerFault.class,
        () ->
            policy(new ContainmentBounds(2, 4))
                .contain(deep, grandchild, Set.of(AuthorityAction.READ)));
    verify(identities).account("alice");
    verify(records, never()).retainChild(any(), any());
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
    verify(identities, never()).containedHome(any());
  }

  /**
   * The inertness this slice ships deliberately. A contained admission's home is its own child
   * project, and no contained scope is resolvable from a project name, so {@code require} cannot
   * accept one and the hop never claims it can. Making a contained admission live is the work of
   * the task that makes a contained scope resolvable; until then, nothing can exercise one.
   */
  @Test
  void aContainedAdmissionIsNotLiveUntilTheAuthorityPathLearnsContainment() {
    var contained = policy.contain(parent(), child, Set.of(AuthorityAction.READ));
    assertThrows(CallerFault.class, () -> policy.require(contained));
  }
}
