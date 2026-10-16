package io.aeyer.plowshare.server.acting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationIdentity;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.Tokens;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class OwnedActingTokensTest {
  final ActingRepository repository = mock(ActingRepository.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final Instant now = Instant.parse("2026-10-16T00:00:00Z");
  final String actor = "@service/" + UUID.randomUUID();
  final RequestAuthority.Direct alice = new RequestAuthority.Direct("alice");
  final OwnedActingTokens service =
      new OwnedActingTokens(repository, members, UnitOfWork.NONE, Clock.fixed(now, ZoneOffset.UTC));
  final ActingScope scope = new ActingScope(ProjectRole.CONTRIBUTOR, Set.of("research"), false);
  final ActingPolicySource.Grant grant =
      new ActingPolicySource.Grant(
          "bridge",
          "users",
          ProjectRole.CONTRIBUTOR,
          true,
          Set.of(ActingPolicySource.ProofKind.ACTING_TOKEN));
  ActingTokens.Issue request;

  @BeforeEach
  void setup() {
    request =
        new ActingTokens.Issue(
            "bridge", "users", scope, 7, Optional.of("external-user"), UUID.randomUUID());
    when(repository.subjectActive("alice")).thenReturn(true);
    when(repository.enabled()).thenReturn(true);
    when(repository.accountActive(actor)).thenReturn(true);
    when(repository.application("bridge"))
        .thenReturn(
            Optional.of(
                new ApplicationIdentity.Binding(
                    "bridge",
                    new ApplicationIdentity.Declaration("bridge", "CONTRIBUTOR"),
                    UUID.fromString(actor.substring(9)),
                    actor,
                    ApplicationIdentity.State.ACTIVE,
                    true,
                    now.plusSeconds(86400).atOffset(ZoneOffset.UTC),
                    null,
                    UUID.randomUUID())));
    when(repository.grant("bridge", "users")).thenReturn(Optional.of(grant));
    when(repository.eligible("bridge", "users", "alice")).thenReturn(true);
    when(members.role("research", "alice")).thenReturn(Optional.of(ProjectRole.MANAGER));
    when(members.role("research", actor)).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
  }

  @Test
  void issues_only_for_authenticated_owner_stores_digest_and_retains_metadata() {
    var issued = service.issue(alice, request);
    assertEquals("alice", issued.token().subject());
    assertEquals(actor, issued.token().actor());
    assertEquals(now.plusSeconds(7 * 86400), issued.token().expiresAt());
    assertFalse(issued.toString().contains(issued.credential()));
    verify(repository)
        .insert(
            issued.token(), Tokens.hash(issued.credential()), Optional.of("external-user"), now);
    var submission = ArgumentCaptor.forClass(ActingRepository.Submission.class);
    verify(repository).complete(submission.capture());
    assertEquals(issued.receipt(), submission.getValue().receipt());
    assertFalse(submission.getValue().toString().contains(issued.credential()));
    var order = inOrder(repository);
    order.verify(repository).lock();
    order.verify(repository).subjectActive("alice");
    order.verify(repository).receipt(request.requestId());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "disabled",
        "inactive-subject",
        "inactive-actor",
        "ineligible",
        "missing-identity",
        "missing-grant",
        "withdrawn-proof",
        "lower-grant",
        "subject-role",
        "actor-role"
      })
  void refuses_without_minting_when_live_authority_is_missing(String reason) {
    switch (reason) {
      case "disabled" -> when(repository.enabled()).thenReturn(false);
      case "inactive-subject" -> when(repository.subjectActive("alice")).thenReturn(false);
      case "inactive-actor" -> when(repository.accountActive(actor)).thenReturn(false);
      case "ineligible" -> when(repository.eligible("bridge", "users", "alice")).thenReturn(false);
      case "missing-identity" ->
          when(repository.application("bridge")).thenReturn(Optional.empty());
      case "missing-grant" ->
          when(repository.grant("bridge", "users")).thenReturn(Optional.empty());
      case "withdrawn-proof" ->
          when(repository.grant("bridge", "users"))
              .thenReturn(
                  Optional.of(
                      new ActingPolicySource.Grant(
                          "bridge", "users", ProjectRole.CONTRIBUTOR, true, Set.of())));
      case "lower-grant" ->
          when(repository.grant("bridge", "users"))
              .thenReturn(
                  Optional.of(
                      new ActingPolicySource.Grant(
                          "bridge", "users", ProjectRole.VIEWER, true, grant.proofs())));
      case "subject-role" ->
          when(members.role("research", "alice")).thenReturn(Optional.of(ProjectRole.VIEWER));
      case "actor-role" -> when(members.role("research", actor)).thenReturn(Optional.empty());
      default -> fail("Unknown fixture");
    }
    assertThrows(CallerFault.class, () -> service.issue(alice, request));
    verify(repository, never()).insert(any(), any(), any(), any());
    verify(repository, never()).complete(any());
  }

  @Test
  void personal_consent_needs_grant_and_owner_access_without_actor_membership() {
    var personal =
        new ActingTokens.Issue(
            "bridge",
            "users",
            new ActingScope(ProjectRole.VIEWER, Set.of(), true),
            1,
            Optional.empty(),
            UUID.randomUUID());
    assertThrows(CallerFault.class, () -> service.issue(alice, personal));
    when(members.role(PersonalSpaces.name("alice"), "alice"))
        .thenReturn(Optional.of(ProjectRole.MANAGER));
    assertTrue(service.issue(alice, personal).token().scope().personal());
    verify(members, never()).role(PersonalSpaces.name("alice"), actor);
    when(repository.grant("bridge", "users"))
        .thenReturn(
            Optional.of(
                new ActingPolicySource.Grant(
                    "bridge", "users", ProjectRole.CONTRIBUTOR, false, grant.proofs())));
    assertThrows(CallerFault.class, () -> service.issue(alice, personal));
  }

  @Test
  void duplicate_issue_never_returns_secret_even_after_grant_withdrawal() {
    var issued = service.issue(alice, request);
    var submission = ArgumentCaptor.forClass(ActingRepository.Submission.class);
    verify(repository).complete(submission.capture());
    when(repository.receipt(request.requestId())).thenReturn(Optional.of(submission.getValue()));
    when(repository.grant("bridge", "users")).thenReturn(Optional.empty());
    when(repository.enabled()).thenReturn(false);
    assertThrows(CallerFault.class, () -> service.issue(alice, request));
    assertEquals(Optional.of(issued.receipt()), service.receipt(alice, request.requestId()));
    var changed =
        new ActingTokens.Issue(
            "bridge", "users", scope, 8, Optional.of("external-user"), request.requestId());
    assertThrows(CallerFault.class, () -> service.issue(alice, changed));
    when(repository.subjectActive("bob")).thenReturn(true);
    assertThrows(
        CallerFault.class,
        () -> service.receipt(new RequestAuthority.Direct("bob"), request.requestId()));
    verify(repository, times(1)).insert(any(), any(), any(), any());
  }

  @Test
  void owner_and_admin_may_revoke_but_other_users_cannot_even_when_feature_disabled() {
    var token = service.issue(alice, request).token();
    when(repository.token(token.id())).thenReturn(Optional.of(token));
    when(repository.enabled()).thenReturn(false);
    assertEquals(
        ActingTokens.Action.REVOKE, service.revoke(alice, token.id(), UUID.randomUUID()).action());
    when(repository.subjectActive("bob")).thenReturn(true);
    var bob = new RequestAuthority.Direct("bob");
    assertThrows(CallerFault.class, () -> service.revoke(bob, token.id(), UUID.randomUUID()));
    when(repository.administrator("bob")).thenReturn(true);
    assertEquals(
        ActingTokens.Action.REVOKE, service.revoke(bob, token.id(), UUID.randomUUID()).action());
    verify(repository, times(2)).revoke(token.id(), now);
  }

  @Test
  void only_admin_can_replace_eligibility_and_receipt_replay_has_no_new_effect() {
    var eligibility =
        new ActingTokens.Eligibility(
            "bridge", "users", Set.of("alice"), Set.of("research"), UUID.randomUUID());
    assertThrows(CallerFault.class, () -> service.eligibility(alice, eligibility));
    when(repository.administrator("alice")).thenReturn(true);
    var receipt = service.eligibility(alice, eligibility);
    var submission = ArgumentCaptor.forClass(ActingRepository.Submission.class);
    verify(repository).complete(submission.capture());
    when(repository.receipt(eligibility.requestId()))
        .thenReturn(Optional.of(submission.getValue()));
    assertEquals(receipt, service.eligibility(alice, eligibility));
    verify(repository, times(1)).eligibility(eligibility);
    var changed =
        new ActingTokens.Eligibility(
            "bridge", "users", Set.of(), Set.of(), eligibility.requestId());
    assertThrows(CallerFault.class, () -> service.eligibility(alice, changed));
  }

  @Test
  void failed_receipt_write_propagates_and_never_reports_issued_credential() {
    doThrow(new IllegalStateException("database unavailable")).when(repository).complete(any());
    assertThrows(IllegalStateException.class, () -> service.issue(alice, request));
  }

  @Test
  void service_principals_cannot_issue_or_manage_human_tokens() {
    assertThrows(
        CallerFault.class, () -> service.issue(new RequestAuthority.Direct(actor), request));
    verify(repository, never()).insert(any(), any(), any(), any());
  }

  @Test
  void lists_only_own_metadata() {
    when(repository.tokens("alice")).thenReturn(List.of());
    assertTrue(service.tokens(alice).isEmpty());
    verify(repository).tokens("alice");
  }

  @Test
  void invalid_lifecycle_requests_are_rejected_before_repository_calls() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActingTokens.Issue(
                "bridge", "users", scope, 0, Optional.empty(), UUID.randomUUID()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActingTokens.Issue(
                "bridge", "users", scope, 366, Optional.empty(), UUID.randomUUID()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActingTokens.Issue(
                "bridge",
                "users",
                new ActingScope(ProjectRole.VIEWER, Set.of(), false),
                1,
                Optional.empty(),
                UUID.randomUUID()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActingTokens.Issue(
                "bridge", "users", scope, 1, Optional.of("line\nbreak"), UUID.randomUUID()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActingTokens.Eligibility(
                "bridge", "users", Set.of(actor), Set.of(), UUID.randomUUID()));
  }
}
