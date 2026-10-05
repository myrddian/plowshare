package io.aeyer.plowshare.server.auth;

import io.aeyer.plowshare.server.auth.ServerAdministration.*;
import java.util.List;

/** Durable account changes. Mutations and audit entries join the caller's transaction. */
public interface AccountAdministrationRepository {
  /** Serializes administrator changes with initial setup; call inside a transaction. */
  void lockAdministrators();

  Account account(String handle);

  List<Account> list();

  void create(String handle, String passwordHash, boolean administrator);

  long enabledAdministrators(boolean passwordSetupComplete);

  void update(String handle, boolean enabled, boolean administrator);

  void resetPassword(String handle, String passwordHash);

  /** Invalidates session chains and increments the durable socket/session fence. */
  void revokeSessions(String handle);

  void audit(String actor, String action, Account target);

  List<Session> sessions(String handle);

  AuditPage history(String handle, long before, int limit);
}
