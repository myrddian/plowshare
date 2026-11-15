package io.aeyer.plowshare.server.auth;

import io.aeyer.plowshare.server.access.BuiltInIdentities;
import io.aeyer.plowshare.server.access.ProjectCreatorGrants;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Human operator account lifecycle. Every mutation and its audit entry commit together. */
@Service
public class ServerAdministration {
  private final AccountAdministrationRepository repository;
  private final UnitOfWork transactions;
  private final AdminStore accounts;
  private final PasswordHasher passwords;
  private final TokenStore tokens;
  private final ApplicationEventPublisher events;
  private final ProjectCreatorGrants creators;

  public ServerAdministration(
      AccountAdministrationRepository repository,
      UnitOfWork transactions,
      AdminStore accounts,
      PasswordHasher passwords,
      TokenStore tokens,
      ApplicationEventPublisher events,
      ProjectCreatorGrants creators) {
    this.repository = repository;
    this.transactions = transactions;
    this.accounts = accounts;
    this.passwords = passwords;
    this.tokens = tokens;
    this.events = events;
    this.creators = creators;
  }

  /**
   * A permanent USER account. {@code projectCreator} is whether the account holds the
   * PROJECT_CREATOR role ({@link ProjectCreatorGrants#holds}); the account repository does not read
   * grants, so its rows carry {@code false} until this service fills the field.
   */
  public record Account(
      String handle,
      boolean enabled,
      boolean serverAdmin,
      boolean mustChangePassword,
      OffsetDateTime createdAt,
      boolean projectCreator) {
    Account withProjectCreator(boolean holder) {
      return new Account(handle, enabled, serverAdmin, mustChangePassword, createdAt, holder);
    }
  }

  public record Credential(Account account, String temporaryPassword) {
    @Override
    public String toString() {
      return "Credential[account=" + account + ", temporaryPassword=<redacted>]";
    }
  }

  public record Session(
      UUID id, boolean restricted, OffsetDateTime createdAt, OffsetDateTime expiresAt) {}

  public record Audit(
      long id,
      OffsetDateTime occurredAt,
      String actor,
      String action,
      String target,
      Boolean enabled,
      Boolean serverAdmin) {}

  public record AuditPage(List<Audit> entries, long before) {}

  public record Revoked(String handle) {}

  public record SessionsRevoked(String handle) {}

  private Account account(String handle) {
    BuiltInIdentities.requireOrdinaryAccount(handle);
    return repository.account(handle).withProjectCreator(creators.holds(handle));
  }

  /** Every permanent account, with the creator role resolved in one read for the whole listing. */
  public List<Account> list(String actor) {
    accounts.requireServerAdmin(actor);
    List<Account> listed = repository.list();
    Set<String> holders = creators.holdersAmong(listed.stream().map(Account::handle).toList());
    return listed.stream()
        .map(account -> account.withProjectCreator(holders.contains(account.handle())))
        .toList();
  }

  private <T> T change(String actor, Supplier<T> work) {
    return transactions.inTransaction(
        () -> {
          // Shared with setup: concurrent administrators cannot remove the last administrator.
          repository.lockAdministrators();
          accounts.requireServerAdmin(actor);
          return work.get();
        });
  }

  private void audit(String actor, String action, Account target) {
    repository.audit(actor, action, target);
  }

  public Credential create(String actor, String handle, boolean serverAdmin) {
    BuiltInIdentities.requireOrdinaryAccount(handle);
    accounts.requireServerAdmin(actor);
    if (!handle.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
      throw new CallerFault(
          "Account handles need 1–64 letters, digits, dots, underscores or hyphens");
    String password = Tokens.mint();
    String hash = passwords.hash(password.toCharArray());
    return change(
        actor,
        () -> {
          if (accounts.byHandle(handle).isPresent())
            throw new CallerFault("That account handle already exists");
          repository.create(handle, hash, serverAdmin);
          events.publishEvent(new PersonalSpaces.AccountCreated(handle));
          Account created = account(handle);
          audit(actor, "account.create", created);
          return new Credential(created, password);
        });
  }

  /**
   * Changes the supplied flags in one transaction under the administrator lock. Account flags apply
   * first, audited as {@code account.update} with the session revocation they imply; then a
   * supplied {@code projectCreator} grants or revokes the role (both idempotent) and, only when
   * that changed something, is audited as {@code account.project-creator.grant} or {@code .revoke}.
   * Any refusal rolls back every change.
   */
  public Account update(
      String actor, String handle, Boolean enabled, Boolean serverAdmin, Boolean projectCreator) {
    if (enabled == null && serverAdmin == null && projectCreator == null)
      throw new CallerFault("Supply enabled, serverAdmin or projectCreator");
    return change(
        actor,
        () -> {
          Account current = account(handle);
          if (enabled != null || serverAdmin != null)
            current = updateFlags(actor, current, enabled, serverAdmin);
          if (projectCreator == null) return current;
          boolean changed =
              projectCreator ? creators.grant(actor, handle) : creators.revoke(actor, handle);
          // A no-op (already a holder, or not one) is neither audited nor re-read: the account
          // read above already carries the holder state. A change leaves the requested state.
          if (!changed) return current;
          Account updated = current.withProjectCreator(projectCreator);
          audit(
              actor,
              projectCreator ? "account.project-creator.grant" : "account.project-creator.revoke",
              updated);
          return updated;
        });
  }

  private Account updateFlags(
      String actor, Account previous, Boolean enabled, Boolean serverAdmin) {
    String handle = previous.handle();
    boolean nextEnabled = enabled == null ? previous.enabled() : enabled;
    boolean nextAdmin = serverAdmin == null ? previous.serverAdmin() : serverAdmin;
    if (previous.enabled()
        && previous.serverAdmin()
        && !(nextEnabled && nextAdmin)
        && repository.enabledAdministrators(false) <= 1)
      throw new CallerFault("The last enabled server administrator cannot be disabled or demoted");
    if (previous.enabled()
        && previous.serverAdmin()
        && !previous.mustChangePassword()
        && !(nextEnabled && nextAdmin)
        && repository.enabledAdministrators(true) <= 1)
      throw new CallerFault(
          "Keep an enabled administrator with a completed password setup before removing this role");
    repository.update(handle, nextEnabled, nextAdmin);
    if (!nextEnabled || previous.serverAdmin() != nextAdmin) revoke(handle);
    Account updated = account(handle);
    audit(actor, "account.update", updated);
    return updated;
  }

  private void revoke(String handle) {
    repository.revokeSessions(handle);
    // Local transient credentials and sockets are retired only after the database commit.
    events.publishEvent(new SessionsRevoked(handle));
  }

  public Credential reset(String actor, String handle) {
    BuiltInIdentities.requireOrdinaryAccount(handle);
    accounts.requireServerAdmin(actor);
    if (actor.equals(handle))
      throw new CallerFault("Change your own password through the authenticated password command");
    String password = Tokens.mint();
    String hash = passwords.hash(password.toCharArray());
    return change(
        actor,
        () -> {
          Account target = account(handle);
          repository.resetPassword(handle, hash);
          revoke(handle);
          audit(actor, "account.password.reset", target);
          return new Credential(account(handle), password);
        });
  }

  public Revoked revokeSessions(String actor, String handle) {
    return change(
        actor,
        () -> {
          Account target = account(handle);
          revoke(handle);
          audit(actor, "session.revoke", target);
          return new Revoked(handle);
        });
  }

  public List<Session> sessions(String actor, String handle) {
    accounts.requireServerAdmin(actor);
    account(handle);
    return repository.sessions(handle);
  }

  public AuditPage history(String actor, String handle, long before, int limit) {
    accounts.requireServerAdmin(actor);
    if (before < 0 || limit < 1 || limit > 100)
      throw new CallerFault("Audit limit must be 1–100 and before must be nonnegative");
    return repository.history(handle, before, limit);
  }

  @org.springframework.transaction.event.TransactionalEventListener(
      phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
  public void retireCredentials(SessionsRevoked event) {
    tokens.retireTransientAccount(event.handle());
  }
}
