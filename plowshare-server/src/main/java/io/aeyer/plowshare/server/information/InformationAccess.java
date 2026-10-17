package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.access.ScopedPermissions;
import io.aeyer.plowshare.server.access.ScopedPermissions.Action;
import io.aeyer.plowshare.server.access.ScopedPermissions.Principal;
import io.aeyer.plowshare.server.access.ScopedPermissions.ProjectTarget;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.util.Objects;

/**
 * Authenticated selection admission and parameterized, live permission checks over every input
 * revision.
 */
public final class InformationAccess {
  private final ScopedPermissions permissions;

  public InformationAccess(ScopedPermissions permissions) {
    this.permissions = Objects.requireNonNull(permissions, "permissions");
  }

  public InformationContext resolve(String account, InformationContext.Selection selection) {
    if (account == null || account.isBlank()) {
      throw new CallerFault("information needs a socket or run authenticated as an account");
    }
    InformationContext context =
        new InformationContext(
            account, selection == null ? InformationContext.Selection.personal() : selection);
    requireSelection(context);
    return context;
  }

  public InformationContext forRun(String account, io.aeyer.plowshare.protocol.Home home) {
    // A run's Personal home is a server-owned workspace project, while its retained information
    // belongs to the account namespace. Require the exact owner before mapping either address.
    String project = PersonalSpaces.resolveAddress(home.project(), account);
    PersonalSpaces.requireOwn(project, account);
    boolean personal = home.isGlobal() || PersonalSpaces.name(account).equals(project);
    return resolve(
            account,
            personal ? InformationContext.Selection.personal() : projectSelection(account, project))
        .withView(true);
  }

  /** Internal project contexts must not accidentally include the server's shared catalogue. */
  public static InformationContext.Selection projectSelection(String account, String project) {
    return new InformationContext.Selection(
        InformationContext.Scope.PROJECT,
        project,
        !io.aeyer.plowshare.server.auth.ServiceCredentials.principal(account));
  }

  /** Check every operation again; an earlier context is not a membership lease. */
  public void requireSelection(InformationContext context) {
    Objects.requireNonNull(context, "context");
    if (io.aeyer.plowshare.server.auth.ServiceCredentials.principal(context.account())
        && context.selection().scope() == InformationContext.Scope.PERSONAL)
      throw new CallerFault("Service tokens cannot select Personal information");
    if (context.selection().scope() == InformationContext.Scope.PROJECT
        && !permissions.allows(
            new Principal(context.account()),
            new ProjectTarget(context.selection().project()),
            Action.READ)) {
      throw new CallerFault("this account may not read the selected project's information");
    }
  }

  public void requireWork(InformationContext context) {
    requireSelection(context);
    if (context.selection().scope() == InformationContext.Scope.SHARED)
      throw new CallerFault("Shared information is a read selection; select a writable home");
    if (context.selection().scope() == InformationContext.Scope.PROJECT
        && !permissions.allows(
            new Principal(context.account()),
            new ProjectTarget(context.selection().project()),
            Action.WRITE))
      throw new CallerFault(
          "This account needs CONTRIBUTOR access to change the selected project's information");
  }

  /** Content WRITE does not grant permission to change an existing resource's audience. */
  public void requireAudience(InformationContext context) {
    requireSelection(context);
    // Shared selects existing resources only. The repository checks the exact Global ADMIN
    // target; admitting the selection confers neither content WRITE nor audience authority.
    if (context.selection().scope() == InformationContext.Scope.PROJECT
        && !permissions.allows(
            new Principal(context.account()),
            new ProjectTarget(context.selection().project()),
            Action.SET_AUDIENCE))
      throw new CallerFault("This account needs MANAGER access to change information audiences");
  }

  /** Returns the typed criteria only after rechecking live selection admission. */
  public InformationContext admitted(InformationContext context) {
    requireSelection(context);
    return context;
  }
}
