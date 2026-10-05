package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;

/**
 * Authenticated selection admission and parameterized, live permission checks over every input
 * revision.
 */
public final class InformationAccess {
  private final ProjectMembers members;

  public InformationAccess(ProjectMembers members) {
    this.members = Objects.requireNonNull(members, "members");
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
    return resolve(
        account,
        home.isGlobal()
            ? InformationContext.Selection.personal()
            : projectSelection(account, home.project()));
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
        && (context.selection().scope() != InformationContext.Scope.PROJECT
            || context.selection().includeShared()))
      throw new CallerFault(
          "Service tokens require a project information scope without includeShared");
    if (context.selection().scope() == InformationContext.Scope.PROJECT
        && !members.mayUse(context.selection().project(), context.account())) {
      throw new CallerFault("this account may not read the selected project's information");
    }
  }

  public void requireWork(InformationContext context) {
    requireSelection(context);
    if (context.selection().scope() == InformationContext.Scope.PROJECT
        && !members.mayWork(context.selection().project(), context.account()))
      throw new CallerFault(
          "This account needs CONTRIBUTOR access to change the selected project's information");
  }

  /** Returns the typed criteria only after rechecking live selection admission. */
  public InformationContext admitted(InformationContext context) {
    requireSelection(context);
    return context;
  }
}
