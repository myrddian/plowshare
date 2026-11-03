package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.protocol.ApplicationDeployment.File;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.FilesystemDefinitions;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.allowances.AllowanceSnapshot;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * ADR 0005's deployment machinery with its admission rule and its destination replaced.
 *
 * <p><strong>What is reused.</strong> The package boundary ({@code validateFiles}: at most 128
 * bounded regular UTF-8 text files, 64 KiB each, 128 KiB and 16 directory levels in total, portable
 * paths, no links, no hidden {@code .plowshare/} state), the runtime's own resource parsers through
 * {@link ApplicationPackageValidator} and {@link WorkspaceApplicationPolicy}, the
 * stage-before-commit order, and the request-UUID receipt whose identity includes the exact
 * submitted content. Those limits apply <em>per child</em>; the live aggregate across a container's
 * descendants is bounded separately, because depth and breadth each multiply the budget.
 *
 * <p><strong>What is replaced.</strong> There is no server-administrator check and no destination
 * FileStore {@code MANAGER} check, and there is no collaborator here through which either could be
 * made: this class holds neither {@code ProjectMembers} nor {@code FileStores}. Nothing on the
 * creating path resolves a scope through the general scope read either, so nothing on it can be
 * defeated by that read's refusal of a {@code CONTAINED} row — and since {@link #run} arrived that
 * is a statement about behaviour rather than about the constructor, because an {@code
 * IdentityRepository} <em>is</em> held now, for {@code containedHome} and for nothing else. {@code
 * containedHome} is not the general read: it resolves the stored containment row with the scope,
 * the kind, the container and the project all bound in one predicate, so it can only ever answer a
 * container's own child's project, whereas {@code home(AuthorityScope)} refuses a contained scope
 * outright. {@code ContainedApplicationCreationTest} and {@code ContainedVisibilityTest} assert
 * that creation and both reads touch it not at all. The authority is the creating Application's own
 * retained admission, narrowed across one containment hop by {@link OperationAuthority#contain},
 * and the destination is allocated by the server under {@link ContainmentConfig#root()}. A parent
 * typically holds no FileStore {@code MANAGER} over its own retained source, so proxying a
 * destination could not have produced a legal one. The administrator-gated top-level path keeps
 * both of its checks untouched.
 *
 * <p><strong>Where the authority comes from, and why a conversation names it.</strong> {@code
 * create} is asked from inside the creating Application's own live conversation, and that
 * conversation's retained record carries two things this path needs and must not take from the
 * caller: the parent's admission, and the allowance the parent is spending. Both are server-side
 * evidence. A request that supplied its own admission would be asking the caller to authorize
 * itself, and one that supplied its own allowance identifier could name a richer pool to raise the
 * ceiling it is about to be bounded by.
 *
 * <p><strong>Refusal totality.</strong> Everything that can refuse — the configuration, the
 * conversation, the parent's live authority, the receipt, the package, the writable areas, the
 * nesting declaration, the breadth bound, the ceiling, and every declared surface compared against
 * the container's hold — completes before the durable unit of work opens. The only writes that
 * precede it are the staged files, and those are collected on the way out of any failure, which
 * inverts ADR 0005's explicit non-collection for the case where destruction is the normal path. So
 * a refused manifest leaves no project row, no contained scope, no principal, no allowance, no
 * containment record and no staged source.
 */
public final class SourceContainedApplications implements ContainedApplications {
  /**
   * The actions a hop may carry. Deliberately the same four {@code PinnedOperationAuthority} admits
   * for a narrowing, restated rather than exported: a narrowed authority runs work, it does not
   * re-grant it, and {@code contain} refuses anything else regardless of what is asked here.
   */
  private static final Set<AuthorityAction> DERIVABLE =
      Set.of(
          AuthorityAction.READ,
          AuthorityAction.WRITE,
          AuthorityAction.CONVERSATION_CREATE,
          AuthorityAction.RUN_MODEL);

  private static final String PREFIX = io.aeyer.plowshare.protocol.ContainedProjects.PREFIX;

  /** The package's file limit, referenced rather than restated so the two cannot drift. */
  private static final int MAX_RESOURCES = SourceApplicationDeployments.MAX_FILES;

  /** The package's 16 directory levels under the staged revision, plus the files themselves. */
  private static final int MAX_RESOURCE_DEPTH = 17;

  /**
   * Directory entries one listing may visit: the package's files and directories with generous
   * headroom, so a hand-planted tree cannot make a listing walk without end.
   */
  private static final long MAX_RESOURCE_ENTRIES = MAX_RESOURCES * 32L;

  /**
   * A contained run is shown no pictures, structurally rather than by convention.
   *
   * <p>{@link Runs.Shown} is how a surface turns image ids into bytes, and the ids would have to
   * come from a caller. A container does not supply any — {@link ContainedApplications.Run} has no
   * such field — so this resolver answers the empty list for the empty request and refuses anything
   * else, which keeps a later widening of the request shape from silently acquiring a route into
   * the surface's own image tier.
   */
  private static final Runs.Shown NO_PICTURES =
      (definition, home, uids) -> {
        if (uids != null && !uids.isEmpty())
          throw new CallerFault("A contained run is shown no pictures");
        return List.of();
      };

  private final ContainedApplicationRepository records;
  private final ProjectWorkspaces projects;
  private final ApplicationPackageValidator validator;
  private final OperationAuthority authority;
  private final IdentityRepository identities;
  private final ContainedPrincipals principals;
  private final AllowanceRepository allowances;
  private final UnitOfWork work;
  private final ContainmentConfig config;
  private final ContainedStaging staging;
  private final Runs runs;
  private final io.aeyer.plowshare.server.agents.CancellableRuns jobs;

  /**
   * @param identities held for {@link IdentityRepository#containedHome} alone, and that is not the
   *     general scope read this class's javadoc says nothing here depends on. {@code
   *     home(AuthorityScope)} <em>refuses</em> a contained scope by design; {@code containedHome}
   *     resolves the stored containment row with all four of its facts bound in the predicate, so
   *     it can only ever answer this container's own child's project. It is used by {@link #run}
   *     and by nothing else.
   * @param runs the run service, which is the owning API for starting one agent on one task. Reused
   *     rather than re-implemented: a second submission path would decide the order of its own
   *     refusals differently from the one every other surface gets.
   * @param jobs how a run already going is asked to stop, and the narrowest contract that can say
   *     so: it holds one method keyed on a conversation. Deliberately not {@code JobStore} itself —
   *     this class must not acquire the ability to submit, poll or list runs in order to stop one —
   *     and deliberately not {@code Runs}, whose subject is starting work. See {@link #cancel}.
   */
  public SourceContainedApplications(
      ContainedApplicationRepository records,
      ProjectWorkspaces projects,
      ApplicationPackageValidator validator,
      OperationAuthority authority,
      IdentityRepository identities,
      ContainedPrincipals principals,
      AllowanceRepository allowances,
      UnitOfWork work,
      ContainmentConfig config,
      ContainedStaging staging,
      Runs runs,
      io.aeyer.plowshare.server.agents.CancellableRuns jobs) {
    this.records = Objects.requireNonNull(records);
    this.projects = Objects.requireNonNull(projects);
    this.validator = Objects.requireNonNull(validator);
    this.authority = Objects.requireNonNull(authority);
    this.identities = Objects.requireNonNull(identities);
    this.principals = Objects.requireNonNull(principals);
    this.allowances = Objects.requireNonNull(allowances);
    this.work = Objects.requireNonNull(work);
    this.config = Objects.requireNonNull(config);
    this.staging = Objects.requireNonNull(staging);
    this.runs = Objects.requireNonNull(runs);
    this.jobs = Objects.requireNonNull(jobs);
  }

  @Override
  public Child create(String account, Create request) {
    if (!config.configured()) throw new CallerFault("This server does not configure nesting");
    var parent = parent(account, request.conversation());

    // The receipt is read before any filesystem access, so an identical retry answers from its
    // record rather than restaging source that may since have been altered or collected. A changed
    // submission under the same UUID is refused rather than treated as a new revision.
    String fingerprint = fingerprint(request);
    var prior = records.receipt(parent.admission().scope(), request.requestId());
    if (prior.isPresent()) {
      if (!prior.get().fingerprint().equals(fingerprint))
        throw new CallerFault(
            "This request UUID already created a different contained Application; absence of a"
                + " receipt is what authorizes a new one");
      return child(prior.get());
    }

    SourceApplicationDeployments.validateFiles(request.files());
    if (!request.writableAreas().isEmpty())
      throw new CallerFault(
          "A contained Application's writable area is allocated inside its own containment; a"
              + " requested FileStore area is outside its container's fence");
    // The same rule for the same reason, and refused here rather than ignored: this path allocates
    // the destination itself under ContainmentConfig.root(), so a request that names one is asking
    // for something no caller can be given. A field accepted and discarded reads as supported.
    if (request.requestedDestination() != null)
      throw new CallerFault(
          "A contained Application's source area is allocated by the server inside its"
              + " containment; a destination cannot be named");
    Path containerRoot = containerRoot(parent.admission().home().project());
    if (!ContainedDeclarations.nesting(containerRoot))
      throw new CallerFault("This Application does not declare nesting");
    if (records.liveDescendants(parent.admission().scope()) >= config.bounds().maxLiveDescendants())
      throw new CallerFault(
          "This Application already holds its permitted live contained Applications");
    requireCeiling(parent.budget(), request.ceiling());

    String project = PREFIX + UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    Path workspace = config.root().resolve(project.substring(PREFIX.length()));
    Path staged = workspace.resolve("revisions").resolve(revision.toString());
    // Claimed before the first byte and released in the finally below. A staged area has no
    // durable row until this unit of work commits -- that ordering is refusal totality -- so
    // without the claim a concurrent sweep would see an unaccounted directory and collect the
    // source of a creation that is about to succeed.
    staging.claim(project);
    try {
      var allowance = new AllowanceId(UUID.randomUUID());
      try {
        stage(staged, request.files(), project);
        // Both sides of the comparison are read through the same reader, from the container's own
        // retained source and from the child's staged source, so a declaration cannot be honoured
        // because the two were parsed differently.
        ContainedDeclarations.requireWithin(
            ContainedDeclarations.read(project, staged),
            ContainedDeclarations.read(parent.admission().home().project(), containerRoot));
        manifest(staged, project);
        validator.validate(project, staged);
      } catch (RuntimeException | IOException before) {
        // Everything above runs before the unit of work opens, so no durable row can exist and
        // the staged bytes are certainly garbage.
        collect(workspace);
        throw before instanceof RuntimeException refused
            ? refused
            : new CallerFault("Contained Application source could not be staged");
      }
      // From here the staged source is collected only by a failure that is DEFINITELY a rollback,
      // which means a failure thrown by the body below -- never one thrown by the commit.
      //
      // A commit that reports failure may still have committed, and collecting on that would
      // produce the one orphan this capability calls unrecoverable: a live child row whose source
      // is gone. committed(project) answers true for it, so the sweep neither collects it nor
      // repairs it, and no record says anything is wrong. Leaving the bytes is safe in both
      // branches and requires distinguishing nothing -- if it committed they are the child's
      // correct source, and if it did not there is no row, so the sweep collects them. So nothing
      // here catches what escapes inTransaction itself.
      return work.inTransaction(
          () -> {
            try {
              // The container's own project row lock, which is the row a parent revision change
              // locks. Taken before the insert so that creation and activation contend on one lock:
              // without it, activation could count zero live children a moment before this row
              // lands and both would commit, leaving the orphan the fence exists to prevent.
              records.lockContainer(parent.admission().scope());
              var retained =
                  records.create(
                      new ContainedApplicationRepository.Creation(
                          parent.admission().scope(),
                          request.requestId(),
                          fingerprint,
                          project,
                          workspace,
                          revision,
                          allowance,
                          request.ceiling()));
              var scope =
                  new AuthorityScope.Contained(
                      retained.scope(), retained.projectId(), retained.container());
              // The child allocates its OWN allowance, as nested starts already do, under the
              // ceiling its container set. There is no shared counter: child spend is attributed to
              // the child, which leaves ADR 0025's immutable attribution and the rule that
              // delegation cannot change the accounting account exactly as they are.
              allowances.retain(new AllowanceSnapshot(allowance, retained.ceiling(), 0));
              principals.issue(scope);
              // Last, because it is the one step that can still refuse on authority rather than on
              // data: the hop proves the stored containment row, the depth bound and the parent's
              // live grants. Its refusal rolls the rest of this unit back.
              authority.contain(parent.admission(), scope, narrowed(parent.admission()));
              return child(retained);
            } catch (RuntimeException rolledBack) {
              // Thrown by the body, so this unit of work will roll back: the project row, the
              // scope, the principal, the allowance and the containment record all go, and the
              // staged bytes are garbage with certainty.
              collect(workspace);
              throw rolledBack;
            }
          });
    } finally {
      staging.release(project);
    }
  }

  @Override
  public void ceiling(String account, String conversation, UUID child, Integer calls) {
    if (calls == null || calls < 0)
      throw new CallerFault(
          "A contained ceiling is explicit; an absent ceiling would mean unlimited, and zero is"
              + " how a container disables its child's calls");
    var retained =
        records.find(child).orElseThrow(() -> new CallerFault("No such contained Application"));
    // The container's own authority, proved exactly as creation proves it and with no general
    // scope read in sight. Resolving the container through IdentityRepository.scope was the whole
    // defect: that read refuses a CONTAINED row, so this operation -- the reduce-to-zero this
    // slice requires included -- failed closed for every child whose container is itself
    // contained. The conversation's retained admission already answers both questions, and the
    // two-gate change made it answer them at depth: who is asking, and whether that caller is
    // acting as the scope this child is recorded under.
    var parent = parent(account, conversation);
    if (!parent.admission().scope().equals(retained.container()))
      throw new CallerFault("This Application does not contain that Application");
    // The identical bound creation applies, on the identical pool. Two call sites, one rule: a
    // raise beyond the container's own remaining allowance is refused here exactly as it is at
    // creation, so creating low and raising high is not a route around it. A raise within that
    // remaining allowance is admitted, which is less restrictive than refusing every increase and
    // is what the container could have asked for at creation anyway.
    requireCeiling(parent.budget(), calls);
    work.inTransaction(
        () -> {
          allowances.ceiling(retained.allowance(), calls);
          records.ceiling(child, calls);
          return null;
        });
  }

  /**
   * Stops the work inside one child and its descendants, under its container's authority.
   *
   * <p>The order is {@link #run}'s: the caller's live authority is proved <em>before</em> any
   * lookup, so a refusal discloses nothing about whether the named UUID is a contained Application
   * on this installation, and then the child's row must name the caller's own scope as its
   * container.
   *
   * <p><strong>Two things this deliberately does not do, each of which {@code run} does.</strong>
   * It does not require containment to be configured: the root is where a child's <em>source</em>
   * lives, nothing here reads it, and a server reconfigured while work is in flight must not
   * thereby lose the ability to stop that work. And it does not refuse a zero ceiling: a child
   * whose calls have been disabled may still hold a run that has not yet reached its next boundary,
   * which is precisely the run a container wants stopped.
   *
   * <p><strong>What "idempotent" does and does not cover, said exactly.</strong> Repeating this
   * call while the child exists is the same answer every time — a stop is a request and changes no
   * state here. It is <em>not</em> idempotent across a destruction: {@link #destroy} deletes the
   * child's project row, {@code contained_applications.project_id} is {@code ON DELETE CASCADE}
   * (V219), and {@code records.find} carries no liveness predicate, so afterwards there is no row
   * and this call refuses with "No such contained Application". That is the honest shape and there
   * is nothing to fix: a destruction has already stopped the subtree's work before collecting any
   * source, so a cancellation arriving after it has nothing left to ask.
   *
   * <p>There is deliberately no destroyed-child branch here, and the reason is not symmetry with
   * {@code run} — it is that such a branch would be <strong>unreachable</strong>. {@code
   * contained_applications.live} is vestigial: V219 declares it {@code NOT NULL DEFAULT TRUE} and
   * nothing in this server ever writes {@code false} to it, so it appears only in read predicates.
   * A record in the {@code live = false} state is a state production does not produce, and {@code
   * run}'s own "has been destroyed" refusal is reachable only from a fixture that builds one.
   *
   * <p>No durable write of any kind happens here, which is why there is no unit of work: the stop
   * is a flag inside the run's own process. The subtree is therefore read without {@code
   * lockSubtree} — a creation committing alongside this call adds a record whose run cannot have
   * started yet, and a caller who wants it stopped asks again, which costs nothing because asking
   * twice is the same answer.
   */
  @Override
  public void cancel(String account, String conversation, UUID child) {
    // First, and before any lookup, exactly as run does.
    var parent = parent(account, conversation);
    var retained =
        records.find(child).orElseThrow(() -> new CallerFault("No such contained Application"));
    if (!parent.admission().scope().equals(retained.container()))
      throw new CallerFault("This Application does not contain that Application");
    stop(
        records.subtree(retained.scope()).stream()
            .map(ContainedApplicationRepository.Retained::scope)
            .toList());
  }

  /**
   * Asks every run inside these scopes to stop, in the order they are given.
   *
   * <p>Shared by {@link #cancel} and by both of {@link #destroy}'s halves on purpose, so there is
   * one notion of "the work inside a subtree" rather than three that could drift. It takes scopes
   * rather than records because the two callers hold different row types for the same subtree — a
   * live {@code Retained} and a {@code Collection} written by a destruction — and the scope is the
   * only thing either of them needs to contribute.
   *
   * <p><strong>The caller supplies the order and this method neither sorts nor reverses
   * it.</strong> Deepest first is the destruction's contract and belongs where that contract is
   * stated; a sort here would make the ordering two claims in two places.
   *
   * <p>A run is reached through the child's own retained conversation envelopes, which is the only
   * durable key a container holds on work it did not submit. {@code conversationsIn} is immutable
   * evidence and grants nothing — the caller proved its authority above — and it is not transitive,
   * which is why this iterates the scopes rather than asking once about the root.
   */
  private void stop(List<AuthorityIds.ScopeId> ordered) {
    for (var scope : ordered)
      for (var envelope : authority.conversationsIn(scope)) jobs.cancelIn(envelope.id());
  }

  /**
   * Starts work inside one child, as that child.
   *
   * <p>The order is the contract and it is {@link #ceiling}'s, not {@link #trajectory}'s. The
   * configuration is checked, the caller's live authority is proved, the child's row is resolved,
   * the caller's scope must <em>be</em> that row's recorded container, a destroyed child and a zero
   * ceiling are refused, the home is resolved through the stored containment row, the agent is
   * resolved against the child's own staged package, and only then is anything started. Nothing
   * before the authority proof reads the child's package or touches its source area; {@code
   * trajectory} and {@code resources} look the child up first and ADR 0042 records that existence
   * oracle as a known limitation, which this operation deliberately does not copy.
   *
   * <p>Why the direct container and not the ancestor walk is argued on {@link
   * ContainedApplications#run}: the spend and the breadth accounting belong to the container that
   * lent them, so a grandparent cannot raise work under a ceiling the intermediate container set.
   * Do not harmonise this with {@code trajectory}.
   *
   * <p>No durable write of this class's own happens here. The child's principal is issued
   * idempotently — it already exists for any child created by this service, and issuing is how its
   * identifier is read back rather than a second source of it — and everything the run itself
   * persists is {@link Runs}' own.
   *
   * <p><strong>The account is the container's and the author is the child's.</strong> A hop keeps
   * the parent's executor and account unchanged, so the run's caller identity, its work authority
   * and its accounting account are all the container's; the contained principal is never made an
   * {@code authority_accounts} subject and is recorded as the <em>author</em> instead, through
   * {@link Speaker#contained}. A container must be able to tell its child's writing from its own,
   * because child output is untrusted input to it, and with the account shared the author is what
   * carries that.
   *
   * <p><strong>What stands below this method, now that it works.</strong> {@link Runs} reaches
   * {@code ConversationStore}, whose ordinary doors resolve a run's home to a scope through {@code
   * OperationAuthority.scope(Home)} — a resolution that refuses a contained project by design, and
   * must keep refusing it. A contained run therefore goes through {@code
   * ConversationStore.openContained} instead, which takes the admission this method's hop produced
   * and binds it rather than admitting a scope, on an {@code Origin.CONTAINED} row carrying the
   * child's own allowance. V225 opened the two shipped constraints that stood in front of that
   * door. {@code ContainedConversationDoorDatabaseTest} asserts the envelope a started child gets:
   * its budget is the child's own allowance and its admission account is its container's.
   *
   * <p>One thing a reader must not do here: a confined Application's documents are not processed by
   * the information pipeline, and that is a recorded decision rather than a gap to close in passing
   * — ADR 0042 names the three-way ownership question it defers.
   */
  @Override
  public Running run(String account, Run request) {
    if (!config.configured()) throw new CallerFault("This server does not configure nesting");
    // First, and before any lookup: a refusal below this line discloses nothing about whether the
    // named UUID is a contained Application on this installation.
    var parent = parent(account, request.conversation());
    var retained =
        records
            .find(request.child())
            .orElseThrow(() -> new CallerFault("No such contained Application"));
    if (!parent.admission().scope().equals(retained.container()))
      throw new CallerFault("This Application does not contain that Application");
    // Unreachable in production, and kept as a guard rather than as behaviour: a destroyed child's
    // record is cascade-deleted with its project row (V219), so find() above already refused it,
    // and contained_applications.live is NOT NULL DEFAULT TRUE with nothing in this server ever
    // writing it false. See the same note on resources below and ADR 0042's record of it.
    if (!retained.live()) throw new CallerFault("That contained Application has been destroyed");
    // A child that cannot make a call is not started. Zero is a deliberate and useful state --
    // this is how a container disables its child -- so a run under it is refused rather than
    // started and starved, which would spend a turn to discover the same thing.
    if (retained.ceiling() != null && retained.ceiling() == 0)
      throw new CallerFault(
          "That contained Application's ceiling is zero, so it can make no call; raise the"
              + " ceiling before starting work inside it");
    var scope =
        new AuthorityScope.Contained(retained.scope(), retained.projectId(), retained.container());
    // Resolved from the stored containment row and never from a name in the request -- there is no
    // such field. The general scope read refuses a CONTAINED row by design, so this is the one read
    // that can answer, and it binds the scope, the kind, the container and the project together.
    Home home = identities.containedHome(scope, retained.container());
    // The work question, asked where a child's work authority actually lives. A contained project
    // carries no PROJECT scope and so no role for anybody, so asking about the child's own project
    // name answers "no role" whoever asks; the deciding question is the container's account against
    // the nearest enclosing uncontained scope, which is the same question the hop answered.
    Home mediating = authority.mediatingHome(scope);
    String agent = declared(retained, request.agent());
    // The hop, run again for this operation: contain is the only way a contained admission comes
    // into being, and each started run is its own operation with its own admission -- the same
    // shape JobRuntime uses for a delegated child, where derive stands where contain stands here.
    // It proves the stored containment row, the depth bound and the container's live grants, so a
    // revoked container starts nothing.
    var admission = authority.contain(parent.admission(), scope, narrowed(parent.admission()));
    // The child's own allowance, restored rather than minted: the conversation points at the pool
    // its container already set a ceiling on, so the run spends the child's allowance while the
    // admission above keeps the container's account. No counter is shared.
    var pool =
        allowances
            .find(retained.allowance())
            .map(found -> allowances.restore(found.id()))
            .orElseThrow(
                () -> new CallerFault("That contained Application's allowance is unavailable"));
    // Two axes, and they are deliberately different identities.
    //
    // ACCOUNT: the container's. A hop keeps the parent's executor and account unchanged, and a
    // child's authority is a projection of its container's, so everything that needs an account --
    // the work check above, the run's caller identity, and the accounting account -- takes the
    // container's. That is also what keeps ADR 0025's immutable attribution and its rule that
    // delegation cannot change the accounting account intact: nothing here invents a second
    // accounting subject.
    //
    // AUTHOR: the contained principal's. It is not an authority_accounts subject and never becomes
    // one -- V218 is unchanged and nothing here creates a row for it -- so what it is recorded as
    // is the provenance of the work: Speaker.contained(child) travels into the log as the
    // utterance's speaker and survives the write. That distinction is load-bearing rather than
    // cosmetic: child output is untrusted input to its container, so a container reading work back
    // must be able to tell its child's writing from its own, and with the account now shared the
    // author field is the only thing that says so.
    var principal = principals.issue(scope);
    var started =
        runs.start(
            new Runs.Ask(
                agent,
                request.task(),
                home.project(),
                null,
                null,
                request.maxTurns(),
                null,
                List.of(),
                Speaker.contained(retained.id()),
                parent.admission().executor(),
                true,
                new Runs.Ask.Contained(admission, pool, mediating.project())),
            NO_PICTURES);
    // Issued, and deliberately not passed as the caller: see the two axes above. Reading it back
    // here is what proves the principal exists for a child this operation is about to start, which
    // Tasks 4 and 5 key cancellation and revocation off.
    Objects.requireNonNull(principal.id(), "a started child has its own principal");
    return new Running(retained.id(), started.id(), started.conversation(), started.agent());
  }

  /**
   * The agent name, proved against the child's <em>own</em> staged package and nothing else.
   *
   * <p>This is a containment check and not the registry resolution: {@link Runs#start} resolves the
   * name again against the child's project, which is where availability, exposure and the client's
   * own definitions are decided. What this adds is the one thing that resolution cannot say on its
   * own — that the name came from the child's package rather than from the container's, which is
   * what keeps a container from naming an agent of its own and having it run inside the child.
   *
   * <p>Read from the child's single staged revision, derived from its opaque project name under the
   * configured root exactly as {@link #resources} derives it, never from a stored path.
   */
  private String declared(ContainedApplicationRepository.Retained retained, String agent) {
    Path staged =
        workspace(retained.project()).resolve("revisions").resolve(retained.revision().toString());
    var names =
        new FilesystemDefinitions(staged.resolve("agents"))
            .list().stream()
                .map(io.aeyer.plowshare.server.agents.DefinitionSource.Definition::name)
                .toList();
    if (!names.contains(agent))
      throw new CallerFault(
          "That contained Application declares no agent named '"
              + agent
              + "'; an agent of the container is not nameable inside it");
    return agent;
  }

  /**
   * Destroys a child and its descendants, deepest first, then collects their source areas.
   *
   * <p><strong>The order is the contract.</strong> Authority first, and proved exactly as {@link
   * #ceiling} proves it. Then one durable unit that, per record from the deepest outwards, zeroes
   * the allowance, revokes the principal, writes the collection record and deletes the rows. Then,
   * and only then, the bytes.
   *
   * <p>Why the bytes come last: the filesystem cannot join the transaction, so one of the two
   * orders must be chosen and they fail differently. Removing first and rolling back leaves a live
   * record pointing at source that no longer exists — an Application that cannot run and that no
   * sweep can repair, because its record says it is live. Committing first and being interrupted
   * leaves a directory with an unmarked collection record, which {@link ContainedSourceCollector}
   * finishes on the next sweep. Only one of those is recoverable.
   *
   * <p>Deepest first is not a preference either. {@code authority_scopes.container} is {@code ON
   * DELETE RESTRICT}, so a container's rows cannot go while a descendant references them: a
   * parent-first delete is refused by the schema rather than silently orphaning anything.
   */
  @Override
  public Destroyed destroy(String account, String conversation, UUID child) {
    var live = records.find(child);
    var parent = parent(account, conversation);
    if (live.isEmpty()) return replay(parent, child);
    var retained = live.get();
    if (!parent.admission().scope().equals(retained.container()))
      throw new CallerFault("This Application does not contain that Application");
    // Locked and read inside the durable unit, as creation locks before it inserts. Read outside
    // it, a nested create committing between the read and the deletes left the cascade to meet the
    // RESTRICT on authority_scopes.container, and that arrived as a DataIntegrityViolationException
    // -- an INTERNAL_ERROR for a conflict creation answers with a plain refusal. The lock is the
    // whole subtree and not the one container because the creations that can add a row inside this
    // cascade are made by descendants, each of which locks its OWN container's project row.
    var subtree =
        work.inTransaction(
            () -> {
              records.lockSubtree(retained.scope());
              // Never empty: find() just resolved this child, and the subtree read selects it at
              // depth 0. Re-read under the lock, so a creation that committed while this request
              // was proving authority is destroyed with the rest rather than left behind it.
              var held = records.subtree(retained.scope());
              destroy(held, child);
              return held;
            });
    // BEFORE THE FIRST REMOVAL, over the same deepest-first set the cascade just walked. A run
    // still reading its source area while that area is collected is the one failure the ordering
    // below is otherwise careful to avoid, and the allowance zeroed inside the unit does not close
    // it: a run mid-turn can make no further call, but it can still read files. Stopping after the
    // commit rather than inside it is deliberate twice over -- a cancellation is a process-local
    // flag that no rollback could take back, and the ordered set is only known under the lock.
    stop(subtree.stream().map(ContainedApplicationRepository.Retained::scope).toList());
    int sources = 0;
    for (var held : subtree) {
      Path workspace = workspace(held.project());
      collect(workspace);
      // Marked only once the bytes are actually gone. An unmarked row is the sweep's work list, so
      // claiming a removal that did not happen would lose exactly the recovery this buys.
      if (Files.notExists(workspace, LinkOption.NOFOLLOW_LINKS)) {
        records.sourceCollected(held.id());
        sources++;
      }
    }
    return new Destroyed(
        child, subtree.stream().map(ContainedApplicationRepository.Retained::id).toList(), sources);
  }

  /**
   * The durable half of a destruction, deepest first, inside the caller's unit of work.
   *
   * <p>A {@link DataIntegrityViolationException} here is the race the lock narrows but cannot
   * remove: a creation already past its own container lock commits a row referencing a scope this
   * cascade is deleting, and {@code authority_scopes.container}'s {@code ON DELETE RESTRICT}
   * refuses the delete. That is the same class of conflict {@code
   * JdbcContainedApplicationRepository .create} answers with a {@link CallerFault}, and it is
   * answered the same way here so one loser is not a refusal and the other a server fault. Nothing
   * is lost either way: the unit rolls back, so every row and every byte is still as it was, and
   * the caller may simply ask again.
   *
   * @param subtree the records to remove, deepest first
   * @param root the child this destruction was asked for, written on every record it writes
   */
  private void destroy(List<ContainedApplicationRepository.Retained> subtree, UUID root) {
    try {
      for (var held : subtree) {
        // Zeroed before the rows go, so a descendant mid-execution cannot claim another call
        // between this unit committing and its own next check. The allowance row itself is the
        // allowance owner's and is not deleted here.
        allowances.ceiling(held.allowance(), 0);
        principals.revoke(
            new AuthorityScope.Contained(held.scope(), held.projectId(), held.container()));
        records.collected(
            new ContainedApplicationRepository.Collection(
                held.id(),
                held.scope(),
                held.container(),
                // Every record of this one destruction carries the named child as its root, which
                // is what lets a replay answer for this destruction and no other.
                root,
                held.project(),
                held.revision(),
                workspace(held.project()),
                ContainedApplicationRepository.Cause.DESTROYED,
                false));
        records.destroy(held.id());
      }
    } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
      throw new CallerFault(
          "This contained Application acquired a nested Application concurrently; nothing was"
              + " destroyed, so repeat the request");
    }
  }

  /**
   * The answer to a {@code destroy} whose child has no live record: a retry after an uncertain
   * delivery, resolved against the collection records the destruction itself wrote.
   *
   * <p>ADR 0005's rule for this family is that delivery uncertainty is resolved by inspection and
   * never by automatic replay, and {@code create} carries a request-UUID receipt for exactly that.
   * Destruction resolves it the other way, because it already writes durable evidence with
   * everything a receipt would have to hold: that the destruction happened, whose child it was, and
   * which records went with it. A second request UUID keyed on the same facts would be a second
   * source of truth for one event.
   *
   * <p><strong>Three answers, and they are distinguished.</strong> No record of any kind is "no
   * such contained Application". A record whose {@code container} is not this caller's scope is the
   * same containment refusal a live child would get — the collection row retains the container as a
   * plain value precisely so this stays answerable after the scope it names is gone. A record that
   * matches is a success, and it replays the <em>same</em> answer: the subtree is rebuilt by
   * descending the recorded containment edges from the child's own scope, deepest first, bounded by
   * the configured depth, and restricted at every edge to the records <em>that</em> destruction
   * wrote. The restriction is what makes "the same answer" true rather than nearly true: a
   * descendant destroyed earlier in its own separate destruction sits on the same edge, and
   * including it would answer a retry with a longer {@code collected} list and a larger {@code
   * sources} count than the destruction being replayed ever produced.
   *
   * <p>It also finishes an interrupted source removal, so calling twice is not merely safe but
   * useful: the second call collects the bytes the first could not and marks the record.
   */
  private Destroyed replay(OperationAuthority.Conversation parent, UUID child) {
    var record =
        records
            .collection(child)
            .orElseThrow(() -> new CallerFault("No such contained Application"));
    if (record.cause() != ContainedApplicationRepository.Cause.DESTROYED
        || !parent.admission().scope().equals(record.container()))
      throw new CallerFault("This Application does not contain that Application");
    var rebuilt = new ArrayList<ContainedApplicationRepository.Collection>();
    // Scoped to the destruction this record belongs to, which for a replayable row is the row's own
    // child. The containment edges alone say which records are beneath a scope, not which
    // destruction wrote them, so an unscoped walk also picked up a descendant destroyed earlier in
    // its own separate destruction -- a longer list and a different source count than the answer
    // being replayed.
    descend(record, rebuilt, 0, record.root());
    // Deepest first, as the original answered. The rebuild appends a node before its children, so
    // reversing it yields children before parents at every level.
    Collections.reverse(rebuilt);
    // The same stop, for the same reason, before this retry removes the bytes the original could
    // not. The original destruction asked too, but a destruction interrupted between its commit
    // and its removal may have been interrupted before that ask landed, and the directories this
    // loop is about to collect are exactly the ones such a run would still be reading.
    stop(rebuilt.stream().map(ContainedApplicationRepository.Collection::scope).toList());
    int sources = 0;
    for (var held : rebuilt) if (finish(held)) sources++;
    return new Destroyed(
        child,
        rebuilt.stream().map(ContainedApplicationRepository.Collection::child).toList(),
        sources);
  }

  /**
   * Walks the recorded containment edges downwards, bounded by the configured depth for the reason
   * {@link #requireAncestor} is bounded: a walk that would loop on bad data refuses instead of
   * looping, and the fence cannot be the thing that proves the walk terminates.
   */
  private void descend(
      ContainedApplicationRepository.Collection node,
      List<ContainedApplicationRepository.Collection> into,
      int depth,
      UUID root) {
    if (depth > config.bounds().maxDepth())
      throw new CallerFault("This containment chain exceeds the configured depth");
    into.add(node);
    for (var beneath : records.collectedUnder(node.scope(), root))
      descend(beneath, into, depth + 1, root);
  }

  /** Collects a recorded source area if it is still there, and marks it once it is gone. */
  private boolean finish(ContainedApplicationRepository.Collection record) {
    Path workspace = workspace(record.project());
    collect(workspace);
    if (Files.notExists(workspace, LinkOption.NOFOLLOW_LINKS)) {
      if (!record.sourceRemoved()) records.sourceCollected(record.child());
      return true;
    }
    return false;
  }

  /**
   * A child's source area, derived from its opaque project name under the configured root. Derived
   * rather than read back from the record so that destruction cannot be pointed at a path a stored
   * row claims: the only directory this can ever name is one under {@link ContainmentConfig#root()}
   * whose name is the project's own UUID suffix.
   */
  private Path workspace(String project) {
    if (!project.startsWith(PREFIX))
      throw new CallerFault("A contained project carries the reserved prefix");
    String suffix = project.substring(PREFIX.length());
    Path workspace = config.root().resolve(suffix).normalize();
    if (!workspace.getParent().equals(config.root()) || suffix.contains("/"))
      throw new CallerFault("A contained source area lies directly under the containment root");
    return workspace;
  }

  /**
   * What this container holds directly. One query, keyed on the caller's own admitted scope.
   *
   * <p>No argument of this call names a container, which is the whole of sibling isolation on this
   * path: the scope listed is the scope the caller's retained admission acts as. A contained
   * Application asking gets its own children, so a sibling is not merely absent from the answer —
   * there is no question that would return it.
   */
  @Override
  public List<Child> list(String account, String conversation) {
    var parent = parent(account, conversation);
    return records.children(parent.admission().scope()).stream()
        .map(SourceContainedApplications::child)
        .toList();
  }

  /**
   * One child's retained conversation lineage, after the chain proves the caller contains it.
   *
   * <p>The order is the contract. The caller's own live authority is proved first, then the
   * containment chain is walked from the child upwards until it reaches the caller's scope — a
   * structural resolution, not a permission denial — and only then is any envelope read. A refused
   * read therefore discloses no conversation of the child's: not a lineage, not a count, not that
   * any particular conversation exists.
   *
   * <p>It does not hide the child itself, and this method does not claim it does. The lookup below
   * runs <em>before</em> {@code parent(...)} and {@link #requireAncestor}, so a caller that
   * contains nothing still learns whether an arbitrary UUID names a contained Application on this
   * installation — a one-bit existence oracle, shared verbatim with {@link #resources}. Closing it
   * means proving the caller's authority before the lookup, which changes which fault a nonexistent
   * child raises and is therefore a contract change rather than a reordering; it is recorded as a
   * known limitation in ADR 0042 and deliberately not fixed here.
   */
  @Override
  public List<Step> trajectory(String account, String conversation, UUID child) {
    var retained =
        records.find(child).orElseThrow(() -> new CallerFault("No such contained Application"));
    var parent = parent(account, conversation);
    requireAncestor(parent.admission().scope(), retained);
    return authority.conversationsIn(retained.scope()).stream()
        .map(
            envelope ->
                new Step(
                    envelope.id(),
                    envelope.agent(),
                    envelope.parent(),
                    envelope.budget().value(),
                    envelope.capabilities()))
        .toList();
  }

  /**
   * A live child's staged source as metadata, after the same ancestor gate {@code trajectory} uses.
   *
   * <p>The order is the contract: the child is found, the caller's live authority is proved, the
   * chain must reach the caller, and only then is liveness checked and the directory opened. The
   * directory is derived from the child's project name under the configured root, never from a
   * stored path. Links are neither followed nor listed.
   *
   * <p>The walk itself is bounded, not just the answer: it is lazy, stops at {@link
   * #MAX_RESOURCE_ENTRIES} visited entries or {@link #MAX_RESOURCES} files, and sorts only what it
   * kept. An area past either bound, or a live child whose area is not a real directory, is a
   * server-side inconsistency and not something the container did, so both raise {@link
   * IllegalStateException} rather than a caller fault, and neither of those two cases is ever an
   * empty success.
   *
   * <p>No manifest is required of the listing, and none is asserted. A live child whose revision
   * directory holds no regular file — an interrupted stage — is answered with an empty list, and
   * one holding files but no {@link WorkspaceApplicationPolicy#FILE} is answered without a {@code
   * manifest} row. That is what the walk reports and what the tests pin; a parent agent reading
   * this must not take a non-empty answer, or a {@code manifest} entry, as a package validity
   * claim. Package validity is decided on the producing side, where the manifest is parsed.
   */
  // CORRECTED. This comment used to say that trajectory answers for a destroyed child where this
  // refuses one, and instructed a reader not to harmonise the two. That was never true of this
  // code: destruction deletes the child's project row, contained_applications.project_id is
  // ON DELETE CASCADE (V219), and BOTH methods open with records.find(child).orElseThrow -- so
  // after a real destruction the two refuse identically, with "No such contained Application",
  // and the only difference is a message neither of them reaches. The retained conversation
  // lineage does survive the destruction; nothing can read it, because the row that resolves the
  // child is gone. ContainedResourceReadTest.aRealDestructionMakesBothReadsRefuseIdentically pins
  // that, and ADR 0042 records it along with the retention decision the old belief justified.
  //
  // The liveness guard below is therefore unreachable in production and is kept as a guard rather
  // than as behaviour: contained_applications.live is NOT NULL DEFAULT TRUE (V219) and nothing in
  // this server ever writes it false. What is still true, and is this read's own reason, is that
  // answering from a source area a collection had not finished removing would make a destroyed
  // child's source look readable.
  @Override
  public List<Resource> resources(String account, String conversation, UUID child) {
    var retained =
        records.find(child).orElseThrow(() -> new CallerFault("No such contained Application"));
    var parent = parent(account, conversation);
    if (!config.configured()) throw new CallerFault("Containment is not configured");
    requireAncestor(parent.admission().scope(), retained);
    if (!retained.live()) throw new CallerFault("That contained Application has been destroyed");
    Path staged =
        workspace(retained.project()).resolve("revisions").resolve(retained.revision().toString());
    if (!Files.isDirectory(staged, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException(
          "A live contained Application's source area is missing or is not a directory");
    var found = new ArrayList<Resource>();
    try (var entries = Files.walk(staged, MAX_RESOURCE_DEPTH).limit(MAX_RESOURCE_ENTRIES + 1)) {
      long visited = 0;
      for (Path path : (Iterable<Path>) entries::iterator) {
        if (++visited > MAX_RESOURCE_ENTRIES)
          throw new IllegalStateException(
              "A contained Application's source area exceeds the package limits");
        var attributes =
            Files.readAttributes(
                path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) continue;
        if (found.size() >= MAX_RESOURCES)
          throw new IllegalStateException(
              "A contained Application's source area exceeds the package limits");
        String relative =
            staged.relativize(path).toString().replace(java.io.File.separatorChar, '/');
        found.add(
            new Resource(
                relative,
                relative.equals(WorkspaceApplicationPolicy.FILE) ? "manifest" : "file",
                retained.revision(),
                attributes.size()));
      }
    } catch (IOException unreadable) {
      throw new java.io.UncheckedIOException(
          "Could not list a contained Application's source", unreadable);
    }
    found.sort(java.util.Comparator.comparing(Resource::path));
    return List.copyOf(found);
  }

  /**
   * Refuses unless {@code ancestor} is this child's container or a container of one.
   *
   * <p>The relation is walked in the only direction it is recorded in: a contained scope's own row
   * names its container, so the walk climbs and can only ever reach a scope that genuinely contains
   * the child. A sibling's scope is never on that chain, and neither is a child's own container
   * when the child is the one asking, so both are refused here rather than later.
   *
   * <p>Bounded by the configured depth, which is the same fence the hop enforces. A chain longer
   * than that cannot be created — the hop refuses it — so exceeding it means the stored rows
   * disagree with the bound, and a walk that would loop on bad data refuses instead of looping.
   */
  private void requireAncestor(
      AuthorityIds.ScopeId ancestor, ContainedApplicationRepository.Retained child) {
    var container = child.container();
    var seen = new HashSet<AuthorityIds.ScopeId>();
    seen.add(child.scope());
    for (int hop = 0; hop < config.bounds().maxDepth(); hop++) {
      if (container.equals(ancestor)) return;
      if (!seen.add(container)) break;
      var enclosing = records.ofScope(container);
      if (enclosing.isEmpty()) break;
      container = enclosing.get().container();
    }
    throw new CallerFault("This Application does not contain that Application");
  }

  /** The creating Application's live conversation: its admission and the pool it is spending. */
  private OperationAuthority.Conversation parent(String account, String conversation) {
    var retained =
        authority
            .conversation(conversation)
            .orElseThrow(
                () ->
                    new CallerFault(
                        "A contained Application is created from a live conversation of its"
                            + " container"));
    if (!retained.admission().executor().equals(account))
      throw new CallerFault("This conversation belongs to another executor");
    // Retained evidence is not a live permission: the parent's authority is rechecked here, so a
    // revoked or stale parent creates nothing.
    authority.require(retained.admission());
    if (retained.admission().home().isGlobal())
      throw new CallerFault("A contained Application is created inside a project home");
    return retained;
  }

  private Path containerRoot(String project) {
    return projects
        .find(project)
        .orElseThrow(() -> new CallerFault("The creating Application's source is unavailable"))
        .workspace();
  }

  /**
   * The child's ceiling may not exceed what its container has left. An absent container ceiling is
   * unlimited by {@link AllowanceSnapshot}'s own contract, so it bounds nothing and any explicit
   * child ceiling is within it; the child's own ceiling is still never absent.
   */
  private void requireCeiling(AllowanceId budget, int ceiling) {
    var snapshot =
        allowances
            .find(budget)
            .orElseThrow(
                () -> new CallerFault("The creating Application's allowance is unavailable"));
    if (snapshot.ceiling() == null) return;
    if (ceiling > Math.max(0, snapshot.ceiling() - snapshot.spent()))
      throw new CallerFault(
          "A contained ceiling cannot exceed the creating Application's remaining allowance");
  }

  /** Exactly the parent's own derivable actions; {@code contain} drops anything beyond them. */
  private static Set<AuthorityAction> narrowed(OperationAuthority.Admission parent) {
    var actions = new LinkedHashSet<>(parent.actions());
    actions.retainAll(DERIVABLE);
    if (actions.isEmpty())
      throw new CallerFault("This Application holds no authority it could confine");
    return Set.copyOf(actions);
  }

  /**
   * Writes the package into its freshly allocated, unpredictable revision directory. {@code
   * CREATE_NEW} throughout and {@code NOFOLLOW_LINKS} on every intermediate directory, so no
   * existing path, link or crashed earlier attempt is ever written through.
   */
  private static void stage(Path staged, List<File> files, String project) throws IOException {
    Files.createDirectories(staged.getParent());
    Files.createDirectory(staged);
    for (var file : files) {
      Path target = staged.resolve(file.path()).normalize();
      if (!target.startsWith(staged))
        throw new CallerFault("Contained Application source escaped its staging area");
      Path at = staged;
      for (Path segment : staged.relativize(target.getParent())) {
        at = at.resolve(segment);
        if (Files.notExists(at, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(at);
        if (Files.isSymbolicLink(at) || !Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS))
          throw new CallerFault("Contained Application staging source changed to a link");
      }
      Files.writeString(
          target,
          file.path().equals(WorkspaceApplicationPolicy.FILE)
              ? named(file.text(), project)
              : file.text(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          LinkOption.NOFOLLOW_LINKS);
    }
  }

  /** Validates the staged manifest through the policy that owns it, under the child's own name. */
  private static void manifest(Path staged, String project) throws IOException {
    try {
      WorkspaceApplicationPolicy.parse(
          Files.readString(staged.resolve(WorkspaceApplicationPolicy.FILE)), project);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Contained Application manifest is invalid: " + invalid.getMessage());
    }
  }

  /**
   * Rewrites the manifest's {@code name} to the server-allocated project name.
   *
   * <p>ADR 0005 requires the root manifest to match its project, and here the server owns the
   * project name: it is an opaque generated identifier the author cannot know in advance. {@code
   * name} is identity and not authority, so rewriting it honours no declaration and relaxes no
   * fence — every surface that <em>is</em> authority is compared against the container's hold and
   * refused, never rewritten.
   */
  private static String named(String manifest, String project) {
    try {
      var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(manifest);
      if (body == null || !body.isObject())
        throw new CallerFault("Contained Application manifest must be a JSON object");
      ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("name", project);
      return body.toString();
    } catch (IOException malformed) {
      throw new CallerFault("Contained Application manifest must be a JSON object");
    }
  }

  /**
   * Collects a staged area after a refusal or an interrupted create. Best effort by necessity — the
   * filesystem cannot join the transaction — and silent on failure, because a refusal must report
   * why it refused rather than why its cleanup could not finish. Nothing treats a surviving
   * directory as live: only a committed containment record is.
   */
  private static void collect(Path workspace) {
    if (Files.notExists(workspace, LinkOption.NOFOLLOW_LINKS)) return;
    try (var entries = Files.walk(workspace)) {
      for (Path path : entries.sorted(Comparator.reverseOrder()).toList())
        Files.deleteIfExists(path);
    } catch (IOException | RuntimeException incomplete) {
      // Deliberately swallowed: see above. The refusal that brought us here is the answer.
    }
  }

  /** The boundary conversion: the internal {@code ScopeId} is unwrapped here and nowhere later. */
  private static Child child(ContainedApplicationRepository.Retained retained) {
    return new Child(
        retained.id(), retained.scope().value(), retained.project(), retained.revision());
  }

  /**
   * Receipt identity: the container's request UUID, the ceiling it set and the exact submitted
   * content. Length framing keeps one part's text from impersonating a separator and so from
   * presenting itself as a different request with the same fingerprint.
   */
  private static String fingerprint(Create request) {
    var value = new StringBuilder("contained.create:");
    part(value, request.requestId().toString());
    part(value, String.valueOf(request.ceiling()));
    part(value, SourceApplicationDeployments.digest(request.files()));
    return hash(value.toString());
  }

  private static void part(StringBuilder value, String text) {
    value.append(text.length()).append(':').append(text);
  }

  private static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
