package io.aeyer.plowshare.server.acting;

import static io.aeyer.plowshare.server.acting.ActingPermission.READ;
import static io.aeyer.plowshare.server.acting.ActingPermission.WORK;
import static io.aeyer.plowshare.server.archive.ProjectRole.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationIdentity;
import io.aeyer.plowshare.server.acting.ActingPolicySource.Grant;
import io.aeyer.plowshare.server.acting.ActingPolicySource.ProofKind;
import io.aeyer.plowshare.server.acting.ActingPolicySource.Token;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
  private final ActingAuthorization policy =
      new LiveActingAuthorization(source, members, Clock.fixed(NOW, ZoneOffset.UTC));
  private final ActingTarget project = new ActingTarget.Project(PROJECT);
  private final ActingTarget personal = new ActingTarget.Personal(SUBJECT);
  private final ActingScope full = scope(CONTRIBUTOR, true);
  private final RequestAuthority.Delegated authority = authority(full);

  @BeforeEach
  void currentAuthority() {
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
  void onlyReviewedReadAndWorkCapabilitiesCanBeAdmitted(ActingPermission permission) {
    if (permission == READ || permission == WORK) {
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
}
