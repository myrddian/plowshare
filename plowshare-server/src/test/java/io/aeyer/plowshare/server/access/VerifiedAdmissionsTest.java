package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.access.OperationAuthority.Support;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.AccountKnowledgePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A repeated recheck of one admission walks once per authority snapshot and staleness bound, while
 * every refusal, transaction and target check keeps its full, uncached behavior.
 *
 * <p>Walks are counted by the repository and grant calls each one makes: two account reads (the
 * executor and the grant source, both "alice" here), one project role read and one {@code
 * allowsUsing} per support item.
 */
class VerifiedAdmissionsTest {
  private static final Duration BOUND = Duration.ofSeconds(2);

  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
  private final ScopedAuthority authority = ScopedAuthorityMocks.mockAuthority();
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final OperationRecords records = mock(OperationRecords.class);
  private final ResourceScopeRepository resources = mock(ResourceScopeRepository.class);

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final AuthorityScope target =
      new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 1);
  private final GrantRecipient recipient = GrantRecipient.account(account);
  private final UUID grant = UUID.randomUUID();
  private final Home home = Home.of("one");

  private final AtomicReference<Optional<AuthoritySnapshot>> snapshot =
      new AtomicReference<>(Optional.of(snapshot()));
  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private final BoundedVerifiedAdmissions verified =
      new BoundedVerifiedAdmissions(snapshot::get, BOUND, 8, clock::get);
  private final PinnedOperationAuthority policy = policy(verified);

  private static AuthoritySnapshot snapshot() {
    return new AuthoritySnapshot(
        new AuthoritySnapshot.Facts(
            Instant.parse("2026-11-14T00:00:00Z"),
            List.of(),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()));
  }

  private PinnedOperationAuthority policy(VerifiedAdmissions memory) {
    return new PinnedOperationAuthority(
        identities,
        grants,
        authority,
        members,
        records,
        resources,
        AccountKnowledgePermissions.DENY,
        ContainmentBounds.absent(),
        memory);
  }

  private Admission admission(List<Support> support) {
    return new Admission(
        UUID.randomUUID(), "alice", account, target.id(), home, recipient, support);
  }

  private Admission admission() {
    return admission(List.of(new Support(AuthorityAction.CONVERSATION_CREATE, grant)));
  }

  @BeforeEach
  void setup() {
    when(identities.account("alice"))
        .thenReturn(
            new IdentityRepository.Account(
                account, "alice", IdentityRepository.AccountKind.USER, false));
    when(identities.project("one")).thenReturn(target);
    when(members.authorityAccount("alice")).thenReturn(Optional.of("alice"));
    when(members.role("one", "alice")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    when(authority.allowsUsing(
            eq(recipient),
            eq(target),
            eq(AuthorityAction.CONVERSATION_CREATE),
            eq(grant),
            anyList()))
        .thenReturn(true);
  }

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  private void assertWalks(int walks) {
    verify(identities, times(2 * walks)).account("alice");
    verify(members, times(walks)).role("one", "alice");
    verify(authority, times(walks)).allowsUsing(any(), any(), any(), any(), any());
  }

  @Test
  void countsEachOutcome() {
    var counts =
        new EnumMap<AuthorityCheckTelemetry.AdmissionOutcome, Integer>(
            AuthorityCheckTelemetry.AdmissionOutcome.class);
    AuthorityCheckTelemetry telemetry =
        new AuthorityCheckTelemetry() {
          @Override
          public void admission(AdmissionOutcome outcome) {
            counts.merge(outcome, 1, Integer::sum);
          }

          @Override
          public void session(SessionOutcome outcome) {}
        };
    var counted = new BoundedVerifiedAdmissions(snapshot::get, BOUND, 8, clock::get, telemetry);
    var counting = policy(counted);
    var first = admission();
    counting.require(first);
    counting.require(first);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    counting.require(first);
    TransactionSynchronizationManager.clear();
    snapshot.set(Optional.empty());
    counting.require(first);
    snapshot.set(Optional.of(snapshot()));
    when(members.role("one", "alice")).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> counting.require(admission()));
    assertEquals(
        Map.of(
            AuthorityCheckTelemetry.AdmissionOutcome.WALKED, 1,
            AuthorityCheckTelemetry.AdmissionOutcome.VERIFIED_HIT, 1,
            AuthorityCheckTelemetry.AdmissionOutcome.TRANSACTION_WALK, 1,
            AuthorityCheckTelemetry.AdmissionOutcome.NO_SNAPSHOT_WALK, 1,
            AuthorityCheckTelemetry.AdmissionOutcome.REFUSED, 1),
        counts);
  }

  @Test
  void repeatedRequireOfOneAdmissionWithinTheBoundWalksOnce() {
    var admitted = admission();
    for (int i = 0; i < 5; i++) {
      clock.addAndGet(BOUND.toNanos() / 10);
      policy.require(admitted);
    }
    assertWalks(1);
    verify(records, times(1)).targets(admitted.id());
    verify(records, times(1)).parent(admitted.id());
  }

  @Test
  void anAuthorityVersionChangeForcesAWalkAndARevocationIsThenDenied() {
    var admitted = admission();
    policy.require(admitted);
    when(authority.allowsUsing(
            eq(recipient),
            eq(target),
            eq(AuthorityAction.CONVERSATION_CREATE),
            eq(grant),
            anyList()))
        .thenReturn(false);
    // Same snapshot: the revocation has not reached this process yet, and the bound allows that.
    assertDoesNotThrow(() -> policy.require(admitted));
    assertWalks(1);
    snapshot.set(Optional.of(snapshot()));
    assertThrows(CallerFault.class, () -> policy.require(admitted));
    assertWalks(2);
    assertEquals(0, verified.size());
  }

  @Test
  void anEntryOlderThanTheBoundForcesAWalk() {
    var admitted = admission();
    policy.require(admitted);
    clock.addAndGet(BOUND.toNanos() - 1);
    policy.require(admitted);
    assertWalks(1);
    clock.addAndGet(1);
    policy.require(admitted);
    assertWalks(2);
    // The renewed entry is valid for a full bound again.
    clock.addAndGet(BOUND.toNanos() - 1);
    policy.require(admitted);
    assertWalks(2);
  }

  @Test
  void aDifferentAdmissionNeverReusesAnotherVerification() {
    var admitted = admission();
    policy.require(admitted);
    // Same fields, another id: interchangeable for nothing but itself.
    var sibling =
        new Admission(
            UUID.randomUUID(),
            admitted.executor(),
            admitted.account(),
            admitted.scope(),
            admitted.home(),
            admitted.source(),
            admitted.support());
    policy.require(sibling);
    assertWalks(2);
    // Same id, another supporting grant: walked, and refused because that grant does not support.
    var otherGrant =
        new Admission(
            admitted.id(),
            admitted.executor(),
            admitted.account(),
            admitted.scope(),
            admitted.home(),
            admitted.source(),
            List.of(new Support(AuthorityAction.CONVERSATION_CREATE, UUID.randomUUID())));
    assertThrows(CallerFault.class, () -> policy.require(otherGrant));
    // Same id, another home: walked, and refused because the home names another scope.
    when(identities.project("two"))
        .thenReturn(new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 2));
    var otherHome =
        new Admission(
            admitted.id(),
            admitted.executor(),
            admitted.account(),
            admitted.scope(),
            Home.of("two"),
            admitted.source(),
            admitted.support());
    assertThrows(CallerFault.class, () -> policy.require(otherHome));
    verify(identities).project("two");
  }

  @Test
  void aDenialIsNeverRemembered() {
    var admitted = admission();
    when(members.role("one", "alice")).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> policy.require(admitted));
    assertThrows(CallerFault.class, () -> policy.require(admitted));
    assertEquals(0, verified.size());
    verify(members, times(2)).role("one", "alice");
    when(members.role("one", "alice")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    policy.require(admitted);
    policy.require(admitted);
    verify(members, times(3)).role("one", "alice");
  }

  @Test
  void aTransactionThatMayHoldItsOwnWritesAlwaysWalksAndRecordsNothing() {
    var admitted = admission();
    var asked = new AtomicInteger();
    var counting =
        new BoundedVerifiedAdmissions(
            () -> {
              asked.incrementAndGet();
              return snapshot.get();
            },
            BOUND,
            8,
            clock::get);
    var transactional = policy(counting);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    transactional.require(admitted);
    transactional.require(admitted);
    TransactionSynchronizationManager.clear();
    assertWalks(2);
    assertEquals(0, asked.get());
    assertEquals(0, counting.size());
    // Outside the transaction the first recheck still walks: nothing was left behind.
    transactional.require(admitted);
    transactional.require(admitted);
    assertWalks(3);
  }

  @Test
  void noUsableSnapshotAlwaysWalks() {
    var admitted = admission();
    snapshot.set(Optional.empty());
    policy.require(admitted);
    policy.require(admitted);
    assertWalks(2);
    assertEquals(0, verified.size());
  }

  @Test
  void targetChecksStillRefuseOnACachedAdmission() {
    var admitted = admission();
    policy.require(admitted);
    var foreign = new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "c-2");
    var own = new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "c-1");
    when(resources.projects(foreign)).thenReturn(List.of("two"));
    when(resources.projects(own)).thenReturn(List.of("one"));
    assertDoesNotThrow(() -> policy.requireTargets(admitted, home, List.of(own)));
    assertThrows(CallerFault.class, () -> policy.requireTargets(admitted, home, List.of(foreign)));
    assertThrows(
        CallerFault.class, () -> policy.requireTargets(admitted, Home.of("two"), List.of()));
    // A cached admission without READ is still refused a read; one with READ is still fenced.
    assertThrows(CallerFault.class, () -> policy.requireReadTargets(admitted, home, List.of(own)));
    when(authority.allowsUsing(
            eq(recipient), eq(target), eq(AuthorityAction.READ), eq(grant), anyList()))
        .thenReturn(true);
    var reader = admission(List.of(new Support(AuthorityAction.READ, grant)));
    policy.require(reader);
    assertDoesNotThrow(() -> policy.requireReadTargets(reader, home, List.of(own)));
    assertThrows(
        CallerFault.class, () -> policy.requireReadTargets(reader, home, List.of(foreign)));
    assertThrows(
        CallerFault.class, () -> policy.requireReadTargets(reader, Home.global(), List.of()));
    // Two admissions, each walked exactly once; every target lookup above still ran.
    assertWalks(2);
    verify(resources, times(2)).projects(own);
    verify(resources, times(2)).projects(foreign);
  }

  /**
   * One agent tool step as {@code JobRuntime} rechecks it: before the step, after pre-tool hooks,
   * through the target check, and after the tool returns. Without the memory that is four full
   * walks; with it, one.
   */
  @Test
  void oneToolStepWalksOnceInsteadOfFourTimes() {
    var tool = new AccessRequest.Resource(ResourceScopeRepository.Kind.MEMORY, "m-1");
    when(resources.projects(tool)).thenReturn(List.of("one"));
    var admitted = admission();
    Runnable step =
        () -> {
          policy.require(admitted); // top of the step loop
          policy.require(admitted); // just before the tool, after pre-tool hooks
          policy.requireTargets(admitted, home, List.of(tool)); // the tool's targets
          policy.require(admitted); // after the tool returns
        };
    step.run();
    assertWalks(1);
    verify(resources).projects(tool);

    clearInvocations(identities, members, authority, resources);
    var uncached = policy(VerifiedAdmissions.none());
    uncached.require(admitted);
    uncached.require(admitted);
    uncached.requireTargets(admitted, home, List.of(tool));
    uncached.require(admitted);
    assertWalks(4);
    verify(resources).projects(tool);
  }

  @Test
  void theMemoryIsBoundedAndEvictsTheLeastRecentlyUsed() {
    var small = new BoundedVerifiedAdmissions(snapshot::get, BOUND, 2, clock::get);
    var walks = new AtomicInteger();
    Runnable walk = walks::incrementAndGet;
    var first = admission();
    var second = admission();
    var third = admission();
    small.require(first, walk);
    small.require(second, walk);
    small.require(first, walk); // first is now the most recently used
    small.require(third, walk); // evicts second
    assertEquals(2, small.size());
    assertEquals(3, walks.get());
    small.require(first, walk);
    small.require(third, walk);
    assertEquals(3, walks.get());
    small.require(second, walk);
    assertEquals(4, walks.get());
  }

  @Test
  void configurationOutsideItsBoundsIsRefused() {
    AuthoritySnapshots none = AuthoritySnapshots.none();
    assertThrows(
        IllegalArgumentException.class,
        () -> new BoundedVerifiedAdmissions(none, Duration.ZERO, 8));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BoundedVerifiedAdmissions(none, Duration.ofMinutes(2), 8));
    assertThrows(
        IllegalArgumentException.class, () -> new BoundedVerifiedAdmissions(none, BOUND, 0));
    verify(authority, never()).allowsUsing(any(), any(), any(), any(), any());
  }
}
