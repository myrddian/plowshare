package io.aeyer.plowshare.server.acting;

import static io.aeyer.plowshare.server.acting.ActingPermission.READ;
import static io.aeyer.plowshare.server.acting.ActingPermission.WORK;
import static io.aeyer.plowshare.server.archive.ProjectRole.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationIdentity;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.acting.ActingPolicySource.Grant;
import io.aeyer.plowshare.server.acting.ActingPolicySource.ProofKind;
import io.aeyer.plowshare.server.acting.ActingPolicySource.Token;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Policy decisions use mocked live sources and membership, without Spring or a database. */
class LiveActingAuthorizationTest {
  private static final UUID ACTOR_TOKEN = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private static final String ACTOR = "@service/" + ACTOR_TOKEN;
  private static final String APP = "connector";
  private static final String GRANT = "account-users";
  private static final String SUBJECT = "alice";
  private static final String PROJECT = "research";
  private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
  private final UUID proof = UUID.randomUUID();
  private final ActingPolicySource source = mock(ActingPolicySource.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final ScopedAuthority actions = mock(ScopedAuthority.class);
  private final AccountId subjectId = new AccountId(UUID.randomUUID());
  private final AccountId actorId = new AccountId(UUID.randomUUID());
  private final AuthorityScope projectScope =
      new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 1);
  private final AuthorityScope personalScope =
      new AuthorityScope.Personal(new ScopeId(UUID.randomUUID()), subjectId, 2);
  private final ActingAuthorization policy =
      new LiveActingAuthorization(
          source, members, Clock.fixed(NOW, ZoneOffset.UTC), identities, actions);
  private final ActingTarget project = new ActingTarget.Project(PROJECT);
  private final ActingTarget personal = new ActingTarget.Personal(SUBJECT);
  private final ActingScope full = scope(CONTRIBUTOR, true);
  private final RequestAuthority.Delegated authority = authority(full);

  @BeforeEach
  void currentAuthority() {
    when(identities.account(SUBJECT))
        .thenReturn(
            new IdentityRepository.Account(
                subjectId, SUBJECT, IdentityRepository.AccountKind.USER, false));
    when(identities.account(ACTOR))
        .thenReturn(
            new IdentityRepository.Account(
                actorId, ACTOR, IdentityRepository.AccountKind.SERVICE_TOKEN, false));
    when(identities.project(PROJECT)).thenReturn(projectScope);
    when(identities.project(PersonalSpaces.name(SUBJECT))).thenReturn(personalScope);
    when(members.authorityAccount(anyString()))
        .thenAnswer(call -> Optional.of(call.getArgument(0)));
    when(actions.allows(any(), any(), any())).thenReturn(true);
    when(source.enabled()).thenReturn(true);
    when(source.accountActive(ACTOR)).thenReturn(true);
    when(source.subjectActive(SUBJECT)).thenReturn(true);
    when(source.application(APP)).thenReturn(Optional.of(application()));
    when(source.grant(APP, GRANT)).thenReturn(Optional.of(grant(CONTRIBUTOR, true)));
    when(source.eligible(APP, GRANT, SUBJECT)).thenReturn(true);
    when(source.token(proof)).thenReturn(Optional.of(token(full)));
    when(members.role(PROJECT, SUBJECT)).thenReturn(Optional.of(CONTRIBUTOR));
    when(members.role(PROJECT, ACTOR)).thenReturn(Optional.of(CONTRIBUTOR));
    when(members.role(PersonalSpaces.name(SUBJECT), SUBJECT)).thenReturn(Optional.of(MANAGER));
  }

  private ActingScope scope(ProjectRole ceiling, boolean personal) {
    return new ActingScope(ceiling, Set.of(PROJECT), personal);
  }

  private RequestAuthority.Delegated authority(ActingScope admitted) {
    return new RequestAuthority.Delegated(
        ACTOR, SUBJECT, new RequestAuthority.Delegation(APP, GRANT, proof, admitted));
  }

  private ApplicationIdentity.Binding application() {
    return new ApplicationIdentity.Binding(
        APP,
        new ApplicationIdentity.Declaration("worker", "CONTRIBUTOR"),
        ACTOR_TOKEN,
        ACTOR,
        ApplicationIdentity.State.ACTIVE,
        true,
        OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC),
        null,
        UUID.randomUUID());
  }

  private Grant grant(ProjectRole ceiling, boolean personal) {
    return new Grant(APP, GRANT, ceiling, personal, Set.of(ProofKind.ACTING_TOKEN));
  }

  private Token token(ActingScope scope) {
    return new Token(proof, APP, ACTOR, GRANT, SUBJECT, scope, NOW.plusSeconds(3600), false);
  }

  @ParameterizedTest
  @CsvSource({
    "CONTRIBUTOR, CONTRIBUTOR, CONTRIBUTOR, CONTRIBUTOR, CONTRIBUTOR, CONTRIBUTOR",
    "MANAGER, MANAGER, CONTRIBUTOR, CONTRIBUTOR, CONTRIBUTOR, CONTRIBUTOR",
    "VIEWER, MANAGER, CONTRIBUTOR, CONTRIBUTOR, CONTRIBUTOR, VIEWER",
    "MANAGER, VIEWER, CONTRIBUTOR, CONTRIBUTOR, CONTRIBUTOR, VIEWER",
    "MANAGER, MANAGER, VIEWER, CONTRIBUTOR, CONTRIBUTOR, VIEWER",
    "MANAGER, MANAGER, CONTRIBUTOR, VIEWER, CONTRIBUTOR, VIEWER",
    "MANAGER, MANAGER, CONTRIBUTOR, CONTRIBUTOR, VIEWER, VIEWER",
    "VIEWER, VIEWER, VIEWER, VIEWER, VIEWER, VIEWER"
  })
  void everyProjectCeilingNarrowsIndependently(
      ProjectRole subject,
      ProjectRole actor,
      ProjectRole grant,
      ProjectRole proofCeiling,
      ProjectRole admitted,
      ProjectRole expected) {
    when(members.role(PROJECT, SUBJECT)).thenReturn(Optional.of(subject));
    when(members.role(PROJECT, ACTOR)).thenReturn(Optional.of(actor));
    when(source.grant(APP, GRANT)).thenReturn(Optional.of(grant(grant, true)));
    when(source.token(proof)).thenReturn(Optional.of(token(scope(proofCeiling, true))));
    var request = authority(scope(admitted, true));
    assertEquals(expected, policy.require(request, READ, project));
    if (expected == VIEWER) {
      assertThrows(CallerFault.class, () -> policy.require(request, WORK, project));
    } else {
      assertEquals(CONTRIBUTOR, policy.require(request, WORK, project));
    }
  }

  @ParameterizedTest
  @EnumSource(ActingPermission.class)
  void onlyReviewedActionsCanBeAdmitted(ActingPermission permission) {
    if (Set.of(READ, WORK, ActingPermission.CONVERSATION_CREATE, ActingPermission.RUN_MODEL)
        .contains(permission)) {
      assertEquals(CONTRIBUTOR, policy.require(authority, permission, project));
    } else {
      assertThrows(CallerFault.class, () -> policy.require(authority, permission, project));
      verifyNoInteractions(source, members);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void bothActorAndSubjectNeedCurrentProjectMembership(boolean actor) {
    when(members.role(PROJECT, actor ? ACTOR : SUBJECT)).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> policy.require(authority, READ, project));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void tokenAndAdmissionProjectSetsCannotBeExpandedByMembership(boolean token) {
    var restricted = new ActingScope(CONTRIBUTOR, Set.of(), true);
    when(members.role(PROJECT, SUBJECT)).thenReturn(Optional.of(MANAGER));
    when(members.role(PROJECT, ACTOR)).thenReturn(Optional.of(MANAGER));
    if (token) when(source.token(proof)).thenReturn(Optional.of(token(restricted)));
    var request = token ? authority : authority(restricted);
    assertThrows(CallerFault.class, () -> policy.require(request, READ, project));
  }

  @ParameterizedTest
  @CsvSource({
    "true,true,true",
    "false,true,true",
    "true,false,true",
    "true,true,false",
    "false,false,false"
  })
  void personalRequiresGrantTokenAndAdmittedConsent(
      boolean grant, boolean token, boolean admitted) {
    when(source.grant(APP, GRANT)).thenReturn(Optional.of(grant(CONTRIBUTOR, grant)));
    when(source.token(proof)).thenReturn(Optional.of(token(scope(CONTRIBUTOR, token))));
    var request = authority(scope(CONTRIBUTOR, admitted));
    if (grant && token && admitted) {
      assertEquals(CONTRIBUTOR, policy.require(request, WORK, personal));
      verify(members).role(PersonalSpaces.name(SUBJECT), SUBJECT);
    } else {
      assertThrows(CallerFault.class, () -> policy.require(request, READ, personal));
    }
    verify(members, never()).role(anyString(), eq(ACTOR));
    verify(members, never()).assign(anyString(), anyString(), any(), anyString(), anyBoolean());
  }

  @Test
  void personalScopeCannotSelectAnotherAccount() {
    assertThrows(
        CallerFault.class, () -> policy.require(authority, READ, new ActingTarget.Personal("bob")));
    verifyNoInteractions(members);
  }

  @Test
  void personalReadDoesNotMakeAViewerAContributorOrIgnoreLostOwnership() {
    when(members.role(PersonalSpaces.name(SUBJECT), SUBJECT)).thenReturn(Optional.of(VIEWER));
    assertEquals(VIEWER, policy.require(authority, READ, personal));
    assertThrows(CallerFault.class, () -> policy.require(authority, WORK, personal));
    when(members.role(PersonalSpaces.name(SUBJECT), SUBJECT)).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> policy.require(authority, READ, personal));
  }

  @ParameterizedTest
  @EnumSource(
      value = AuthorityAction.class,
      names = {"READ", "WRITE", "CONVERSATION_CREATE", "RUN_MODEL"})
  void eachProjectActionRequiresBothScopedAccounts(AuthorityAction action) {
    ActingPermission permission = permission(action);
    for (var account : Set.of(subjectId, actorId)) {
      when(actions.allows(GrantRecipient.account(account), projectScope, action)).thenReturn(false);
      assertThrows(CallerFault.class, () -> policy.require(authority, permission, project));
      when(actions.allows(GrantRecipient.account(account), projectScope, action)).thenReturn(true);
    }
    assertEquals(CONTRIBUTOR, policy.require(authority, permission, project));
  }

  @ParameterizedTest
  @EnumSource(
      value = AuthorityAction.class,
      names = {"READ", "WRITE", "CONVERSATION_CREATE", "RUN_MODEL"})
  void personalUsesOnlySubjectScopedAuthorityAndNeverCreatesMembership(AuthorityAction action) {
    var permission = permission(action);
    assertEquals(CONTRIBUTOR, policy.require(authority, permission, personal));
    verify(actions).allows(GrantRecipient.account(subjectId), personalScope, action);
    verify(actions, never()).allows(eq(GrantRecipient.account(actorId)), any(), any());
    verify(members, never()).authorityAccount(ACTOR);
    verify(members, never()).assign(anyString(), anyString(), any(), anyString(), anyBoolean());
    when(actions.allows(GrantRecipient.account(subjectId), personalScope, action))
        .thenReturn(false);
    assertThrows(CallerFault.class, () -> policy.require(authority, permission, personal));
  }

  @Test
  void serviceTokenChecksItsOwningGrantWithoutLosingTheActorCeiling() {
    when(members.authorityAccount(ACTOR)).thenReturn(Optional.of(SUBJECT));
    assertEquals(CONTRIBUTOR, policy.require(authority, WORK, project));
    verify(actions, times(2))
        .allows(GrantRecipient.account(subjectId), projectScope, AuthorityAction.WRITE);
    when(members.role(PROJECT, ACTOR)).thenReturn(Optional.of(VIEWER));
    assertThrows(CallerFault.class, () -> policy.require(authority, WORK, project));
  }

  @Test
  void foreignPersonalIdentityAndUnavailableSubstrateCannotFallBackToConsent() {
    when(identities.project(PersonalSpaces.name(SUBJECT)))
        .thenReturn(new AuthorityScope.Personal(new ScopeId(UUID.randomUUID()), actorId, 3));
    assertThrows(CallerFault.class, () -> policy.require(authority, READ, personal));
    when(actions.allows(any(), any(), any())).thenThrow(new IllegalStateException("unavailable"));
    assertThrows(IllegalStateException.class, () -> policy.require(authority, READ, project));
  }

  private static ActingPermission permission(AuthorityAction action) {
    return switch (action) {
      case READ -> READ;
      case WRITE -> WORK;
      case CONVERSATION_CREATE -> ActingPermission.CONVERSATION_CREATE;
      case RUN_MODEL -> ActingPermission.RUN_MODEL;
      default -> throw new IllegalArgumentException("unsupported test action");
    };
  }

  enum MissingRecord {
    APPLICATION,
    GRANT,
    TOKEN
  }

  @ParameterizedTest
  @EnumSource(MissingRecord.class)
  void missingAuthorityRecordsRefuseRatherThanFallBack(MissingRecord missing) {
    switch (missing) {
      case APPLICATION -> when(source.application(APP)).thenReturn(Optional.empty());
      case GRANT -> when(source.grant(APP, GRANT)).thenReturn(Optional.empty());
      case TOKEN -> when(source.token(proof)).thenReturn(Optional.empty());
    }
    assertThrows(CallerFault.class, () -> policy.require(authority, READ, project));
  }

  enum ForeignToken {
    ID,
    APPLICATION,
    ACTOR,
    GRANT,
    SUBJECT
  }

  @ParameterizedTest
  @EnumSource(ForeignToken.class)
  void proofMustMatchTheExactActorApplicationGrantAndSubject(ForeignToken foreign) {
    var other = UUID.randomUUID();
    var token =
        new Token(
            foreign == ForeignToken.ID ? other : proof,
            foreign == ForeignToken.APPLICATION ? "other-app" : APP,
            foreign == ForeignToken.ACTOR ? "@service/" + other : ACTOR,
            foreign == ForeignToken.GRANT ? "other-grant" : GRANT,
            foreign == ForeignToken.SUBJECT ? "bob" : SUBJECT,
            full,
            NOW.plusSeconds(3600),
            false);
    when(source.token(proof)).thenReturn(Optional.of(token));
    assertThrows(CallerFault.class, () -> policy.require(authority, READ, project));
    verifyNoInteractions(members);
  }

  enum InvalidApplication {
    RESERVED,
    UNISSUED,
    REVOKED,
    EXPIRED,
    FOREIGN_PROJECT,
    FOREIGN_PRINCIPAL
  }

  @ParameterizedTest
  @EnumSource(InvalidApplication.class)
  void onlyTheExactActiveIssuedAndUnexpiredApplicationCanAct(InvalidApplication invalid) {
    var original = application();
    UUID id = invalid == InvalidApplication.FOREIGN_PRINCIPAL ? UUID.randomUUID() : ACTOR_TOKEN;
    var application =
        new ApplicationIdentity.Binding(
            invalid == InvalidApplication.FOREIGN_PROJECT ? "another-app" : APP,
            original.service(),
            id,
            "@service/" + id,
            invalid == InvalidApplication.RESERVED
                ? ApplicationIdentity.State.RESERVED
                : ApplicationIdentity.State.ACTIVE,
            invalid != InvalidApplication.UNISSUED,
            invalid == InvalidApplication.EXPIRED
                ? OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
                : original.expiresAt(),
            invalid == InvalidApplication.REVOKED
                ? OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
                : null,
            original.credentialRequestId());
    when(source.application(APP)).thenReturn(Optional.of(application));
    assertThrows(CallerFault.class, () -> policy.require(authority, READ, project));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void actorAndSubjectDisablementImmediatelyRefuseNewChecks(boolean actor) {
    assertEquals(CONTRIBUTOR, policy.require(authority, WORK, project));
    if (actor) when(source.accountActive(ACTOR)).thenReturn(false);
    else when(source.subjectActive(SUBJECT)).thenReturn(false);
    assertThrows(CallerFault.class, () -> policy.require(authority, READ, project));
  }

  @Test
  void disabledFeatureRefusesBeforeReadingAuthorityRecords() {
    when(source.enabled()).thenReturn(false);
    assertThrows(CallerFault.class, () -> policy.require(authority, READ, project));
    verify(source).enabled();
    verifyNoMoreInteractions(source);
    verifyNoInteractions(members);
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void proofExpiryIsExclusiveAtTheCurrentInstant(long secondsRemaining) {
    when(source.token(proof))
        .thenReturn(
            Optional.of(
                new Token(
                    proof,
                    APP,
                    ACTOR,
                    GRANT,
                    SUBJECT,
                    full,
                    NOW.plusSeconds(secondsRemaining),
                    false)));
    if (secondsRemaining > 0) assertEquals(CONTRIBUTOR, policy.require(authority, READ, project));
    else assertThrows(CallerFault.class, () -> policy.require(authority, READ, project));
  }

  @Test
  void eligibilityProofAndGrantWithdrawalAreNeverCached() {
    assertEquals(CONTRIBUTOR, policy.require(authority, WORK, project));
    when(source.eligible(APP, GRANT, SUBJECT)).thenReturn(false);
    assertThrows(CallerFault.class, () -> policy.require(authority, READ, project));
    when(source.eligible(APP, GRANT, SUBJECT)).thenReturn(true);
    when(source.token(proof))
        .thenReturn(
            Optional.of(
                new Token(proof, APP, ACTOR, GRANT, SUBJECT, full, NOW.plusSeconds(3600), true)));
    assertThrows(CallerFault.class, () -> policy.require(authority, READ, project));
    when(source.token(proof)).thenReturn(Optional.of(token(full)));
    when(source.grant(APP, GRANT))
        .thenReturn(Optional.of(new Grant(APP, GRANT, CONTRIBUTOR, true, Set.of())));
    assertThrows(CallerFault.class, () -> policy.require(authority, READ, project));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void returnedGrantMetadataMustMatchTheRequestedDeclaration(boolean application) {
    when(source.grant(APP, GRANT))
        .thenReturn(
            Optional.of(
                new Grant(
                    application ? "another-app" : APP,
                    application ? GRANT : "another-grant",
                    CONTRIBUTOR,
                    true,
                    Set.of(ProofKind.ACTING_TOKEN))));
    assertThrows(CallerFault.class, () -> policy.require(authority, READ, project));
  }

  @Test
  void subsequentChecksObserveNarrowingAndCannotExpandTheAdmittedScope() {
    assertEquals(CONTRIBUTOR, policy.require(authority, WORK, project));
    when(source.grant(APP, GRANT)).thenReturn(Optional.of(grant(VIEWER, true)));
    assertThrows(CallerFault.class, () -> policy.require(authority, WORK, project));
    var narrower = authority(scope(VIEWER, true));
    when(source.grant(APP, GRANT)).thenReturn(Optional.of(grant(CONTRIBUTOR, true)));
    assertThrows(CallerFault.class, () -> policy.require(narrower, WORK, project));
    assertEquals(VIEWER, policy.require(narrower, READ, project));
  }

  @Test
  void interleavedSubjectsKeepTheirOwnPersonalAuthorityAndTheSameApplicationActor() {
    UUID bobProof = UUID.randomUUID();
    var bobId = new AuthorityIds.AccountId(UUID.randomUUID());
    when(identities.account("bob"))
        .thenReturn(
            new IdentityRepository.Account(
                bobId, "bob", IdentityRepository.AccountKind.USER, false));
    when(identities.project(PersonalSpaces.name("bob")))
        .thenReturn(
            new AuthorityScope.Personal(new AuthorityIds.ScopeId(UUID.randomUUID()), bobId, 3));
    var bob =
        new RequestAuthority.Delegated(
            ACTOR, "bob", new RequestAuthority.Delegation(APP, GRANT, bobProof, full));
    when(source.subjectActive("bob")).thenReturn(true);
    when(source.eligible(APP, GRANT, "bob")).thenReturn(true);
    when(source.token(bobProof))
        .thenReturn(
            Optional.of(
                new Token(bobProof, APP, ACTOR, GRANT, "bob", full, NOW.plusSeconds(3600), false)));
    when(members.role(PersonalSpaces.name("bob"), "bob")).thenReturn(Optional.of(MANAGER));
    assertEquals(CONTRIBUTOR, policy.require(authority, WORK, personal));
    assertEquals(CONTRIBUTOR, policy.require(bob, WORK, new ActingTarget.Personal("bob")));
    assertThrows(CallerFault.class, () -> policy.require(bob, READ, personal));
    assertEquals(CONTRIBUTOR, policy.require(authority, WORK, personal));
    assertEquals(ACTOR, authority.actor());
    assertEquals(ACTOR, bob.actor());
    assertEquals(SUBJECT, authority.subject());
    assertEquals("bob", bob.subject());
  }

  @Test
  void sourceFailuresPropagateWithoutProducingAnAuthorizationDecision() {
    var outage = new IllegalStateException("authority source unavailable");
    when(source.token(proof)).thenThrow(outage);
    assertSame(
        outage,
        assertThrows(IllegalStateException.class, () -> policy.require(authority, WORK, project)));
    verifyNoInteractions(members);
  }

  enum Withdrawal {
    ACTOR,
    SUBJECT,
    PROOF,
    EXPIRY,
    ELIGIBILITY,
    MANIFEST,
    ADMITTED_CEILING,
    SUBJECT_ACTION
  }

  @ParameterizedTest
  @EnumSource(Withdrawal.class)
  void liveActingChecksRemainAdditionalToOrdinaryOperationAuthority(Withdrawal withdrawal) {
    var home = Home.of(PersonalSpaces.name(SUBJECT));
    var definition =
        new AgentDefinition(
            "chat", "chat", "fast", List.of(), List.of(), List.of(), 4, 8, "Answer", true, false);
    var operations = mock(OperationAuthority.class);
    var operation =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            SUBJECT,
            subjectId,
            personalScope.id(),
            home,
            GrantRecipient.account(subjectId),
            OperationExecution.actions(definition).stream()
                .map(action -> new OperationAuthority.Support(action, UUID.randomUUID()))
                .toList());
    when(operations.scope(home)).thenReturn(personalScope);
    when(operations.admit(SUBJECT, personalScope, OperationExecution.actions(definition)))
        .thenReturn(operation);
    var dispatcher = mock(LlmDispatcher.class);
    var transcript = mock(Transcript.class, CALLS_REAL_METHODS);
    when(transcript.conversationId()).thenReturn("cnv_ROOT");
    var resources = mock(ResourceScopeRepository.class);
    when(resources.projects(any())).thenReturn(List.of(home.project()));
    var admitted =
        withdrawal == Withdrawal.ADMITTED_CEILING ? authority(scope(VIEWER, true)) : authority;
    Runnable check =
        () -> {
          policy.require(admitted, READ, personal);
          policy.require(admitted, WORK, personal);
          policy.require(admitted, ActingPermission.RUN_MODEL, personal);
        };
    var runtime =
        new JobRuntime(operations, dispatcher, List.of())
            .forAdmittedTurn(
                new RunAdmission(
                    check,
                    job -> {},
                    () -> {},
                    SUBJECT,
                    new PersonalRunAuthority(SUBJECT, check, resources)));
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              switch (withdrawal) {
                case ACTOR -> when(source.accountActive(ACTOR)).thenReturn(false);
                case SUBJECT -> when(source.subjectActive(SUBJECT)).thenReturn(false);
                case PROOF ->
                    when(source.token(proof))
                        .thenReturn(
                            Optional.of(
                                new Token(
                                    proof,
                                    APP,
                                    ACTOR,
                                    GRANT,
                                    SUBJECT,
                                    full,
                                    NOW.plusSeconds(3600),
                                    true)));
                case EXPIRY ->
                    when(source.token(proof))
                        .thenReturn(
                            Optional.of(
                                new Token(proof, APP, ACTOR, GRANT, SUBJECT, full, NOW, false)));
                case ELIGIBILITY -> when(source.eligible(APP, GRANT, SUBJECT)).thenReturn(false);
                case MANIFEST ->
                    when(source.grant(APP, GRANT)).thenReturn(Optional.of(grant(VIEWER, true)));
                case SUBJECT_ACTION ->
                    when(actions.allows(
                            GrantRecipient.account(subjectId),
                            personalScope,
                            AuthorityAction.RUN_MODEL))
                        .thenReturn(false);
                case ADMITTED_CEILING -> fail("An admitted READER must never dispatch");
              }
              return new Completion("private model answer", "stop", TokenUsage.UNKNOWN, List.of());
            });
    var budget = Budget.of(4);
    if (withdrawal == Withdrawal.ADMITTED_CEILING) {
      assertThrows(
          CallerFault.class,
          () ->
              runtime.run(
                  definition,
                  "Hello",
                  home,
                  budget,
                  () -> false,
                  null,
                  JobWatch.UNWATCHED,
                  transcript,
                  TurnCap.of(8),
                  List.of(),
                  SUBJECT,
                  false));
      verifyNoInteractions(dispatcher);
      assertEquals(0, budget.spent());
    } else {
      var outcome =
          runtime.run(
              definition,
              "Hello",
              home,
              budget,
              () -> false,
              null,
              JobWatch.UNWATCHED,
              transcript,
              TurnCap.of(8),
              List.of(),
              SUBJECT,
              false);
      assertEquals(Outcome.Ending.UNAVAILABLE, outcome.ending());
      assertFalse(outcome.text().contains("private model answer"));
      assertEquals(1, outcome.modelCalls());
      assertEquals(1, budget.spent());
      verify(operations, atLeastOnce()).require(operation);
    }
    verify(members, never()).authorityAccount(ACTOR);
    verify(operations, never()).admit(eq(ACTOR), any(), any());
  }
}
