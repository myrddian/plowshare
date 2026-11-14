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
  public static final RoleId ADMIN = new RoleId(id(7));
  public static final RoleId MANAGER = new RoleId(id(8));
  public static final RoleId CONTRIBUTOR = new RoleId(id(9));
  public static final RoleId READER = new RoleId(id(10));

  /**
   * The isolated scope that {@link #PROJECT_CREATOR} grants target. It contains no resource, and no
   * other role may be granted at it (V263).
   */
  public static final ScopeId PROJECT_CREATION = new ScopeId(id(11));

  /** Permits creating new projects and nothing else; its only action is {@code PROJECT_CREATE}. */
  public static final RoleId PROJECT_CREATOR = new RoleId(id(12));

  private static final List<String> RESERVED =
      List.of(
          id(1).toString(),
          id(2).toString(),
          id(3).toString(),
          id(4).toString(),
          id(5).toString(),
          id(6).toString(),
          id(7).toString(),
          id(8).toString(),
          id(9).toString(),
          id(10).toString(),
          id(11).toString(),
          id(12).toString());

  private BuiltInIdentities() {}

  private static UUID id(long suffix) {
    return new UUID(0, suffix);
  }

  /** Deny ordinary lifecycle use of built-in aliases and reserved IDs, including case variants. */
  public static boolean reservedAccount(String value) {
    return value != null
        && (value.equalsIgnoreCase("SYSTEM")
            || value.equalsIgnoreCase("GLOBAL")
            || RESERVED.contains(value.toLowerCase(java.util.Locale.ROOT)));
  }

  public static void requireOrdinaryAccount(String value) {
    if (reservedAccount(value))
      throw new CallerFault(
          "Built-in accounts cannot use ordinary account lifecycle or credentials");
  }
}
