package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.server.access.AccessRequest;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.ProjectAuthorization;
import io.aeyer.plowshare.server.access.ResourceScopeRepository;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.archive.ArchiveQueryCancelledException;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ConversationAliasProperties;
import io.aeyer.plowshare.server.archive.ConversationAliasRepository;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.Faults;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.telemetry.WorkPerformanceReporter;
import io.aeyer.plowshare.server.testing.LogicalSqlWorkBudget;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;

class ExternalConversationServiceTest {
  private final ConversationAliasRepository aliases = mock(ConversationAliasRepository.class);
  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final WorkAccess access = mock(WorkAccess.class);
  private final ConversationRuns runs = mock(ConversationRuns.class);
  private final Runs.Shown pictures = mock(Runs.Shown.class);
  private final LogStages stages = mock(LogStages.class);
  private final ConversationsProperties defaults = new ConversationsProperties();
  private final AccountId account = new AccountId(UUID.randomUUID());
  private final ExternalConversations.Locator locator =
      new ExternalConversations.Locator("house", "ha.installation", "external/opaque:CASE");
  private final ExternalConversations.Caller caller =
      new ExternalConversations.Caller("alice", "socket");
  private ExternalConversationService service;

  @BeforeEach
  void setUp() {
    when(identities.account("alice"))
        .thenReturn(
            new IdentityRepository.Account(
                account, "alice", IdentityRepository.AccountKind.USER, false));
    when(identities.project("house"))
        .thenReturn(new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 42));
    defaults.setDefaultBudget("17");
    service =
        new ExternalConversationService(
            aliases,
            identities,
            access,
            runs,
            pictures,
            stages,
            defaults,
            new ConversationAliasProperties(Duration.ofSeconds(2), Duration.ofSeconds(1)),
            new LogicalSqlWorkBudget());
  }

  static ConversationRecord row(ConversationLifecycle lifecycle) {
    return new ConversationRecord(
        "cnv_retained",
        Home.of("house"),
        Origin.TURN,
        lifecycle,
        null,
        null,
        Instant.EPOCH,
        null,
        Budget.of(20),
        TurnCap.of(8),
        "Retained");
  }

  @Test
  void namespaceIsAuthorizedBeforeAnyIdentityLookupOrAliasDisclosure() {
    doThrow(new CallerFault("denied")).when(access).requireWork("house", "alice");
    assertThrows(CallerFault.class, () -> service.run(locator, run(), caller));
    verifyNoInteractions(aliases, identities, runs, stages);
  }

  @Test
  void firstOpenUsesServerIdentityAndCommitsBeforeItsSingleHook() {
    var row = row(ConversationLifecycle.ACTIVE);
    when(aliases.open(any(), any()))
        .thenReturn(
            new ConversationAliasRepository.Opened(row, true),
            new ConversationAliasRepository.Opened(row, false));
    var options = new ExternalConversations.Open(null, null, null, null);
    assertSame(row, service.open(locator, options, caller));
    assertSame(row, service.open(locator, options, caller));
    var key = ArgumentCaptor.forClass(ConversationAliasRepository.Key.class);
    var opening = ArgumentCaptor.forClass(ConversationAliasRepository.Opening.class);
    verify(aliases, times(2)).open(key.capture(), opening.capture());
    assertEquals(account, key.getValue().account());
    assertEquals(42, key.getValue().projectId());
    assertEquals("external/opaque:CASE", key.getValue().externalConversation());
    assertEquals(17, opening.getValue().maxModelCalls());
    var order = inOrder(aliases, stages);
    order.verify(aliases).open(any(), any());
    order.verify(stages).opened(LogStages.LogOpened.ofConversation(row, "socket"));
    order.verify(aliases).open(any(), any());
    verifyNoMoreInteractions(stages);
  }

  @ParameterizedTest
  @EnumSource(ConversationLifecycle.class)
  void existingOpenNeverRevivesLifecycleOrChangesItsAllowance(ConversationLifecycle lifecycle) {
    var retained = row(lifecycle);
    retained.budget().trySpend();
    when(aliases.open(any(), any()))
        .thenReturn(new ConversationAliasRepository.Opened(retained, false));
    assertSame(
        retained,
        service.open(locator, new ExternalConversations.Open(99, 7, false, false), caller));
    assertEquals(20, retained.budget().limit());
    assertEquals(1, retained.budget().spent());
    assertEquals(lifecycle, retained.lifecycle());
    verifyNoInteractions(stages, runs);
  }

  @Test
  void configuredZeroIsPreservedButExplicitZeroIsNotACreationChoice() {
    defaults.setDefaultBudget("0");
    when(aliases.open(any(), any()))
        .thenReturn(
            new ConversationAliasRepository.Opened(row(ConversationLifecycle.ACTIVE), false));
    service.open(locator, new ExternalConversations.Open(null, null, null, null), caller);
    var options = ArgumentCaptor.forClass(ConversationAliasRepository.Opening.class);
    verify(aliases).open(any(), options.capture());
    assertEquals(0, options.getValue().budget().limit());
    assertThrows(
        IllegalArgumentException.class, () -> new ExternalConversations.Open(0, null, null, null));
  }

  @Test
  void resolveMissNeverCreatesOrRunsAnything() {
    when(aliases.find(any())).thenReturn(Optional.empty());
    assertThrows(NotFoundFault.class, () -> service.run(locator, run(), caller));
    verify(aliases, never()).open(any(), any());
    verifyNoInteractions(runs, stages);
  }

  @Test
  void resolvedRowAndExplicitOrdinaryRaiseReachTheSameRunOwner() {
    var retained = row(ConversationLifecycle.ACTIVE);
    when(aliases.find(any())).thenReturn(Optional.of(retained));
    when(runs.startResolved(any(), eq(pictures), any()))
        .thenReturn(new Runs.Started("job", "worker", retained.id()));
    var options =
        new ExternalConversations.Run(
            "worker", "Keep original text", "workspace", 35, 4, false, List.of());
    assertEquals("job", service.run(locator, options, caller).id());
    var ask = ArgumentCaptor.forClass(Runs.Ask.class);
    var resolved = ArgumentCaptor.forClass(ConversationRuns.Resolved.class);
    verify(runs).startResolved(ask.capture(), eq(pictures), resolved.capture());
    assertNull(ask.getValue().project());
    assertEquals(retained.id(), ask.getValue().conversation());
    assertEquals(35, ask.getValue().maxModelCalls());
    assertEquals("workspace", ask.getValue().session());
    assertSame(retained, resolved.getValue().conversation());
    assertEquals(42, resolved.getValue().projectId());
    verify(aliases, times(1)).find(any());
    verify(aliases, never()).open(any(), any());
    verifyNoInteractions(stages, pictures);
  }

  @Test
  void hooksAndAcceptedRunsAreObservedWithoutAWorkflowDeadlineOrLateFailure() {
    var performance = mock(WorkPerformanceReporter.class);
    var observation = mock(WorkPerformanceReporter.Observation.class);
    when(performance.watch(eq(WorkPerformanceReporter.Kind.WORKFLOW), any()))
        .thenReturn(observation);
    var facade =
        new ExternalConversationService(
            aliases, identities, access, runs, pictures, stages, defaults, performance);
    var target = row(ConversationLifecycle.ACTIVE);
    when(aliases.open(any(), any()))
        .thenReturn(new ConversationAliasRepository.Opened(target, true));
    doAnswer(
            call -> {
              assertFalse(ReadDeadline.currentDeadlineNanos().isPresent());
              return null;
            })
        .when(stages)
        .opened(any());
    assertSame(
        target,
        facade.open(locator, new ExternalConversations.Open(null, null, null, null), caller));
    when(aliases.find(any())).thenReturn(Optional.of(target));
    when(runs.startResolved(any(), eq(pictures), any()))
        .thenAnswer(
            call -> {
              assertFalse(ReadDeadline.currentDeadlineNanos().isPresent());
              return new Runs.Started("accepted", "worker", target.id());
            });
    assertEquals("accepted", facade.run(locator, run(), caller).id());
    verify(performance).watch(WorkPerformanceReporter.Kind.WORKFLOW, "external-conversation.open");
    verify(performance).watch(WorkPerformanceReporter.Kind.WORKFLOW, "external-conversation.run");
    verify(observation, times(2)).close();
  }

  @Test
  void actualTargetScopeAndCurrentAccessAreCheckedBeforeRunOrHook() {
    var target = row(ConversationLifecycle.ACTIVE);
    when(aliases.find(any())).thenReturn(Optional.of(target));
    doNothing().doThrow(new CallerFault("withdrawn")).when(access).requireWork("house", "alice");
    assertThrows(CallerFault.class, () -> service.run(locator, run(), caller));
    verifyNoInteractions(runs, stages);
  }

  @Test
  void oneEffectiveWorkPipelinePerPhaseRetainsThePostCommitIdentityFence() {
    var members = mock(ProjectMembers.class);
    when(members.mayWork("house", "alice")).thenReturn(true);
    var actualAccess = new CallerAccess(new SessionRegistry(), members);
    var actualService =
        new ExternalConversationService(
            aliases,
            identities,
            actualAccess,
            runs,
            pictures,
            stages,
            defaults,
            new ConversationAliasProperties(Duration.ofSeconds(2), Duration.ofSeconds(1)),
            new LogicalSqlWorkBudget());
    var row = row(ConversationLifecycle.ACTIVE);
    when(aliases.open(any(), any())).thenReturn(new ConversationAliasRepository.Opened(row, false));
    actualService.open(locator, new ExternalConversations.Open(null, null, null, null), caller);
    verify(members, times(2)).mayWork("house", "alice");
    verify(members, never()).mayUse(any(), any());
    verify(identities, times(1)).account("alice");
    verify(identities, times(2)).project("house");
    var order = inOrder(members, identities, aliases);
    order.verify(members).mayWork("house", "alice");
    order.verify(identities).account("alice");
    order.verify(identities).project("house");
    order.verify(aliases).open(any(), any());
    order.verify(identities).project("house");
    order.verify(members).mayWork("house", "alice");
  }

  @Test
  void immediateRouterEvidenceReusesOnlyItsFirstMatchingNamespaceCheck() {
    var commonMembers = mock(ProjectMembers.class);
    var common =
        new ProjectAuthorization(
            mock(ResourceScopeRepository.class), commonMembers, mock(AdminStore.class));
    var row = row(ConversationLifecycle.ACTIVE);
    when(aliases.open(any(), any())).thenReturn(new ConversationAliasRepository.Opened(row, false));
    ReadDeadline.within(
        Duration.ofSeconds(2),
        () -> {
          try (var evidence =
              common.requireExternalConversation(
                  "conversation.open.ex", AccessRequest.project("house"), "alice", "socket")) {
            var admitted = new ExternalConversations.Caller("alice", "socket", evidence);
            service.open(locator, new ExternalConversations.Open(null, null, null, null), admitted);
            verify(access, times(1)).requireWork("house", "alice");
            // Retaining the Caller cannot skip the next operation's live namespace work check.
            service.open(locator, new ExternalConversations.Open(null, null, null, null), admitted);
            verify(access, times(3)).requireWork("house", "alice");
          }
          return true;
        });
    verify(commonMembers, times(1)).requireRole("house", "alice", ProjectRole.CONTRIBUTOR);
  }

  @Test
  void mismatchedRouterEvidenceFallsThroughToLiveNamespaceRefusalBeforeAliasDisclosure() {
    var common =
        new ProjectAuthorization(
            mock(ResourceScopeRepository.class),
            mock(ProjectMembers.class),
            mock(AdminStore.class));
    doThrow(new CallerFault("withdrawn")).when(access).requireWork("house", "alice");
    ReadDeadline.within(
        Duration.ofSeconds(2),
        () -> {
          try (var evidence =
              common.requireExternalConversation(
                  "conversation.open.ex", AccessRequest.project("house"), "alice", "original")) {
            assertThrows(
                CallerFault.class,
                () ->
                    service.open(
                        locator,
                        new ExternalConversations.Open(null, null, null, null),
                        new ExternalConversations.Caller("alice", "socket", evidence)));
          }
          return true;
        });
    verifyNoInteractions(aliases, identities, runs, stages);
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void aliasAvailabilityFailureRetainsUncertaintyWithoutRetryOrRawDependencyDetails(
      boolean opening, boolean cancelled) {
    RuntimeException outage =
        cancelled
            ? new ArchiveQueryCancelledException("private dependency diagnostic", null)
            : new ArchiveUnavailableException("private dependency diagnostic", null);
    if (opening) doThrow(outage).when(aliases).open(any(), any());
    else doThrow(outage).when(aliases).find(any());
    var failure =
        assertThrows(
            RuntimeException.class,
            () -> {
              if (opening)
                service.open(
                    locator, new ExternalConversations.Open(null, null, null, null), caller);
              else service.run(locator, run(), caller);
            });
    var fault = Faults.of(failure);
    assertEquals(Code.ARCHIVE_UNAVAILABLE, fault.code());
    if (cancelled) {
      assertSame(outage, failure);
      assertEquals(
          ReadFailedException.Reason.QUERY_CANCELLED,
          assertInstanceOf(ReadFailedException.class, failure).reason());
    } else {
      assertInstanceOf(ExternalConversationUnavailableException.class, failure);
      assertTrue(fault.detail().contains("may have committed"));
      assertTrue(fault.detail().contains("do not replay"));
    }
    assertFalse(fault.detail().contains("Nothing was read"));
    assertFalse(fault.detail().contains("nothing was written"));
    assertFalse(fault.detail().contains("private dependency diagnostic"));
    if (opening) verify(aliases, times(1)).open(any(), any());
    else verify(aliases, times(1)).find(any());
    verifyNoInteractions(runs, stages);
  }

  @Test
  void exactExAndSqlAvailabilityFailuresKeepTheirOwningOutcome() {
    for (RuntimeException failure :
        List.of(
            new ExternalConversationUnavailableException("owned Ex failure", null),
            ReadFailedException.deadlineExceeded(),
            new ReadUnavailableException())) {
      doThrow(failure).when(aliases).open(any(), any());
      assertSame(
          failure,
          assertThrows(
              RuntimeException.class,
              () ->
                  service.open(
                      locator, new ExternalConversations.Open(null, null, null, null), caller)));
    }
    verify(aliases, times(3)).open(any(), any());
  }

  static Stream<RuntimeException> nonAvailabilityFailures() {
    return Stream.of(
        new CallerFault("Refused"),
        new DataIntegrityViolationException("Constraint refusal"),
        new BadSqlGrammarException(
            "Fixture query", "invalid fixture SQL", new SQLException("syntax")),
        new IllegalStateException("Programming failure"));
  }

  @ParameterizedTest
  @MethodSource("nonAvailabilityFailures")
  void unrelatedFailuresKeepTheirOriginalTypeAndAreNotRetried(RuntimeException failure) {
    doThrow(failure).when(aliases).find(any());
    assertSame(
        failure, assertThrows(RuntimeException.class, () -> service.run(locator, run(), caller)));
    verify(aliases, times(1)).find(any());
    verifyNoInteractions(runs, stages);
  }

  @Test
  void aReusedProjectNameCannotRedirectTheResolvedTarget() {
    when(aliases.find(any())).thenReturn(Optional.of(row(ConversationLifecycle.ACTIVE)));
    when(identities.project("house"))
        .thenReturn(
            new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 42),
            new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 99));
    assertThrows(CallerFault.class, () -> service.run(locator, run(), caller));
    verifyNoInteractions(runs, stages);
  }

  @Test
  void aDifferentAuthenticatedAccountHasAnotherAliasPartition() {
    var bob = new AccountId(UUID.randomUUID());
    when(identities.account("bob"))
        .thenReturn(
            new IdentityRepository.Account(
                bob, "bob", IdentityRepository.AccountKind.SERVICE, false));
    when(aliases.find(any())).thenReturn(Optional.empty());
    assertThrows(NotFoundFault.class, () -> service.run(locator, run(), caller));
    assertThrows(
        NotFoundFault.class,
        () -> service.run(locator, run(), new ExternalConversations.Caller("bob", "other")));
    var keys = ArgumentCaptor.forClass(ConversationAliasRepository.Key.class);
    verify(aliases, times(2)).find(keys.capture());
    assertEquals(account, keys.getAllValues().get(0).account());
    assertEquals(bob, keys.getAllValues().get(1).account());
  }

  private static ExternalConversations.Run run() {
    return new ExternalConversations.Run("worker", "work", null, null, null, null, List.of());
  }
}
