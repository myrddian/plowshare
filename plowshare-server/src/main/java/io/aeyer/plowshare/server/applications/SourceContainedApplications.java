package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.protocol.ApplicationDeployment.File;
import io.aeyer.plowshare.server.access.*;
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
 * made: this class holds neither {@code ProjectMembers} nor {@code FileStores}. It holds no {@code
 * IdentityRepository} either, which is the structural form of a narrower point: nothing on this
 * path resolves a scope through the general scope read, so nothing on it can be defeated by that
 * read's refusal of a {@code CONTAINED} row. The authority is the creating Application's own
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

  private final ContainedApplicationRepository records;
  private final ProjectWorkspaces projects;
  private final ApplicationPackageValidator validator;
  private final OperationAuthority authority;
  private final ContainedPrincipals principals;
  private final AllowanceRepository allowances;
  private final UnitOfWork work;
  private final ContainmentConfig config;
  private final ContainedStaging staging;

  public SourceContainedApplications(
      ContainedApplicationRepository records,
      ProjectWorkspaces projects,
      ApplicationPackageValidator validator,
      OperationAuthority authority,
      ContainedPrincipals principals,
      AllowanceRepository allowances,
      UnitOfWork work,
      ContainmentConfig config,
      ContainedStaging staging) {
    this.records = Objects.requireNonNull(records);
    this.projects = Objects.requireNonNull(projects);
    this.validator = Objects.requireNonNull(validator);
    this.authority = Objects.requireNonNull(authority);
    this.principals = Objects.requireNonNull(principals);
    this.allowances = Objects.requireNonNull(allowances);
    this.work = Objects.requireNonNull(work);
    this.config = Objects.requireNonNull(config);
    this.staging = Objects.requireNonNull(staging);
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
    // Never empty: find() just resolved this child, and the subtree read selects it at depth 0.
    var subtree = records.subtree(retained.scope());
    work.inTransaction(
        () -> {
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
                    held.project(),
                    held.revision(),
                    workspace(held.project()),
                    ContainedApplicationRepository.Cause.DESTROYED,
                    false));
            records.destroy(held.id());
          }
          return null;
        });
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
   * the configured depth.
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
    descend(record, rebuilt, 0);
    // Deepest first, as the original answered. The rebuild appends a node before its children, so
    // reversing it yields children before parents at every level.
    Collections.reverse(rebuilt);
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
      int depth) {
    if (depth > config.bounds().maxDepth())
      throw new CallerFault("This containment chain exceeds the configured depth");
    into.add(node);
    for (var beneath : records.collectedUnder(node.scope())) descend(beneath, into, depth + 1);
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
   * read therefore learns nothing about the child, not even that its conversations exist.
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
