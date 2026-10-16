package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.protocol.ApplicationIdentity.State;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.auth.Tokens;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Consent mutations serialize with account/identity changes and source activation. Authorization,
 * state and receipts share a transaction. A credential is never cached, put in an event or
 * replayed. Scope checks at issuance do not replace the policy's checks at subsequent execution
 * boundaries.
 */
public final class OwnedActingTokens implements ActingTokens {
  private final ActingRepository repository;
  private final ProjectMembers members;
  private final UnitOfWork work;
  private final Clock clock;

  public OwnedActingTokens(
      ActingRepository repository, ProjectMembers members, UnitOfWork work, Clock clock) {
    this.repository = Objects.requireNonNull(repository);
    this.members = Objects.requireNonNull(members);
    this.work = Objects.requireNonNull(work);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Credential issue(RequestAuthority.Direct caller, Issue request) {
    Objects.requireNonNull(request);
    String fingerprint =
        fingerprint(
            "ISSUE",
            request.application(),
            request.grant(),
            request.scope().ceiling().name(),
            sorted(request.scope().projects()),
            Boolean.toString(request.scope().personal()),
            Integer.toString(request.expiresInDays()),
            request.externalSubject().orElse(""));
    return work.inTransaction(
        () -> {
          repository.lock();
          human(caller);
          if (prior(caller, request.requestId(), fingerprint).isPresent())
            throw new CallerFault(
                "Acting credential was already issued; read its receipt and explicitly revoke or replace it");
          if (!repository.enabled()) throw refused();
          Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          var binding =
              repository.application(request.application()).orElseThrow(OwnedActingTokens::refused);
          if (!binding.project().equals(request.application())
              || binding.state() != State.ACTIVE
              || !binding.credentialIssued()
              || binding.revokedAt() != null
              || !binding.expiresAt().toInstant().isAfter(now)
              || !repository.accountActive(binding.principal())) throw refused();
          var grant =
              repository
                  .grant(request.application(), request.grant())
                  .orElseThrow(OwnedActingTokens::refused);
          if (!grant.application().equals(request.application())
              || !grant.name().equals(request.grant())
              || !grant.proofs().contains(ActingPolicySource.ProofKind.ACTING_TOKEN)
              || !grant.ceiling().allows(request.scope().ceiling())
              || request.scope().personal() && !grant.personal()
              || !repository.eligible(request.application(), request.grant(), caller.subject()))
            throw refused();
          for (String project : request.scope().projects()) {
            requireRole(project, caller.subject(), request.scope().ceiling());
            requireRole(project, binding.principal(), request.scope().ceiling());
          }
          if (request.scope().personal())
            requireRole(
                PersonalSpaces.name(caller.subject()), caller.subject(), request.scope().ceiling());
          var token =
              new ActingPolicySource.Token(
                  UUID.randomUUID(),
                  request.application(),
                  binding.principal(),
                  request.grant(),
                  caller.subject(),
                  request.scope(),
                  now.plus(request.expiresInDays(), ChronoUnit.DAYS),
                  false);
          String secret = "psa_" + Tokens.mint();
          repository.insert(token, Tokens.hash(secret), request.externalSubject(), now);
          var receipt =
              new Receipt(
                  request.requestId(),
                  Action.ISSUE,
                  request.application(),
                  request.grant(),
                  Optional.of(token.id()),
                  now);
          repository.complete(
              new ActingRepository.Submission(caller.actor(), fingerprint, receipt));
          return new Credential(token, receipt, secret);
        });
  }

  @Override
  public Receipt revoke(RequestAuthority.Direct caller, UUID tokenId, UUID requestId) {
    Objects.requireNonNull(tokenId);
    Objects.requireNonNull(requestId);
    String fingerprint = fingerprint("REVOKE", tokenId.toString());
    return work.inTransaction(
        () -> {
          repository.lock();
          human(caller);
          var prior = prior(caller, requestId, fingerprint);
          if (prior.isPresent()) return prior.get();
          var token = repository.token(tokenId).orElseThrow(OwnedActingTokens::refused);
          if (!token.subject().equals(caller.subject())
              && !repository.administrator(caller.actor())) throw refused();
          Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          repository.revoke(tokenId, now);
          var receipt =
              new Receipt(
                  requestId,
                  Action.REVOKE,
                  token.application(),
                  token.grant(),
                  Optional.of(tokenId),
                  now);
          repository.complete(
              new ActingRepository.Submission(caller.actor(), fingerprint, receipt));
          return receipt;
        });
  }

  @Override
  public Receipt eligibility(RequestAuthority.Direct caller, Eligibility request) {
    Objects.requireNonNull(request);
    String fingerprint =
        fingerprint(
            "ELIGIBILITY",
            request.application(),
            request.grant(),
            sorted(request.accounts()),
            sorted(request.memberProjects()));
    return work.inTransaction(
        () -> {
          repository.lock();
          human(caller);
          var prior = prior(caller, request.requestId(), fingerprint);
          if (prior.isPresent()) return prior.get();
          if (!repository.administrator(caller.actor())
              || repository.grant(request.application(), request.grant()).isEmpty())
            throw refused();
          for (String account : request.accounts())
            if (!repository.subjectActive(account)) throw refused();
          repository.eligibility(request);
          var receipt =
              new Receipt(
                  request.requestId(),
                  Action.ELIGIBILITY,
                  request.application(),
                  request.grant(),
                  Optional.empty(),
                  clock.instant().truncatedTo(ChronoUnit.MICROS));
          repository.complete(
              new ActingRepository.Submission(caller.actor(), fingerprint, receipt));
          return receipt;
        });
  }

  @Override
  public Optional<Receipt> receipt(RequestAuthority.Direct caller, UUID requestId) {
    human(caller);
    return prior(caller, Objects.requireNonNull(requestId), null);
  }

  @Override
  public List<ActingPolicySource.Token> tokens(RequestAuthority.Direct caller) {
    human(caller);
    return repository.tokens(caller.subject());
  }

  private void human(RequestAuthority.Direct caller) {
    Objects.requireNonNull(caller);
    if (!repository.subjectActive(caller.subject())) throw refused();
  }

  private void requireRole(String project, String account, ProjectRole ceiling) {
    if (!members.role(project, account).map(role -> role.allows(ceiling)).orElse(false))
      throw refused();
  }

  private Optional<Receipt> prior(
      RequestAuthority.Direct caller, UUID requestId, String fingerprint) {
    return repository
        .receipt(requestId)
        .map(
            found -> {
              if (!found.actor().equals(caller.actor())
                  || fingerprint != null && !found.fingerprint().equals(fingerprint))
                throw new CallerFault(
                    "Acting request UUID already belongs to a different submission");
              return found.receipt();
            });
  }

  // Length framing handles arbitrary validated external subjects without delimiter collisions.
  private static String fingerprint(String... fields) {
    var value = new StringBuilder();
    for (String field : fields) value.append(field.length()).append(':').append(field);
    return Tokens.hash(value.toString());
  }

  private static String sorted(Set<String> values) {
    var result = new StringBuilder();
    values.stream()
        .sorted()
        .forEach(value -> result.append(value.length()).append(':').append(value));
    return result.toString();
  }

  private static CallerFault refused() {
    return new CallerFault("Acting token operation is not permitted");
  }
}
