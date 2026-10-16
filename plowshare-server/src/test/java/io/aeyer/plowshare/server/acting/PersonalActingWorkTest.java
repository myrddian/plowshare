package io.aeyer.plowshare.server.acting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Admission, replay refusal and live execution checks use repository fakes, not PostgreSQL. */
class PersonalActingWorkTest {
  final String actor = "@service/" + UUID.randomUUID();
  final RequestAuthority.Delegated authority =
      new RequestAuthority.Delegated(
          actor,
          "alice",
          new RequestAuthority.Delegation(
              "bridge",
              "users",
              UUID.randomUUID(),
              new ActingScope(ProjectRole.CONTRIBUTOR, Set.of("research"), true)));
  final ActingAuthorization policy = mock(ActingAuthorization.class);
  final ActingAgents agents = mock(ActingAgents.class);
  final Store store = new Store();
  final AtomicReference<RunAdmission> queued = new AtomicReference<>();
  final AgentDefinition agent =
      new AgentDefinition(
          "chat",
          "chat",
          "fast",
          List.of(),
          List.of(),
          List.of(),
          4,
          8,
          "Answer clearly",
          true,
          false);
  int submissions;
  ActingTurns turns =
      (conversation, definition, task, admission) -> {
        assertFalse(store.transaction, "execution must start after admission commits");
        assertEquals(
            ActingWork.State.ADMITTED, store.rows.values().iterator().next().receipt().state());
        submissions++;
        queued.set(admission);
        admission.check().run();
        admission.started().accept("job_ABC123");
      };

  @BeforeEach
  void setup() {
    when(agents.resolve("alice", "chat")).thenReturn(agent);
  }

  PersonalActingWork service() {
    return new PersonalActingWork(
        store,
        policy,
        agents,
        turns,
        store.transactions,
        50,
        mock(io.aeyer.plowshare.server.access.ResourceScopeRepository.class));
  }

  ActingWork.Start request() {
    return new ActingWork.Start(UUID.randomUUID(), "chat", "Hello", null);
  }

  @Test
  void admission_retains_actor_subject_and_personal_ceiling_before_execution() {
    var request = request();
    var result = service().start(authority, request);
    assertEquals(ActingWork.State.STARTED, result.state());
    assertEquals("job_ABC123", result.job());
    assertEquals("alice", store.owner);
    assertEquals(50, store.budget);
    var retained = store.bindings.get(result.conversation()).authority();
    assertEquals(actor, retained.actor());
    assertEquals("alice", retained.subject());
    assertEquals(authority.delegation().proof(), retained.delegation().proof());
    assertEquals(Set.of(), retained.delegation().scope().projects());
    assertTrue(retained.delegation().scope().personal());
    queued.get().ended().run();
    assertEquals(
        ActingWork.State.FINISHED, service().receipt(authority, request.requestId()).state());
  }

  @Test
  void duplicate_returns_original_receipt_without_starting_again() {
    var request = request();
    var original = service().start(authority, request);
    assertEquals(original, service().start(authority, request));
    assertEquals(1, submissions);
    assertEquals(1, store.bindings.size());
  }

  @Test
  void lost_socket_or_new_service_instance_recovers_receipt_without_execution() {
    var request = request();
    var original = service().start(authority, request);
    // No context/socket is retained, and a new service reads the owning records.
    assertEquals(original, service().receipt(authority, request.requestId()));
    assertEquals(1, submissions);
    queued.get().check().run();
  }

  @Test
  void changed_parameters_with_same_uuid_refuse() {
    var request = request();
    service().start(authority, request);
    assertThrows(
        CallerFault.class,
        () ->
            service()
                .start(
                    authority,
                    new ActingWork.Start(request.requestId(), "chat", "Different", null)));
    assertEquals(1, submissions);
  }

  @Test
  void changed_subject_or_proof_cannot_reuse_or_read_another_receipt() {
    var request = request();
    service().start(authority, request);
    for (var other :
        List.of(
            new RequestAuthority.Delegated(actor, "bob", authority.delegation()),
            new RequestAuthority.Delegated(
                actor,
                "alice",
                new RequestAuthority.Delegation(
                    "bridge", "users", UUID.randomUUID(), authority.delegation().scope())))) {
      assertThrows(CallerFault.class, () -> service().start(other, request));
      assertThrows(CallerFault.class, () -> service().receipt(other, request.requestId()));
      assertThrows(
          CallerFault.class,
          () -> service().requireJob(other, "job_ABC123", ActingPermission.READ));
    }
    assertEquals(1, submissions);
  }

  @Test
  void existing_bound_conversation_can_take_another_turn() {
    var original = service().start(authority, request());
    store.nextJob = "job_SECOND";
    turns =
        (conversation, definition, task, admission) -> {
          submissions++;
          admission.started().accept(store.nextJob);
        };
    var result =
        service()
            .start(
                authority,
                new ActingWork.Start(UUID.randomUUID(), "chat", "Next", original.conversation()));
    assertEquals(original.conversation(), result.conversation());
    assertEquals(1, store.bindings.size());
    assertEquals(2, submissions);
  }

  @Test
  void an_unbound_or_relocated_conversation_refuses() {
    assertThrows(
        CallerFault.class,
        () ->
            service()
                .start(
                    authority,
                    new ActingWork.Start(UUID.randomUUID(), "chat", "Hello", "cnv_OTHER")));
    var request = request();
    service().start(authority, request);
    store.owned = false;
    assertThrows(CallerFault.class, () -> queued.get().check().run());
    assertThrows(CallerFault.class, () -> service().receipt(authority, request.requestId()));
  }

  @Test
  void revocation_after_admission_blocks_queued_work_and_later_reads() {
    var request = request();
    service().start(authority, request);
    doThrow(new CallerFault("revoked")).when(policy).require(any(), any(), any());
    assertThrows(CallerFault.class, () -> queued.get().check().run());
    assertThrows(CallerFault.class, () -> service().receipt(authority, request.requestId()));
    assertThrows(
        CallerFault.class,
        () -> service().requireJob(authority, "job_ABC123", ActingPermission.WORK));
    assertEquals(1, submissions);
  }

  @Test
  void denial_before_admission_creates_no_conversation_or_receipt() {
    doThrow(new CallerFault("denied")).when(policy).require(any(), any(), any());
    assertThrows(CallerFault.class, () -> service().start(authority, request()));
    assertTrue(store.rows.isEmpty());
    assertTrue(store.bindings.isEmpty());
    assertEquals(0, submissions);
  }

  @Test
  void archiving_stops_work_but_does_not_hide_its_receipt() {
    var request = request();
    service().start(authority, request);
    store.active = false;
    assertThrows(CallerFault.class, () -> queued.get().check().run());
    assertNotNull(service().receipt(authority, request.requestId()));
    assertThrows(
        CallerFault.class,
        () -> service().requireJob(authority, "job_ABC123", ActingPermission.WORK));
  }

  @Test
  void no_personal_scope_refuses_before_admission() {
    var ordinary =
        new RequestAuthority.Delegated(
            actor,
            "alice",
            new RequestAuthority.Delegation(
                "bridge",
                "users",
                authority.delegation().proof(),
                new ActingScope(ProjectRole.CONTRIBUTOR, Set.of("research"), false)));
    assertThrows(CallerFault.class, () -> service().start(ordinary, request()));
    assertEquals(0, submissions);
  }

  @Test
  void changed_definition_cannot_be_substituted_for_an_admitted_agent() {
    service().start(authority, request());
    when(agents.resolve("alice", "chat"))
        .thenReturn(
            new AgentDefinition(
                "chat",
                "changed",
                "fast",
                List.of(),
                List.of(),
                List.of(),
                4,
                8,
                "Different prompt",
                true,
                false));
    assertThrows(CallerFault.class, () -> queued.get().check().run());
  }

  @Test
  void uncertain_submission_is_retained_and_never_replayed() {
    var request = request();
    turns =
        (conversation, definition, task, admission) -> {
          submissions++;
          admission.started().accept("job_ABC123");
          throw new IllegalStateException("response was lost after admission");
        };
    assertThrows(IllegalStateException.class, () -> service().start(authority, request));
    assertEquals(
        ActingWork.State.UNCERTAIN, service().receipt(authority, request.requestId()).state());
    assertEquals(ActingWork.State.UNCERTAIN, service().start(authority, request).state());
    assertEquals(1, submissions);
  }

  @Test
  void interrupted_reservation_is_read_only_and_not_resubmitted() {
    var request = request();
    service().start(authority, request);
    var key = new Key(actor, request.requestId());
    store.update(key, ActingWork.State.ADMITTED, null);
    assertEquals(ActingWork.State.ADMITTED, service().start(authority, request).state());
    assertEquals(1, submissions);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "cnv_x ", "other"})
  void invalid_conversation_identifiers_refuse(String conversation) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ActingWork.Start(UUID.randomUUID(), "chat", "Hello", conversation));
  }

  record Key(String actor, UUID request) {}

  static final class Store implements ActingWorkRepository {
    final Map<Key, Admission> rows = new HashMap<>();
    final Map<String, Binding> bindings = new HashMap<>();
    boolean owned = true;
    boolean active = true;
    boolean transaction;
    String owner;
    String nextJob;
    int budget;
    final UnitOfWork transactions =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> work) {
            transaction = true;
            try {
              return work.get();
            } finally {
              transaction = false;
            }
          }
        };

    @Override
    public void lock() {
      assertTrue(transaction);
    }

    @Override
    public String open(String subject, int allowance) {
      owner = subject;
      budget = allowance;
      return "cnv_" + (bindings.size() + 1);
    }

    @Override
    public Optional<Binding> binding(String conversation) {
      return Optional.ofNullable(bindings.get(conversation));
    }

    @Override
    public boolean owns(Binding binding) {
      return owned && binding.equals(bindings.get(binding.conversation()));
    }

    @Override
    public boolean active(Binding binding) {
      return active;
    }

    @Override
    public void bind(Binding binding) {
      assertTrue(transaction);
      bindings.put(binding.conversation(), binding);
    }

    @Override
    public Optional<Admission> receipt(String actor, UUID requestId) {
      return Optional.ofNullable(rows.get(new Key(actor, requestId)));
    }

    @Override
    public Optional<Admission> job(String job) {
      return rows.values().stream().filter(row -> job.equals(row.receipt().job())).findFirst();
    }

    @Override
    public void admit(Admission admission) {
      assertTrue(transaction);
      rows.put(
          new Key(admission.binding().authority().actor(), admission.receipt().requestId()),
          admission);
    }

    @Override
    public void started(String actor, UUID requestId, String job) {
      var key = new Key(actor, requestId);
      if (rows.get(key).receipt().state() != ActingWork.State.ADMITTED)
        throw new IllegalStateException("already submitted");
      update(key, ActingWork.State.STARTED, job);
    }

    @Override
    public void finished(String actor, UUID requestId) {
      var key = new Key(actor, requestId);
      update(key, ActingWork.State.FINISHED, rows.get(key).receipt().job());
    }

    @Override
    public void uncertain(String actor, UUID requestId) {
      var key = new Key(actor, requestId);
      if (rows.get(key).receipt().state() != ActingWork.State.FINISHED)
        update(key, ActingWork.State.UNCERTAIN, rows.get(key).receipt().job());
    }

    void update(Key key, ActingWork.State state, String job) {
      var old = rows.get(key);
      rows.put(
          key,
          new Admission(
              old.binding(),
              old.fingerprint(),
              new ActingWork.Receipt(
                  old.receipt().requestId(),
                  old.receipt().conversation(),
                  old.receipt().agent(),
                  state,
                  job)));
    }
  }
}
