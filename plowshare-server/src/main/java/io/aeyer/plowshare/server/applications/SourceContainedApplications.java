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

  private static final String PREFIX = "contained:";

  private final ContainedApplicationRepository records;
  private final ProjectWorkspaces projects;
  private final ApplicationPackageValidator validator;
  private final OperationAuthority authority;
  private final ContainedPrincipals principals;
  private final AllowanceRepository allowances;
  private final UnitOfWork work;
  private final ContainmentConfig config;

  public SourceContainedApplications(
      ContainedApplicationRepository records,
      ProjectWorkspaces projects,
      ApplicationPackageValidator validator,
      OperationAuthority authority,
      ContainedPrincipals principals,
      AllowanceRepository allowances,
      UnitOfWork work,
      ContainmentConfig config) {
    this.records = Objects.requireNonNull(records);
    this.projects = Objects.requireNonNull(projects);
    this.validator = Objects.requireNonNull(validator);
    this.authority = Objects.requireNonNull(authority);
    this.principals = Objects.requireNonNull(principals);
    this.allowances = Objects.requireNonNull(allowances);
    this.work = Objects.requireNonNull(work);
    this.config = Objects.requireNonNull(config);
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
      var allowance = new AllowanceId(UUID.randomUUID());
      return work.inTransaction(
          () -> {
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
          });
    } catch (RuntimeException | IOException failed) {
      collect(workspace);
      throw failed instanceof RuntimeException refused
          ? refused
          : new CallerFault("Contained Application source could not be staged");
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
