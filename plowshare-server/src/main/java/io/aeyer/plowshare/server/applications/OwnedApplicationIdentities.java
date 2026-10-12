package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.protocol.ApplicationIdentity.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;

/** Administrator authorization never follows from ordinary Application membership. */
public final class OwnedApplicationIdentities implements ApplicationIdentities {
  private final ApplicationIdentityRepository repository;
  private final ServiceAccountRepository services;
  private final AdminStore accounts;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final UnitOfWork work;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public OwnedApplicationIdentities(
      ApplicationIdentityRepository repository,
      ServiceAccountRepository services,
      AdminStore accounts,
      ProjectMembers members,
      ProjectWorkspaces projects,
      UnitOfWork work,
      ApplicationEventPublisher events) {
    this(repository, services, accounts, members, projects, work, events, Clock.systemUTC());
  }

  /** Uses the supplied time source; issued receipts share PostgreSQL's microsecond precision. */
  public OwnedApplicationIdentities(
      ApplicationIdentityRepository repository,
      ServiceAccountRepository services,
      AdminStore accounts,
      ProjectMembers members,
      ProjectWorkspaces projects,
      UnitOfWork work,
      ApplicationEventPublisher events,
      Clock clock) {
    this.clock = java.util.Objects.requireNonNull(clock);
    this.repository = repository;
    this.services = services;
    this.accounts = accounts;
    this.members = members;
    this.projects = projects;
    this.work = work;
    this.events = events;
  }

  private void require(String actor, String project) {
    accounts.requireServerAdmin(actor);
    try {
      io.aeyer.plowshare.protocol.ApplicationIdentity.project(project);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault(invalid.getMessage());
    }
  }

  public void lockActivation(String actor, String project) {
    services.lockAdministrators();
    require(actor, project);
  }

  public Binding reserve(String actor, String project, Declaration declaration) {
    require(actor, project);
    java.util.Objects.requireNonNull(declaration);
    return work.inTransaction(
        () -> {
          services.lockAdministrators();
          require(actor, project);
          var prior = repository.find(project, true);
          if (prior.isPresent()) {
            if (!prior.get().service().equals(declaration))
              throw new CallerFault(
                  "Application service declaration differs from its retained reservation");
            return prior.get();
          }
          if (projects.find(project).isPresent())
            throw new CallerFault(
                "An existing project cannot silently adopt an Application-owned identity");
          if (accounts.byHandle(declaration.handle()).isPresent())
            throw new CallerFault("Application service handle already belongs to an account");
          UUID tokenId = UUID.randomUUID();
          services.create(declaration.handle());
          services.issue(
              tokenId,
              declaration.handle(),
              "application",
              "@service/" + tokenId,
              Tokens.hash(Tokens.mint()),
              OffsetDateTime.now(ZoneOffset.UTC).plusDays(365),
              java.util.List.of());
          repository.reserve(project, declaration, tokenId, actor);
          services.audit(actor, "application.identity.reserve", project);
          return repository.find(project, false).orElseThrow();
        });
  }

  public Status status(String actor, String project) {
    require(actor, project);
    return new Status(project, repository.find(project, false).orElse(null));
  }

  /**
   * Activation joins the deployment transaction; neither placement nor membership survives a failed
   * commit.
   */
  public String activate(String actor, String project, Declaration declaration, String principal) {
    require(actor, project);
    // All identity/token changes take the administrator lock before their own row locks.
    // The deployment repository acquires this before the project lock, avoiding a reverse lock
    // order.
    var retained = repository.find(project, true);
    if (declaration == null) {
      if (retained.isPresent())
        throw new CallerFault("Keep the owned service declaration when updating or rolling back");
      return actor;
    }
    Binding identity =
        retained.orElseThrow(() -> new CallerFault("Application identity is not reserved"));
    if (!identity.service().equals(declaration) || !identity.principal().equals(principal))
      throw new CallerFault("Application source does not match its owned identity");
    if (identity.state() == State.RESERVED)
      members.assign(
          project, declaration.handle(), ProjectRole.valueOf(declaration.role()), actor, true);
    repository.activate(identity);
    return identity.principal();
  }

  public Credential issue(String actor, Issue request) {
    require(actor, request.project());
    return work.inTransaction(
        () -> {
          services.lockAdministrators();
          require(actor, request.project());
          var prior = repository.receipt(request.requestId());
          if (prior.isPresent()) {
            match(actor, request.project(), prior.get());
            if (!prior.get().request().equals(request))
              throw new CallerFault("Credential request UUID belongs to a different submission");
            throw new CallerFault(
                "Credential request already committed; read its receipt and explicitly rotate the retained token if the secret was lost");
          }
          Binding identity =
              repository
                  .find(request.project(), true)
                  .orElseThrow(
                      () ->
                          new CallerFault(
                              "Application has no owned identity; retain its manual service token"));
          if (identity.state() != State.ACTIVE)
            throw new CallerFault("Deploy the Application before issuing its credential");
          var source =
              projects
                  .find(request.project())
                  .orElseThrow(() -> new CallerFault("Application source is unavailable"));
          var active = ApplicationIdentitySource.read(source.workspace());
          if (!identity.service().equals(active.declaration())
              || !identity.principal().equals(active.principal()))
            throw new CallerFault("Active Application no longer grants its owned identity");
          if (!services.account(identity.service().handle(), true).enabled())
            throw new CallerFault(
                "Enable the Application service account before issuing its credential");
          members.requireRole(
              request.project(),
              identity.service().handle(),
              ProjectRole.valueOf(identity.service().role()));
          if (identity.credentialIssued() != request.rotate())
            throw new CallerFault(
                identity.credentialIssued()
                    ? "Explicit rotation is required for an issued Application credential"
                    : "Issue the reserved credential before rotating it");
          // TIMESTAMPTZ retains microseconds. Normalize before both persistence and the
          // one-time response so recovery returns exactly the same receipt, including expiry.
          var receipt =
              new Receipt(
                  request.requestId(),
                  request.project(),
                  identity.tokenId(),
                  identity.principal(),
                  OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))
                      .plusDays(request.expiresInDays())
                      .truncatedTo(ChronoUnit.MICROS));
          String secret = ServiceCredentials.PREFIX + Tokens.mint();
          repository.issued(actor, request, receipt, Tokens.hash(secret));
          services.retirePrincipal(identity.principal());
          services.audit(
              actor,
              request.rotate() ? "application.token.rotate" : "application.token.issue",
              request.project());
          work.afterCommit(
              () ->
                  events.publishEvent(
                      new ServerAdministration.SessionsRevoked(identity.principal())));
          return new Credential(receipt, secret);
        });
  }

  public Receipt receipt(String actor, String project, UUID requestId) {
    require(actor, project);
    java.util.Objects.requireNonNull(requestId);
    var retained =
        repository
            .receipt(requestId)
            .orElseThrow(
                () ->
                    new CallerFault(
                        "Credential receipt not found; absence does not authorize automatic replay"));
    match(actor, project, retained);
    return retained.receipt();
  }

  private static void match(
      String actor, String project, ApplicationIdentityRepository.IssueRecord record) {
    if (!actor.equals(record.actor()) || !project.equals(record.request().project()))
      throw new CallerFault("Credential request UUID belongs to a different submission");
  }
}
