package io.aeyer.plowshare.server.auth;

import io.aeyer.plowshare.server.auth.ServiceAccounts.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Service account persistence. Locks and mutations join the caller's transaction. */
public interface ServiceAccountRepository {
  void lockAdministrators();

  Account account(String handle, boolean lock);

  List<Account> list();

  void create(String handle);

  void update(String handle, boolean enabled);

  /** Revokes owned tokens, returning principals whose transient credentials must be retired. */
  List<String> revokeOwnedTokens(String handle);

  void retirePrincipal(String principal);

  void audit(String actor, String action, String target);

  Token token(String handle, UUID id, boolean lock);

  List<Token> tokens(String handle);

  /** Locks an ordinary project against deletion while scopes are checked and saved. */
  boolean lockOrdinaryProject(String project);

  boolean hasTokenName(String handle, String name);

  void issue(
      UUID id,
      String handle,
      String name,
      String principal,
      String digest,
      OffsetDateTime expires,
      List<Scope> scopes);

  void rotate(UUID id, String digest, OffsetDateTime expires);

  void revoke(UUID id);
}
