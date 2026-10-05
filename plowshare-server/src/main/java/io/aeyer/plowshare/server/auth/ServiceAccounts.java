package io.aeyer.plowshare.server.auth;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Operator-managed service identities and independently revocable project credentials. */
@Service
public class ServiceAccounts {
  private final ServiceAccountRepository repository;
  private final AdminStore accounts;
  private final ProjectMembers members;
  private final ApplicationEventPublisher events;
  private final UnitOfWork transactions;

  public ServiceAccounts(
      ServiceAccountRepository repository,
      UnitOfWork transactions,
      AdminStore accounts,
      ProjectMembers members,
      ApplicationEventPublisher events) {
    this.repository = repository;
    this.transactions = transactions;
    this.accounts = accounts;
    this.members = members;
    this.events = events;
  }

  public record Account(String handle, boolean enabled, OffsetDateTime createdAt) {}

  public record Scope(String project, ProjectRole role) {}

  public record Token(
      UUID id,
      String name,
      String principal,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt,
      OffsetDateTime revokedAt,
      List<Scope> scopes) {}

  public record Credential(Token token, String credential) {
    @Override
    public String toString() {
      return "Credential[token=" + token + ", credential=<redacted>]";
    }
  }

  private Account account(String handle, boolean lock) {
    return repository.account(handle, lock);
  }

  public List<Account> list(String actor) {
    accounts.requireServerAdmin(actor);
    return repository.list();
  }

  private void audit(String actor, String action, String target) {
    repository.audit(actor, action, target);
  }

  public Account create(String actor, String handle) {
    accounts.requireServerAdmin(actor);
    if (handle == null || !handle.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
      throw new CallerFault("Supply a valid account handle");
    return transactions.inTransaction(
        () -> {
          repository.lockAdministrators();
          accounts.requireServerAdmin(actor);
          if (accounts.byHandle(handle).isPresent())
            throw new CallerFault("That account handle already exists");
          repository.create(handle);
          audit(actor, "service.account.create", handle);
          return account(handle, false);
        });
  }

  private void retire(String principal) {
    repository.retirePrincipal(principal);
    events.publishEvent(new ServerAdministration.SessionsRevoked(principal));
  }

  public Account update(String actor, String handle, boolean enabled) {
    return transactions.inTransaction(
        () -> {
          repository.lockAdministrators();
          accounts.requireServerAdmin(actor);
          account(handle, true);
          repository.update(handle, enabled);
          if (!enabled) {
            var principals = repository.revokeOwnedTokens(handle);
            principals.forEach(this::retire);
          }
          audit(actor, "service.account.update", handle);
          return account(handle, false);
        });
  }

  private Token token(String handle, UUID id, boolean lock) {
    return repository.token(handle, id, lock);
  }

  public List<Token> tokens(String actor, String handle) {
    accounts.requireServerAdmin(actor);
    account(handle, false);
    return repository.tokens(handle);
  }

  private OffsetDateTime expiry(int days) {
    if (days < 1 || days > 365) throw new CallerFault("expiresInDays must be 1–365");
    return OffsetDateTime.now(ZoneOffset.UTC).plusDays(days);
  }

  private void validateScopes(String handle, List<Scope> scopes) {
    if (scopes == null || scopes.isEmpty() || scopes.size() > 100)
      throw new CallerFault("Supply 1–100 project scopes");
    if (scopes.stream()
        .anyMatch(scope -> scope == null || scope.project() == null || scope.role() == null))
      throw new CallerFault("Token scopes need project and role");
    var names = new java.util.HashSet<String>();
    for (Scope scope :
        scopes.stream().sorted(java.util.Comparator.comparing(Scope::project)).toList()) {
      String project = scope.project();
      if (project == null
          || scope.role() == null
          || !names.add(project)
          || project.startsWith("personal:")
          || project.startsWith("Personal:")
          || project.startsWith("client:"))
        throw new CallerFault("Token scopes must name distinct ordinary server projects and roles");
      if (!repository.lockOrdinaryProject(project))
        throw new CallerFault("No ordinary server project has that name");
      members.requireRole(project, handle, scope.role());
    }
  }

  public Credential issue(String actor, String handle, String name, List<Scope> scopes, int days) {
    OffsetDateTime expires = expiry(days);
    if (name == null || name.isBlank() || name.length() > 64)
      throw new CallerFault("Token name needs 1–64 characters");
    return transactions.inTransaction(
        () -> {
          repository.lockAdministrators();
          accounts.requireServerAdmin(actor);
          if (!account(handle, true).enabled())
            throw new CallerFault("Enable this service account before issuing a token");
          validateScopes(handle, scopes);
          if (repository.hasTokenName(handle, name))
            throw new CallerFault("That token name exists; rotate its credential instead");
          UUID id = UUID.randomUUID();
          String principal = "@service/" + id;
          String credential = ServiceCredentials.PREFIX + Tokens.mint();
          repository.issue(id, handle, name, principal, Tokens.hash(credential), expires, scopes);
          audit(actor, "service.token.create", handle + "/" + id);
          return new Credential(token(handle, id, false), credential);
        });
  }

  public Credential rotate(String actor, String handle, UUID id, int days) {
    OffsetDateTime expires = expiry(days);
    return transactions.inTransaction(
        () -> {
          repository.lockAdministrators();
          accounts.requireServerAdmin(actor);
          if (!account(handle, true).enabled())
            throw new CallerFault("Enable this service account before rotating a token");
          Token previous = token(handle, id, true);
          validateScopes(handle, previous.scopes());
          String credential = ServiceCredentials.PREFIX + Tokens.mint();
          repository.rotate(id, Tokens.hash(credential), expires);
          retire(previous.principal());
          audit(actor, "service.token.rotate", handle + "/" + id);
          return new Credential(token(handle, id, false), credential);
        });
  }

  public Token revoke(String actor, String handle, UUID id) {
    return transactions.inTransaction(
        () -> {
          repository.lockAdministrators();
          accounts.requireServerAdmin(actor);
          account(handle, true);
          Token previous = token(handle, id, true);
          repository.revoke(id);
          retire(previous.principal());
          audit(actor, "service.token.revoke", handle + "/" + id);
          return token(handle, id, false);
        });
  }
}
