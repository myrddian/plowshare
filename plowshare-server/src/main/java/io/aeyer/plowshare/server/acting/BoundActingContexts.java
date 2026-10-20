package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.protocol.ActingContext;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.Tokens;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory contexts end with the socket or after five minutes, never later than proof expiry. Only
 * token metadata survives verification; secrets are neither retained nor cached. Open does not
 * grant an effect lease. Each supported request rechecks its target before and after reading.
 */
public final class BoundActingContexts implements ActingContexts {
  private record Entry(
      Connection connection, RequestAuthority.Delegated authority, Instant expiresAt) {}

  private final ConcurrentMap<UUID, Entry> contexts = new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID, Connection> opening = new ConcurrentHashMap<>();
  private final ActingRepository repository;
  private final ActingAuthorization authorization;
  private final Clock clock;

  public BoundActingContexts(
      ActingRepository repository, ActingAuthorization authorization, Clock clock) {
    this.repository = Objects.requireNonNull(repository);
    this.authorization = Objects.requireNonNull(authorization);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public ActingContext.Opened open(Connection connection, ActingContext.Open request) {
    Objects.requireNonNull(connection);
    Objects.requireNonNull(request);
    UUID reservation = UUID.randomUUID();
    try {
      synchronized (this) {
        contexts.values().removeIf(entry -> !entry.expiresAt().isAfter(clock.instant()));
        long local =
            contexts.values().stream()
                    .filter(entry -> entry.connection().id().equals(connection.id()))
                    .count()
                + opening.values().stream()
                    .filter(value -> value.id().equals(connection.id()))
                    .count();
        if (contexts.size() + opening.size() >= 8192 || local >= 64) throw refused();
        opening.put(reservation, connection);
      }
      return admit(connection, request, reservation);
    } catch (CallerFault refused) {
      repository.contextAudit(
          new ActingRepository.ContextAudit(
              connection.actor(),
              request.application(),
              request.grant(),
              ActingRepository.ContextAction.REFUSED,
              java.util.Optional.empty(),
              java.util.Optional.empty()));
      throw refused;
    } finally {
      opening.remove(reservation, connection);
    }
  }

  private ActingContext.Opened admit(
      Connection connection, ActingContext.Open request, UUID reservation) {
    if (!repository.enabled()) throw refused();
    var token =
        repository
            .proof(Tokens.hash(request.proof().credential()))
            .orElseThrow(BoundActingContexts::refused);
    if (token.revoked()
        || !token.expiresAt().isAfter(clock.instant())
        || !token.actor().equals(connection.actor())
        || !token.application().equals(request.application())
        || !token.grant().equals(request.grant())) throw refused();
    var grant =
        repository
            .grant(request.application(), request.grant())
            .orElseThrow(BoundActingContexts::refused);
    var scope =
        new ActingScope(
            narrow(token.scope().ceiling(), grant.ceiling()),
            token.scope().projects(),
            token.scope().personal() && grant.personal());
    var provisional = authority(connection, token, scope);
    ProjectRole ceiling = scope.ceiling();
    for (String project : scope.projects())
      ceiling =
          narrow(
              ceiling,
              authorization.require(
                  provisional, ActingPermission.READ, new ActingTarget.Project(project)));
    if (scope.personal())
      ceiling =
          narrow(
              ceiling,
              authorization.require(
                  provisional, ActingPermission.READ, new ActingTarget.Personal(token.subject())));
    if (scope.projects().isEmpty() && !scope.personal()) throw refused();
    var admitted =
        authority(connection, token, new ActingScope(ceiling, scope.projects(), scope.personal()));
    Instant expiresAt = clock.instant().plusSeconds(300);
    if (token.expiresAt().isBefore(expiresAt)) expiresAt = token.expiresAt();
    UUID id = UUID.randomUUID();
    // The proof/grant may have changed during authorization IO. Refuse rather than retarget the
    // retained subject/scope. This freshness read is not a reusable permission cache.
    if (!repository.enabled()
        || !repository
            .proof(Tokens.hash(request.proof().credential()))
            .filter(token::equals)
            .isPresent()
        || !repository
            .grant(request.application(), request.grant())
            .filter(grant::equals)
            .isPresent()) throw refused();
    repository.contextAudit(
        new ActingRepository.ContextAudit(
            connection.actor(),
            request.application(),
            request.grant(),
            ActingRepository.ContextAction.OPEN,
            java.util.Optional.of(id),
            java.util.Optional.of(token.subject())));
    synchronized (this) {
      if (opening.get(reservation) != connection || !expiresAt.isAfter(clock.instant()))
        throw refused();
      contexts.put(id, new Entry(connection, admitted, expiresAt));
    }
    return new ActingContext.Opened(id, token.subject(), expiresAt);
  }

  @Override
  public RequestAuthority.Delegated resolve(Connection connection, UUID context) {
    var entry = contexts.get(Objects.requireNonNull(context));
    if (entry == null
        || !entry.connection().equals(connection)
        || !entry.expiresAt().isAfter(clock.instant())
        || !repository.enabled()) throw refused();
    return entry.authority();
  }

  @Override
  public void close(Connection connection, UUID context) {
    var entry = contexts.get(Objects.requireNonNull(context));
    if (entry == null || !entry.connection().equals(connection)) throw refused();
    if (!contexts.remove(context, entry)) throw refused();
    var delegation = entry.authority().delegation();
    repository.contextAudit(
        new ActingRepository.ContextAudit(
            connection.actor(),
            delegation.application(),
            delegation.grant(),
            ActingRepository.ContextAction.CLOSE,
            java.util.Optional.of(context),
            java.util.Optional.of(entry.authority().subject())));
  }

  @Override
  public synchronized void disconnected(String connection) {
    contexts.values().removeIf(entry -> entry.connection().id().equals(connection));
    opening.values().removeIf(entry -> entry.id().equals(connection));
  }

  private static RequestAuthority.Delegated authority(
      Connection connection, ActingPolicySource.Token token, ActingScope scope) {
    return new RequestAuthority.Delegated(
        connection.actor(),
        token.subject(),
        new RequestAuthority.Delegation(token.application(), token.grant(), token.id(), scope));
  }

  private static ProjectRole narrow(ProjectRole left, ProjectRole right) {
    return left.allows(right) ? right : left;
  }

  private static CallerFault refused() {
    return new CallerFault("Acting context is unavailable for this connection");
  }
}
