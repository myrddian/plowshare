package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.server.access.ResourceScopeRepository;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.RetainedAgent;
import io.aeyer.plowshare.server.agents.RunAdmission;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Admission commits before submitting a turn. A duplicate UUID only returns its original receipt;
 * neither a duplicate nor receipt recovery resubmits ADMITTED, STARTED or UNCERTAIN work. The
 * retained authority is narrowed to Personal and rechecked without consulting the ephemeral socket
 * context.
 */
public final class PersonalActingWork implements ActingWork {
  private final ActingWorkRepository repository;
  private final ActingAuthorization policy;
  private final ActingAgents agents;
  private final ActingTurns turns;
  private final UnitOfWork transactions;
  private final int budget;
  private final ResourceScopeRepository resources;

  public PersonalActingWork(
      ActingWorkRepository repository,
      ActingAuthorization policy,
      ActingAgents agents,
      ActingTurns turns,
      UnitOfWork transactions,
      int budget,
      ResourceScopeRepository resources) {
    if (budget < 1) throw new IllegalArgumentException("finite positive budget required");
    this.repository = repository;
    this.policy = policy;
    this.agents = agents;
    this.turns = turns;
    this.transactions = transactions;
    this.budget = budget;
    this.resources = Objects.requireNonNull(resources);
  }

  private record Prepared(ActingWorkRepository.Admission admission, AgentDefinition definition) {}

  @Override
  public Receipt start(RequestAuthority.Delegated caller, Start request) {
    var authority = personal(caller);
    String fingerprint = fingerprint(authority, request);
    var prepared =
        transactions.inTransaction(
            () -> {
              repository.lock();
              require(authority, ActingPermission.WORK);
              var previous = repository.receipt(authority.actor(), request.requestId());
              if (previous.isPresent()) {
                if (!previous.get().fingerprint().equals(fingerprint)) throw refused();
                require(previous.get().binding(), ActingPermission.WORK);
                return new Prepared(previous.get(), null);
              }
              var definition = agents.resolve(authority.subject(), request.agent());
              RetainedAgent.requireRoot(definition);
              String conversation = request.conversation();
              ActingWorkRepository.Binding binding;
              if (conversation == null) {
                conversation = repository.open(authority.subject(), budget);
                binding = new ActingWorkRepository.Binding(conversation, authority);
                repository.bind(binding);
              } else {
                binding = repository.binding(conversation).orElseThrow(PersonalActingWork::refused);
                if (!binding.authority().equals(authority)) throw refused();
              }
              require(binding, ActingPermission.WORK);
              var receipt =
                  new Receipt(
                      request.requestId(), conversation, definition.name(), State.ADMITTED, null);
              var reservation = new ActingWorkRepository.Admission(binding, fingerprint, receipt);
              repository.admit(reservation);
              return new Prepared(reservation, definition);
            });
    if (prepared.definition() == null) return prepared.admission().receipt();
    transactions.afterCommit(() -> submit(prepared, request));
    return receipt(caller, request.requestId());
  }

  private void submit(Prepared prepared, Start request) {
    var admitted = prepared.admission();
    var authority = admitted.binding().authority();
    try {
      // Resolve again after commit. A source change cannot substitute a different concrete agent.
      var definition = agents.resolve(authority.subject(), admitted.receipt().agent());
      RetainedAgent.requireRoot(definition);
      if (!definition.equals(prepared.definition())) throw refused();
      Runnable check =
          () -> {
            require(admitted.binding(), ActingPermission.WORK);
            if (!definition.equals(agents.resolve(authority.subject(), definition.name())))
              throw refused();
          };
      check.run();
      turns.start(
          admitted.binding().conversation(),
          definition,
          request.task(),
          new RunAdmission(
              check,
              job -> repository.started(authority.actor(), request.requestId(), job),
              () -> repository.finished(authority.actor(), request.requestId()),
              authority.subject(),
              new PersonalRunAuthority(authority.subject(), check, resources)));
    } catch (RuntimeException failed) {
      // Submission may already have taken effect. Mark evidence, never compensate by replaying.
      try {
        repository.uncertain(authority.actor(), request.requestId());
      } catch (RuntimeException recordingFailed) {
        failed.addSuppressed(recordingFailed);
      }
      throw failed;
    }
  }

  @Override
  public Receipt receipt(RequestAuthority.Delegated authority, UUID requestId) {
    var admitted =
        repository.receipt(authority.actor(), requestId).orElseThrow(PersonalActingWork::refused);
    requireCaller(authority, admitted, ActingPermission.READ);
    return admitted.receipt();
  }

  @Override
  public void requireJob(
      RequestAuthority.Delegated authority, String job, ActingPermission permission) {
    var admitted = repository.job(job).orElseThrow(PersonalActingWork::refused);
    requireCaller(authority, admitted, permission);
  }

  private void requireCaller(
      RequestAuthority.Delegated caller,
      ActingWorkRepository.Admission admitted,
      ActingPermission permission) {
    var retained = admitted.binding().authority();
    if (!caller.actor().equals(retained.actor())
        || !caller.subject().equals(retained.subject())
        || !caller.delegation().application().equals(retained.delegation().application())
        || !caller.delegation().grant().equals(retained.delegation().grant())
        || !caller.delegation().proof().equals(retained.delegation().proof())) throw refused();
    require(caller, permission);
    require(admitted.binding(), permission);
  }

  private void require(ActingWorkRepository.Binding binding, ActingPermission permission) {
    if (!repository.owns(binding)
        || permission == ActingPermission.WORK && !repository.active(binding)) throw refused();
    require(binding.authority(), permission);
  }

  private void require(RequestAuthority.Delegated authority, ActingPermission permission) {
    policy.require(authority, permission, new ActingTarget.Personal(authority.subject()));
  }

  private static RequestAuthority.Delegated personal(RequestAuthority.Delegated authority) {
    var delegation = authority.delegation();
    if (!delegation.scope().personal()) throw refused();
    return new RequestAuthority.Delegated(
        authority.actor(),
        authority.subject(),
        new RequestAuthority.Delegation(
            delegation.application(),
            delegation.grant(),
            delegation.proof(),
            new ActingScope(delegation.scope().ceiling(), Set.of(), true)));
  }

  private static String fingerprint(RequestAuthority.Delegated authority, Start request) {
    // Length framing prevents ambiguity; canonical fields do not depend on record toString().
    StringBuilder canonical = new StringBuilder();
    var delegation = authority.delegation();
    for (String value :
        new String[] {
          authority.actor(),
          authority.subject(),
          delegation.application(),
          delegation.grant(),
          delegation.proof().toString(),
          delegation.scope().ceiling().name(),
          request.agent(),
          request.task(),
          request.conversation() == null ? "" : request.conversation()
        }) canonical.append(value.length()).append(':').append(value);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static CallerFault refused() {
    return new CallerFault("Acting work is unavailable for this authority");
  }
}
