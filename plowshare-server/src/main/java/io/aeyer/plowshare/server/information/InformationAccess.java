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
  private final io.aeyer.plowshare.server.access.KnowledgeReadScope boundary;
  private final AccountKnowledgePermissions accounts;
  private final io.aeyer.plowshare.server.requests.RequestProjects projects;

  public InformationAccess(ScopedPermissions permissions) {
    this(permissions, io.aeyer.plowshare.server.requests.RequestProjects.EXPLICIT_ONLY);
  }

  public InformationAccess(
      ScopedPermissions permissions, io.aeyer.plowshare.server.requests.RequestProjects projects) {
    this(permissions, projects, AccountKnowledgePermissions.DENY);
  }

  public InformationAccess(
      ScopedPermissions permissions,
      io.aeyer.plowshare.server.requests.RequestProjects projects,
      AccountKnowledgePermissions accounts) {
    this(permissions, projects, accounts, null);
  }

  private InformationAccess(
      ScopedPermissions permissions,
      io.aeyer.plowshare.server.requests.RequestProjects projects,
      AccountKnowledgePermissions accounts,
      io.aeyer.plowshare.server.access.KnowledgeReadScope boundary) {
    this.boundary = boundary;
    this.accounts = Objects.requireNonNull(accounts);
    this.permissions = Objects.requireNonNull(permissions, "permissions");
    this.projects = Objects.requireNonNull(projects, "projects");
  }

  /**
   * Bind the existing information service to one retained read ceiling, never fresh ambient grants.
   */
  public InformationAccess within(io.aeyer.plowshare.server.access.KnowledgeReadScope scope) {
    return new InformationAccess(
        permissions,
        projects,
        accounts,
        boundary == null ? Objects.requireNonNull(scope) : boundary.intersect(scope));
  }

  public InformationContext resolve(String account, InformationContext.Selection selection) {
    if (account == null || account.isBlank()) {
      throw new CallerFault("information needs a socket or run authenticated as an account");
    }
    if (selection == null)
      selection =
          projects
              .implicit(account)
              .map(project -> projectSelection(account, project))
              .orElseGet(InformationContext.Selection::personal);
    InformationContext context = new InformationContext(account, selection);
    requireSelection(context);
    return boundary == null ? context : context.within(boundary);
  }

  public InformationContext forRun(String account, io.aeyer.plowshare.protocol.Home home) {
    if (home.isGlobal())
      throw new CallerFault("A retained run needs an explicit Personal or project home");
    // A run's Personal home is a server-owned workspace project, while its retained information
    // belongs to the account namespace. Require the exact owner before mapping either address.
    String project = PersonalSpaces.resolveAddress(home.project(), account);
    PersonalSpaces.requireOwn(project, account);
    boolean personal = PersonalSpaces.name(account).equals(project);
    var retained =
        boundary == null
            ? io.aeyer.plowshare.server.access.KnowledgeReadScope.homeOnly(home)
            : boundary;
    retained.requireHome(home);
    return resolve(
            account,
            new InformationContext.Selection(
                personal ? InformationContext.Scope.PERSONAL : InformationContext.Scope.PROJECT,
                personal ? null : project,
                retained.globalRead()))
        .withView(true)
        .within(retained);
  }

  /** Ordinary discovery includes Global READ for every authenticated principal type. */
  public static InformationContext.Selection projectSelection(String account, String project) {
    return InformationContext.Selection.project(project);
  }

  /**
   * The execution home a selection's own pipeline work is admitted against.
   *
   * <p>This is the exact inverse of {@link #forRun}, which reads a PERSONAL selection back out of a
   * home by comparing its project against {@code PersonalSpaces.name(account)}. A {@link
   * InformationContext.Selection} cannot carry the answer by itself: its constructor forbids every
   * non-PROJECT scope from naming a project, so {@code selection.project()} is null for a PERSONAL
   * selection and reading it alone cannot tell "this account's own space" apart from "no project at
   * all". Resolving the personal space's own project name here is what keeps a personal write
   * inside an execution scope that {@code OperationAuthority.scope(Home)} can resolve and {@code
   * admit} can own-check; mapping it to {@code Home.global()} instead is what refused every
   * personal document upload with "Operation is outside its retained scoped authority".
   *
   * <p>This resolves a destination; it grants nothing. The caller's own authority over that home is
   * still established by admission — {@code admit} refuses a {@code Personal} scope whose account
   * is not the executor's, and still requires a live supporting grant.
   *
   * <p>ACCOUNT and GLOBAL_READ selections keep answering {@link
   * io.aeyer.plowshare.protocol.Home#global()}: the Global knowledge tier genuinely has no project,
   * it is not an execution scope, and admission for it stays closed exactly as it is today. Those
   * two scopes have their own gates ({@link AccountKnowledgePermissions}, and {@link
   * #requireWork}'s refusal of a Global READ destination) and are not widened here.
   */
  public static io.aeyer.plowshare.protocol.Home executionHome(InformationContext context) {
    Objects.requireNonNull(context, "context");
    return switch (context.selection().scope()) {
      case PROJECT -> io.aeyer.plowshare.protocol.Home.of(context.selection().project());
      case PERSONAL -> io.aeyer.plowshare.protocol.Home.of(PersonalSpaces.name(context.account()));
      case ACCOUNT, GLOBAL_READ -> io.aeyer.plowshare.protocol.Home.global();
    };
  }

  /** Check every operation again; an earlier context is not a membership lease. */
  public void requireSelection(InformationContext context) {
    Objects.requireNonNull(context, "context");
    if (context.selection().scope() == InformationContext.Scope.ACCOUNT
        && context.corpus() != InformationContext.Corpus.DOCUMENTS)
      throw new CallerFault("Account destinations currently support the documents corpus only");
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
    requireAccountAction(context, io.aeyer.plowshare.server.access.AuthorityAction.WRITE);
    if (context.selection().scope() == InformationContext.Scope.GLOBAL_READ)
      throw new CallerFault("Global READ is a read selection; select a writable home");
    if (context.selection().scope() == InformationContext.Scope.PROJECT
        && !permissions.allows(
            new Principal(context.account()),
            new ProjectTarget(context.selection().project()),
            Action.WRITE))
      throw new CallerFault(
          "This account needs CONTRIBUTOR access to change the selected project's information");
  }

  /** Content WRITE does not grant permission to change an existing resource's audience. */
  private void requireAccountAction(
      InformationContext context, io.aeyer.plowshare.server.access.AuthorityAction action) {
    if (context.selection().scope() == InformationContext.Scope.ACCOUNT
        && !accounts.allows(
            context.account(),
            new io.aeyer.plowshare.server.access.AuthorityIds.AccountId(
                context.selection().account()),
            action))
      throw new CallerFault(
          "This account needs scoped human ADMIN authority for this account home");
  }

  public void requireAudience(InformationContext context) {
    requireSelection(context);
    requireAccountAction(context, io.aeyer.plowshare.server.access.AuthorityAction.SET_AUDIENCE);
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
