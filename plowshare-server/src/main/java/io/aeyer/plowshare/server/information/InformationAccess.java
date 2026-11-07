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
    selection = resolvePersonalAlias(account, selection);
    InformationContext context = new InformationContext(account, selection);
    requireSelection(context);
    return boundary == null ? context : context.within(boundary);
  }

  /**
   * Resolves {@code kind:personal} to the project it names, because that is all it is.
   *
   * <p>An account is an identity, not a container. What gives a user account information to hold is
   * that it resolves to a personal space, and a personal space is fundamentally a <b>project</b> —
   * as an Application is. Projects in any form are the resources; accounts are the identities that
   * reach them. So PERSONAL is an alias for {@code {kind:"project", project:"personal:<hex>"}} and
   * is resolved here, once, so that nothing inside the server has to decide what an absent project
   * means.
   *
   * <p>This is deliberately the <b>only</b> place the alias is resolved. Deciding it per consumer
   * is what let the two spellings drift: {@link InformationContext#namespace()} spelled PERSONAL
   * into the {@code account:} family while PROJECT went to {@code project:}, and the stored scope
   * is keyed off the namespace, so one upload's row landed in the account's knowledge scope and the
   * other in its personal project. After this, {@code Scope.PERSONAL} is an input kind on the wire
   * that no longer exists by the time any gate, namespace or home is computed.
   *
   * <p>The service-token refusal is made here rather than left to {@link
   * #requireSelection(InformationContext)}, because that check is expressed against {@code
   * Scope.PERSONAL} and would stop firing the moment the alias resolved. It is kept there too: that
   * method is public and must defend itself.
   *
   * <p>Ownership needs no separate check. The resolved name is this account's own personal project
   * by construction, and a client naming another account's personal project arrives as an ordinary
   * PROJECT selection, where {@code requireSelection} already demands a role that {@code
   * JdbcProjectMembers.role} does not grant on someone else's personal space.
   */
  private InformationContext.Selection resolvePersonalAlias(
      String account, InformationContext.Selection selection) {
    if (selection.scope() != InformationContext.Scope.PERSONAL) return selection;
    if (io.aeyer.plowshare.server.auth.ServiceCredentials.principal(account))
      throw new CallerFault("Service tokens cannot select Personal information");
    return InformationContext.Selection.project(PersonalSpaces.name(account));
  }

  /**
   * The handle-only run resolution, unchanged for every caller including a handle-less one.
   *
   * <p>The null and blank checks are here rather than left to {@link InformationActor#of} on
   * purpose. {@code AskTool}, {@code DocumentTools.Search} and {@code DocumentTools.AgentList} all
   * pass a run's {@code callerHandle} straight through and that handle is nullable (see {@code
   * JobRuntime.ownerOf}, which exists to recover an owner when it is absent). The old body refused
   * such a run with {@link PersonalSpaces}' caller-visible fault, and refused a Global home before
   * ever looking at the handle; {@code InformationActor} rejects a blank acting principal as a
   * programming error, which is right for the record and wrong for this entry point. So this method
   * keeps its own precondition, in its original order, and builds the actor only once the handle is
   * one an actor may hold.
   */
  public InformationContext forRun(String account, io.aeyer.plowshare.protocol.Home home) {
    if (home.isGlobal())
      throw new CallerFault("A retained run needs an explicit Personal or project home");
    if (account == null || account.isBlank()) throw unauthenticated(account);
    return forRun(InformationActor.of(account), home);
  }

  /**
   * {@link PersonalSpaces} owns this refusal's wording, so it is re-raised rather than copied.
   *
   * <p>The old {@code forRun} body reached {@code PersonalSpaces.name(account)} before anything
   * else could reject a missing handle, which is the fault a handle-less run must keep receiving.
   */
  private static CallerFault unauthenticated(String account) {
    PersonalSpaces.name(account);
    throw new IllegalStateException("A missing account must not resolve a personal space name");
  }

  /**
   * The same run resolution, with both of the run's axes named rather than one handle meaning both.
   *
   * <p>For an uncontained actor this <em>is</em> the single-argument body: the acting principal is
   * resolved exactly as {@code account} was and {@link InformationContext#accountedTo} is never
   * called, so the context is byte-identical to the one this method produced before the carrier
   * existed — not merely equivalent. That is the whole of the behaviour change for every caller
   * that exists today.
   *
   * <p><strong>A contained actor takes the branch below instead of this body.</strong> This body
   * cannot resolve differing axes and must not pretend to: it runs {@link
   * PersonalSpaces#resolveAddress} and {@link PersonalSpaces#requireOwn} against the acting
   * principal, and both refuse a contained project name outright, so a contained actor resolving
   * here could only ever be treated as its container — silently collapsing the two axes this
   * carrier exists to keep apart. {@link #containedRun} is where it goes, and it delegates to
   * {@link #contained(String, String, String)}.
   */
  public InformationContext forRun(InformationActor actor, io.aeyer.plowshare.protocol.Home home) {
    Objects.requireNonNull(actor, "actor");
    if (!actor.uncontained()) return containedRun(actor, home);
    String account = actor.acting();
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

  /**
   * The contained run's resolution: the child's principal acting, its container accountable.
   *
   * <p>This is {@link #forRun(InformationActor, io.aeyer.plowshare.protocol.Home)}'s other half and
   * deliberately not a variation on it. The uncontained body resolves a <em>selection</em> from a
   * handle — a personal-space address, an ownership check and a live project role — and every one
   * of those steps is a question about a credentialed account. A contained principal is a bare UUID
   * with no {@code admins}, {@code authority_accounts} or {@code service_tokens} row by design
   * (V218), so each of those steps would refuse it, and widening any of them would amount to
   * issuing the child credential authority. None of them is widened. The selection is instead
   * <em>stated</em>: the child's own contained project, which is the only home a contained run has.
   *
   * <p><strong>How this reaches authority without a credential.</strong> The admission is the
   * run's, not the principal's. {@code retained} is the {@link
   * io.aeyer.plowshare.server.access.KnowledgeReadScope} the run was bound with — in production the
   * run's own {@code OperationAuthority.Admission} home, narrowed across the containment hop before
   * this method is ever called — and {@code requireHome} is what refuses a selection outside it.
   * That is the same ceiling an uncontained run is held to, asked in the same place. What the
   * contained principal supplies is the <em>subject</em> the SQL gates are keyed on (V223's
   * contained arm, V229's read and write arms, V231's reference arm, V232's memory arm), each of
   * which admits that principal to its own scope and to nothing else by walking {@code
   * contained_scope_ancestors} upward from the descendant. So the authority comes from the run and
   * the identity comes from the principal, and neither of them is a credential.
   *
   * <p><strong>No {@link #requireSelection} call, and that is not an omission.</strong> That gate
   * asks {@link ScopedPermissions} for a live project role, and a contained project carries no
   * {@code PROJECT} scope and therefore no role for anybody — not the principal and not its
   * container. Calling it here would refuse every contained run, and relaxing it would hand a role
   * to a principal the design says must hold none. The gates that do decide a contained run's reads
   * and writes are the SQL arms named above, which are asked on every statement. Note that the
   * service methods on this class still call {@code requireSelection} for themselves and therefore
   * still refuse a contained context; what a contained run reaches today is the navigation read
   * path and the catalogue repository, and that boundary is recorded in {@code
   * ContainedInformationReachDatabaseTest}.
   *
   * <p>The project-prefix refusal is {@link #contained(String, String, String)}'s and is reached
   * through it rather than restated, so there is one definition of what a contained home is. The
   * Global refusal is restated here because it must run before the home's project is read at all.
   */
  private InformationContext containedRun(
      InformationActor actor, io.aeyer.plowshare.protocol.Home home) {
    if (home.isGlobal())
      throw new CallerFault("A retained run needs an explicit Personal or project home");
    var retained =
        boundary == null
            ? io.aeyer.plowshare.server.access.KnowledgeReadScope.homeOnly(home)
            : boundary;
    retained.requireHome(home);
    // includeGlobal stays false: contained(...) builds the scope-bound selection and
    // retained.globalRead() is deliberately NOT folded in, because the global arm of every gate
    // requires a credentialed general-read membership this principal does not have.
    return contained(actor.acting(), actor.attribution(), home.project())
        .withView(true)
        .within(retained);
  }

  /** Ordinary discovery includes Global READ for every authenticated principal type. */
  public static InformationContext.Selection projectSelection(String account, String project) {
    return InformationContext.Selection.project(project);
  }

  /**
   * The context a run inside a contained Application would write under, with both axes named once.
   *
   * <p>It is the only place an {@link InformationContext} is built with its two axes differing, and
   * it exists so that no caller assembles the combination by hand: the acting principal is the
   * child's own ephemeral principal, which is what V229's gates admit, and the accounting account
   * is its container's, which is what the provenance columns' foreign keys require.
   *
   * <p><strong>A production path calls this.</strong> {@link #containedRun} delegates here for
   * every run whose own conversation record says it is a contained Application's: {@code
   * JobRuntime} builds {@link InformationActor#contained} when the durable record's origin is
   * {@code Origin.CONTAINED} and its home is a {@code contained:} project, and the information tool
   * and the memory write binding both take that actor. So {@code contained_knowledge_actor} is true
   * for an actor a production path presents, and V229's read baseline, both of V229's write arms,
   * V231's reference arm, V232's memory arms and every contained-actor guard in the <em>four</em>
   * information repositories that carry one are reachable from a running child rather than from a
   * test alone. The tests that also build this context directly are now three — {@code
   * ContainedExecutionWriteDatabaseTest}, {@code ContainedActorWriteReachDatabaseTest} and {@code
   * ContainedIntakeAnnouncementDatabaseTest} — and {@code ContainedInformationReachDatabaseTest}
   * reaches it through {@link #forRun(InformationActor, io.aeyer.plowshare.protocol.Home)} as a run
   * does.
   *
   * <p><strong>What a running child does and does not reach.</strong> The gates above are SQL arms
   * and they admit the child to its own scope. The service methods on this class — {@link
   * #requireSelection}, {@link #requireWork}, {@link #requireAudience} — ask {@link
   * ScopedPermissions} for a live project role, which a contained project grants nobody, so every
   * {@code InformationCatalogue} operation that calls one of them still refuses a contained
   * context. {@code SET_AUDIENCE} is refused by exactly that absence and must stay refused. The
   * same is true one layer above {@code MemoryStore}, where {@code BoundMemoryQueries.requireScope}
   * and {@code BoundMemoryWrites} ask the same question. Whether those layers should admit a
   * contained principal is an open authority decision, not an oversight; the characterization is
   * recorded in {@code ContainedInformationReachDatabaseTest}. <strong>Do not read this javadoc as
   * a claim that a child drives the whole information tool.</strong>
   *
   * <p><strong>{@code includeGlobal} is false, deliberately.</strong> {@link
   * InformationContext.Selection#project} sets it true, which every ordinary caller wants and which
   * V229 correctly leaves closed to a contained actor — the global arm of the destination gate
   * requires a credentialed general-read membership a contained principal does not have. A child's
   * write is scope-bound or it is nothing, so this builds the scope-bound selection rather than
   * letting the ordinary helper's default refuse it at the gate.
   *
   * @param principal the contained principal, acting
   * @param container the container's account, accountable
   * @param project the child's own contained project
   */
  public static InformationContext contained(String principal, String container, String project) {
    if (!io.aeyer.plowshare.protocol.ContainedProjects.name(project))
      throw new CallerFault("A contained write names the child's own contained project");
    return new InformationContext(
            principal,
            new InformationContext.Selection(InformationContext.Scope.PROJECT, project, false))
        .accountedTo(container);
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
