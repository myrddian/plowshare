package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.UUID;

/** Deployment-independent reserved identity registry. Equality never authorizes an operation. */
public final class BuiltInIdentities {
  public static final AccountId SYSTEM = new AccountId(id(1));
  public static final RoleId SYSTEM_ROLE = new RoleId(id(2));
  public static final ScopeId ADMINISTRATION = new ScopeId(id(3));
  public static final AccountId GLOBAL = new AccountId(id(4));
  public static final ScopeId GLOBAL_KNOWLEDGE = new ScopeId(id(5));
  public static final RoleId GLOBAL_READ = new RoleId(id(6));
  private static final List<String> RESERVED =
      List.of(
          id(1).toString(),
          id(2).toString(),
          id(3).toString(),
          id(4).toString(),
          id(5).toString(),
          id(6).toString());

  private BuiltInIdentities() {}

  private static UUID id(long suffix) {
    return new UUID(0, suffix);
  }

  /** Deny ordinary lifecycle use of built-in aliases and reserved IDs, including case variants. */
  public static boolean reservedAccount(String value) {
    return value != null
        && (value.equalsIgnoreCase("SYSTEM")
            || value.equalsIgnoreCase("GLOBAL")
            || RESERVED.contains(value));
  }

  public static void requireOrdinaryAccount(String value) {
    if (reservedAccount(value))
      throw new CallerFault(
          "Built-in accounts cannot use ordinary account lifecycle or credentials");
  }
}
