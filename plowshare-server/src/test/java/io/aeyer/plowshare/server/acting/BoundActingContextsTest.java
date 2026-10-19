package io.aeyer.plowshare.server.acting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.protocol.frames.*;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.Tokens;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.ws.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundActingContextsTest {
  final ActingRepository repository = mock(ActingRepository.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final TurnHistoryRepository turns = mock(TurnHistoryRepository.class);
  final ResourceScopeRepository resources = mock(ResourceScopeRepository.class);
  final Clock clock = mock(Clock.class);
  final Instant now = Instant.parse("2026-10-16T00:00:00Z");
  final UUID actorId = UUID.randomUUID();
  final String actor = "@service/" + actorId;
  final String secret = "psa_" + "a".repeat(48);
  final ActingContexts.Connection connection =
      new ActingContexts.Connection("physical", "session", actor);
  final ActingScope scope = new ActingScope(ProjectRole.CONTRIBUTOR, Set.of("research"), true);
  final ActingPolicySource.Token token =
      new ActingPolicySource.Token(
          UUID.randomUUID(),
          "bridge",
          actor,
          "users",
          "alice",
          scope,
          now.plusSeconds(86400),
          false);
  final IdentityRepository identities = mock(IdentityRepository.class);
  final ScopedAuthority actions = mock(ScopedAuthority.class);
  final ActingAuthorization policy =
      new LiveActingAuthorization(repository, members, clock, identities, actions);
  final BoundActingContexts contexts = new BoundActingContexts(repository, policy, clock);
  final AtomicInteger ordinary = new AtomicInteger();
  FrameRouter router;

  ActingContext.Open request() {
    return new ActingContext.Open(
        "bridge", "users", new ActingContext.Proof("acting-token", secret));
  }

  Asking asking() {
    return new Asking("session", actor, "physical");
  }

  String wire(String type, String payload, String selection) {
    return "{\"type\":\""
        + type
        + "\",\"protocol_version\":\"plowshare-v1\",\"payload\":"
        + payload
        + (selection == null ? "" : ",\"actingFor\":" + selection)
        + "}";
  }

  String select(UUID id) {
    return "{\"context\":\"" + id + "\"}";
  }

  Outcome history(UUID context, String conversation) {
    return router.route(
        wire("conversation.turns", "{\"conversation\":\"" + conversation + "\"}", select(context)),
        asking());
  }

  @BeforeEach
  void setup() {
    var subject = new AuthorityIds.AccountId(UUID.randomUUID());
    var actorAccount = new AuthorityIds.AccountId(UUID.randomUUID());
    when(identities.account("alice"))
        .thenReturn(
            new IdentityRepository.Account(
                subject, "alice", IdentityRepository.AccountKind.USER, false));
    when(identities.account(actor))
        .thenReturn(
            new IdentityRepository.Account(
                actorAccount, actor, IdentityRepository.AccountKind.SERVICE_TOKEN, false));
    when(identities.project("research"))
        .thenReturn(new AuthorityScope.Project(new AuthorityIds.ScopeId(UUID.randomUUID()), 1));
    when(identities.project(PersonalSpaces.name("alice")))
        .thenReturn(
            new AuthorityScope.Personal(new AuthorityIds.ScopeId(UUID.randomUUID()), subject, 2));
    when(members.authorityAccount(anyString()))
        .thenAnswer(call -> Optional.of(call.getArgument(0)));
    when(actions.allows(any(), any(), any())).thenReturn(true);
    when(clock.instant()).thenReturn(now);
    when(repository.enabled()).thenReturn(true);
    when(repository.accountActive(actor)).thenReturn(true);
    when(repository.subjectActive("alice")).thenReturn(true);
    when(repository.application("bridge"))
        .thenReturn(
            Optional.of(
                new ApplicationIdentity.Binding(
                    "bridge",
                    new ApplicationIdentity.Declaration("bridge", "CONTRIBUTOR"),
                    actorId,
                    actor,
                    ApplicationIdentity.State.ACTIVE,
                    true,
                    now.plusSeconds(86400).atOffset(ZoneOffset.UTC),
                    null,
                    UUID.randomUUID())));
    when(repository.grant("bridge", "users"))
        .thenReturn(
            Optional.of(
                new ActingPolicySource.Grant(
                    "bridge",
                    "users",
                    ProjectRole.CONTRIBUTOR,
                    true,
                    Set.of(ActingPolicySource.ProofKind.ACTING_TOKEN))));
    when(repository.eligible("bridge", "users", "alice")).thenReturn(true);
    when(repository.proof(Tokens.hash(secret))).thenReturn(Optional.of(token));
    when(repository.token(token.id())).thenReturn(Optional.of(token));
    when(members.role("research", actor)).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    when(members.role("research", "alice")).thenReturn(Optional.of(ProjectRole.MANAGER));
    when(members.role(PersonalSpaces.name("alice"), "alice"))
        .thenReturn(Optional.of(ProjectRole.MANAGER));
    when(resources.projects(
            new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "alice-history")))
        .thenReturn(List.of(PersonalSpaces.name("alice")));
    when(turns.forConversation("alice-history"))
        .thenReturn(
            List.of(
                new TurnRecord(
                    "alice-history",
                    1,
                    "question",
                    "answer",
                    io.aeyer.plowshare.server.agents.Outcome.Ending.ANSWERED,
                    1,
                    null,
                    null)));
    var handlers = new HashMap<String, FrameHandler>(new ActingFrames(contexts).frames());
    handlers.put(
        "conversation.turns",
        (payload, asking) -> {
          ordinary.incrementAndGet();
          assertEquals(actor, asking.handle());
          return Outcome.ok(List.of());
        });
    handlers.put(
        "agent.run",
        (payload, asking) -> {
          ordinary.incrementAndGet();
          return Outcome.ok();
        });
    router =
        new FrameRouter(
            handlers,
            new ActingRequests(contexts, new AuthorizedActingHistory(resources, turns, policy)));
  }

  @Test
  void proof_exchange_and_personal_read_keep_actor_and_subject_separate() {
    var opened = contexts.open(connection, request());
    assertEquals(now.plusSeconds(300), opened.expiresAt());
    assertEquals("alice", opened.subject());
    assertFalse(request().toString().contains(secret));
    assertEquals(Code.OK, history(opened.context(), "alice-history").code());
    assertEquals(0, ordinary.get());
    verify(members, never()).role(PersonalSpaces.name("alice"), actor);
    assertEquals(actor, contexts.resolve(connection, opened.context()).actor());
    assertEquals(Code.OK, router.route(wire("conversation.turns", "{}", null), asking()).code());
    assertEquals(1, ordinary.get());
  }

  @Test
  void contexts_are_bound_to_physical_connection_session_and_actor() {
    var opened = contexts.open(connection, request());
    for (var other :
        List.of(
            new ActingContexts.Connection("other", "session", actor),
            new ActingContexts.Connection("physical", "other", actor),
            new ActingContexts.Connection(
                "physical", "session", "@service/" + UUID.randomUUID()))) {
      assertThrows(RuntimeException.class, () -> contexts.resolve(other, opened.context()));
      assertThrows(RuntimeException.class, () -> contexts.close(other, opened.context()));
    }
    contexts.close(connection, opened.context());
    assertNotEquals(Code.OK, history(opened.context(), "alice-history").code());
  }

  @Test
  void teardown_and_expiry_end_authority_and_reconnect_does_not_inherit_it() {
    var opened = contexts.open(connection, request());
    router.disconnected(connection.id());
    assertThrows(RuntimeException.class, () -> contexts.resolve(connection, opened.context()));
    var next = contexts.open(connection, request());
    when(clock.instant()).thenReturn(now.plusSeconds(300));
    assertThrows(RuntimeException.class, () -> contexts.resolve(connection, next.context()));
  }

  @Test
  void wrong_proof_or_application_actor_cannot_open_a_context() {
    assertThrows(
        RuntimeException.class,
        () ->
            contexts.open(
                new ActingContexts.Connection("other", "s", "@service/" + UUID.randomUUID()),
                request()));
    assertThrows(
        RuntimeException.class,
        () ->
            contexts.open(connection, new ActingContext.Open("other", "users", request().proof())));
    when(repository.proof(Tokens.hash(secret))).thenReturn(Optional.empty());
    assertThrows(RuntimeException.class, () -> contexts.open(connection, request()));
    verifyNoInteractions(turns);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "{}",
        "{\"context\":null}",
        "{\"context\":1}",
        "{\"context\":\"1-1-1-1-1\"}",
        "{\"context\":\"00000000-0000-0000-0000-000000000000\",\"subject\":\"alice\"}"
      })
  void malformed_selection_never_falls_back_to_actor(String selection) {
    assertNotEquals(
        Code.OK, router.route(wire("conversation.turns", "{}", selection), asking()).code());
    assertEquals(0, ordinary.get());
    verifyNoInteractions(turns);
  }

  @Test
  void unsafe_operations_unknown_contexts_and_scope_injection_are_closed() {
    var opened = contexts.open(connection, request());
    assertNotEquals(
        Code.OK, router.route(wire("agent.run", "{}", select(opened.context())), asking()).code());
    assertNotEquals(Code.OK, history(UUID.randomUUID(), "alice-history").code());
    assertNotEquals(
        Code.OK,
        router
            .route(
                wire(
                    "conversation.turns",
                    "{\"conversation\":\"alice-history\",\"project\":\"research\"}",
                    select(opened.context())),
                asking())
            .code());
    assertEquals(0, ordinary.get());
  }

  @Test
  void revocation_eligibility_and_disable_are_rechecked_for_each_read() {
    var opened = contexts.open(connection, request());
    when(repository.eligible("bridge", "users", "alice")).thenReturn(false);
    assertNotEquals(Code.OK, history(opened.context(), "alice-history").code());
    when(repository.eligible("bridge", "users", "alice")).thenReturn(true);
    when(repository.token(token.id()))
        .thenReturn(
            Optional.of(
                new ActingPolicySource.Token(
                    token.id(),
                    token.application(),
                    token.actor(),
                    token.grant(),
                    token.subject(),
                    scope,
                    token.expiresAt(),
                    true)));
    assertNotEquals(Code.OK, history(opened.context(), "alice-history").code());
    when(repository.enabled()).thenReturn(false);
    assertNotEquals(Code.OK, history(opened.context(), "alice-history").code());
    verifyNoInteractions(turns);
  }

  @Test
  void concurrent_subjects_on_one_connection_remain_isolated() throws Exception {
    var alice = contexts.open(connection, request());
    String secondSecret = "psa_" + "b".repeat(48);
    var bobId = new AuthorityIds.AccountId(UUID.randomUUID());
    when(identities.account("bob"))
        .thenReturn(
            new IdentityRepository.Account(
                bobId, "bob", IdentityRepository.AccountKind.USER, false));
    when(identities.project(PersonalSpaces.name("bob")))
        .thenReturn(
            new AuthorityScope.Personal(new AuthorityIds.ScopeId(UUID.randomUUID()), bobId, 3));
    var bobToken =
        new ActingPolicySource.Token(
            UUID.randomUUID(), "bridge", actor, "users", "bob", scope, token.expiresAt(), false);
    when(repository.proof(Tokens.hash(secondSecret))).thenReturn(Optional.of(bobToken));
    when(repository.token(bobToken.id())).thenReturn(Optional.of(bobToken));
    when(repository.subjectActive("bob")).thenReturn(true);
    when(repository.eligible("bridge", "users", "bob")).thenReturn(true);
    when(members.role("research", "bob")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    when(members.role(PersonalSpaces.name("bob"), "bob"))
        .thenReturn(Optional.of(ProjectRole.MANAGER));
    when(resources.projects(
            new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "bob-history")))
        .thenReturn(List.of(PersonalSpaces.name("bob")));
    when(turns.forConversation("bob-history")).thenReturn(List.of());
    var bob =
        contexts.open(
            connection,
            new ActingContext.Open(
                "bridge", "users", new ActingContext.Proof("acting-token", secondSecret)));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var left = pool.submit(() -> history(alice.context(), "alice-history"));
      var right = pool.submit(() -> history(bob.context(), "bob-history"));
      assertEquals(Code.OK, left.get(5, TimeUnit.SECONDS).code());
      assertEquals(Code.OK, right.get(5, TimeUnit.SECONDS).code());
    }
    assertNotEquals(Code.OK, history(alice.context(), "bob-history").code());
    assertNotEquals(Code.OK, history(bob.context(), "alice-history").code());
  }

  @Test
  void close_or_revocation_during_read_prevents_data_return() {
    var opened = contexts.open(connection, request());
    when(turns.forConversation("alice-history"))
        .thenAnswer(
            call -> {
              contexts.close(connection, opened.context());
              return List.of();
            });
    assertNotEquals(Code.OK, history(opened.context(), "alice-history").code());
  }

  @Test
  void malformed_proof_json_never_echoes_credentials_or_accepts_trailing_frames() {
    String malformed =
        "{\"type\":\"acting.context.open\",\"protocol_version\":\"plowshare-v1\",\"payload\":{\"proof\":{\"credential\":"
            + secret
            + "}}}";
    var answer = router.route(malformed, asking());
    assertNotEquals(Code.OK, answer.code());
    assertFalse(answer.said().contains(secret));
    assertNotEquals(
        Code.OK, router.route(wire("conversation.turns", "{}", null) + "{}", asking()).code());
    assertEquals(0, ordinary.get());
  }

  @Test
  void history_uses_the_exact_identifier_that_was_authorized() {
    var opened = contexts.open(connection, request());
    assertNotEquals(Code.OK, history(opened.context(), " alice-history ").code());
    verifyNoInteractions(turns);
  }

  @Test
  void moved_or_global_resources_never_return_data() {
    var opened = contexts.open(connection, request());
    when(resources.projects(
            new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "alice-history")))
        .thenReturn(List.of(PersonalSpaces.name("alice")))
        .thenReturn(List.of(PersonalSpaces.name("bob")));
    assertNotEquals(Code.OK, history(opened.context(), "alice-history").code());
    assertNotEquals(Code.OK, history(opened.context(), "unknown-history").code());
  }

  @Test
  void context_capacity_is_bounded_and_expired_entries_are_reclaimed() {
    for (int n = 0; n < 64; n++) contexts.open(connection, request());
    assertThrows(RuntimeException.class, () -> contexts.open(connection, request()));
    when(clock.instant()).thenReturn(now.plusSeconds(300));
    assertDoesNotThrow(() -> contexts.open(connection, request()));
  }

  @Test
  void admission_never_widens_after_grant_changes_or_outlives_proof() {
    var shortToken =
        new ActingPolicySource.Token(
            token.id(),
            token.application(),
            token.actor(),
            token.grant(),
            token.subject(),
            scope,
            now.plusSeconds(120),
            false);
    when(repository.token(token.id())).thenReturn(Optional.of(shortToken));
    when(repository.proof(Tokens.hash(secret))).thenReturn(Optional.of(shortToken));
    when(repository.grant("bridge", "users"))
        .thenReturn(
            Optional.of(
                new ActingPolicySource.Grant(
                    "bridge",
                    "users",
                    ProjectRole.VIEWER,
                    true,
                    Set.of(ActingPolicySource.ProofKind.ACTING_TOKEN))));
    var opened = contexts.open(connection, request());
    assertEquals(shortToken.expiresAt(), opened.expiresAt());
    when(repository.grant("bridge", "users"))
        .thenReturn(
            Optional.of(
                new ActingPolicySource.Grant(
                    "bridge",
                    "users",
                    ProjectRole.CONTRIBUTOR,
                    true,
                    Set.of(ActingPolicySource.ProofKind.ACTING_TOKEN))));
    assertEquals(
        ProjectRole.VIEWER,
        contexts.resolve(connection, opened.context()).delegation().scope().ceiling());
  }

  @Test
  void audit_failure_prevents_admission_and_disabled_contexts_can_still_close() {
    var audit = org.mockito.ArgumentCaptor.forClass(ActingRepository.ContextAudit.class);
    doThrow(new IllegalStateException("audit unavailable"))
        .when(repository)
        .contextAudit(audit.capture());
    assertThrows(IllegalStateException.class, () -> contexts.open(connection, request()));
    assertThrows(
        RuntimeException.class,
        () -> contexts.resolve(connection, audit.getValue().context().orElseThrow()));
    doNothing().when(repository).contextAudit(any());
    var opened = contexts.open(connection, request());
    when(repository.enabled()).thenReturn(false);
    assertDoesNotThrow(() -> contexts.close(connection, opened.context()));
  }

  @Test
  void revocation_during_the_history_read_prevents_returning_content() {
    var opened = contexts.open(connection, request());
    when(turns.forConversation("alice-history"))
        .thenAnswer(
            call -> {
              when(repository.token(token.id()))
                  .thenReturn(
                      Optional.of(
                          new ActingPolicySource.Token(
                              token.id(),
                              token.application(),
                              token.actor(),
                              token.grant(),
                              token.subject(),
                              scope,
                              token.expiresAt(),
                              true)));
              return List.of();
            });
    assertNotEquals(Code.OK, history(opened.context(), "alice-history").code());
  }

  @Test
  void open_frames_validate_proof_and_payload_before_calling_contexts() {
    String json =
        "{\"application\":\"bridge\",\"grant\":\"users\",\"proof\":{\"kind\":\"acting-token\",\"credential\":\""
            + secret
            + "\"}}";
    assertEquals(Code.OK, router.route(wire("acting.context.open", json, null), asking()).code());
    assertNotEquals(
        Code.OK,
        router
            .route(
                wire("acting.context.open", json.replace("acting-token", "oidc"), null), asking())
            .code());
    assertNotEquals(
        Code.OK,
        router
            .route(wire("acting.context.open", json.replace("\"bridge\"", "1"), null), asking())
            .code());
  }
}
