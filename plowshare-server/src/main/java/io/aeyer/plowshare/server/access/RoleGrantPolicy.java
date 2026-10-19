package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.IdentityRepository.AccountKind;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;

/** Pure issuance ceiling, applied again by persistence constraints at the write boundary. */
public final class RoleGrantPolicy {
  private RoleGrantPolicy() {}

  /** Agent identity is explicit; a service account alone is not evidence of an agent. */
  public static StandardRole effective(
      StandardRole requested,
      boolean agent,
      boolean forceManager,
      AccountKind issuerKind,
      boolean issuerAgent,
      StandardRole issuingRole) {
    Objects.requireNonNull(requested);
    Objects.requireNonNull(issuerKind);
    Objects.requireNonNull(issuingRole);
    if (requested == StandardRole.SYSTEM || requested == StandardRole.GLOBAL_READ)
      throw new CallerFault("This role is not assignable through scoped role management");
    if (agent && requested == StandardRole.ADMIN)
      throw new CallerFault("Agents cannot receive ADMIN");
    if (issuerAgent && (requested == StandardRole.ADMIN || forceManager))
      throw new CallerFault("Agents cannot issue administrative authority or authorize exceptions");
    if (forceManager
        && (!agent
            || requested != StandardRole.MANAGER
            || issuerKind != AccountKind.USER
            || issuerAgent))
      throw new CallerFault("A MANAGER exception requires an explicit human issuer");
    StandardRole effective =
        agent && requested == StandardRole.MANAGER && !forceManager
            ? StandardRole.CONTRIBUTOR
            : requested;
    boolean permits =
        switch (issuingRole) {
          case SYSTEM, ADMIN -> true;
          case MANAGER -> effective != StandardRole.ADMIN;
          case CONTRIBUTOR, READER, GLOBAL_READ -> false;
        };
    if (!permits) throw new CallerFault("Issuing grant cannot delegate that role");
    return effective;
  }
}
