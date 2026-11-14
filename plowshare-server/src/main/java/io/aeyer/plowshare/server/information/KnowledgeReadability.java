package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.information.KnowledgeFact.Audience;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The targeted readability check of {@link JdbcInformationCatalogueRepository#readable} evaluated
 * in Java: {@code knowledge_readable_targets} over one target (V198), which is {@code
 * knowledge_readable_candidates} (V229) with no corpus, kind, heads or related resource, the target
 * as the only candidate and excluded revisions included, followed by the repository's outer filter:
 * the row in {@code information_revision_allowances} is active and passes the typed-content filter
 * of {@link InformationSql#content}.
 *
 * <p>{@link #retainedInputs} evaluates, over the same facts, the {@code readable} column of {@code
 * information_readable_input_selections} (V235), which log visibility applies to every retained job
 * input ({@link LogReadability}). It shares the closure, node and public-dependency walks but keeps
 * V235's own {@code facts.readable}, {@code general_readable} and selection arms.
 *
 * <p>Facts come from {@link Facts}, authority from an {@link AuthoritySnapshot}. Each private
 * method names the SQL clause it mirrors. {@code KnowledgeReadabilityDifferentialDatabaseTest}
 * compares this class with the SQL routine on generated fixtures; a migration that changes one of
 * the mirrored routines must change this class in the same change.
 *
 * <p>SQL three-valued logic: every mirrored predicate is a conjunction or disjunction of
 * comparisons with no negation over a nullable operand, and each is finally read through {@code
 * coalesce(...,false)} or a WHERE clause, so a NULL comparison is treated as false throughout.
 */
public final class KnowledgeReadability {
  /** The closure bound of {@code knowledge_dependency_revisions} (V172, LIMIT 1025). */
  static final int CLOSURE_LIMIT = 1024;

  private KnowledgeReadability() {}

  /** The arguments of {@code knowledge_readable_targets} that a targeted check binds. */
  public record Request(
      String actor,
      String scope,
      String project,
      boolean includeGlobal,
      String boundaryHome,
      Boolean boundaryGlobal,
      boolean typedView,
      UUID target) {
    public Request {
      Objects.requireNonNull(actor);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(target);
    }

    /** Exactly the values {@code JdbcInformationCatalogueRepository.targetArguments} binds. */
    public static Request of(InformationContext context, UUID revision) {
      var boundary = context.readBoundary();
      return new Request(
          context.account(),
          context.selection().scope().name().toLowerCase(Locale.ROOT),
          context.selection().project(),
          context.selection().includeGlobal(),
          boundary == null ? null : boundary.home().project(),
          boundary == null ? null : boundary.globalRead(),
          context.typedView(),
          revision);
    }
  }

  /**
   * The facts one evaluation reads. Empty means the fact cannot be served here (for example a
   * per-evaluation budget is spent), and the evaluation is abandoned for the SQL path.
   */
  public interface Facts {
    Optional<KnowledgeFact> revision(UUID id);

    Optional<KnowledgeFact> resource(UUID id);
  }

  /**
   * The verdict of the SQL check, or empty when a fact could not be served or a row is {@link
   * KnowledgeFact.Oversized}; the caller must then ask SQL.
   */
  public static Optional<Boolean> readable(
      Request request, AuthoritySnapshot authority, Facts facts) {
    var evaluation = new Evaluation(request, authority, facts);
    try {
      return Optional.of(evaluation.admitted());
    } catch (Unservable abandoned) {
      // Not an error: a fact this evaluation needs is not available in memory. The SQL path
      // answers instead, so nothing is decided from a partial walk.
      return Optional.empty();
    }
  }

  /**
   * The {@code readable} column of {@code information_readable_input_selections(actor, ...)} (V235)
   * for retained selections, one evaluator per actor and evaluation: the nodes, closures and
   * public-dependency walks it computes are independent of the selection, so they are shared across
   * the inputs it is asked about. Not thread-safe.
   *
   * @param actor the reading account; never null (a NULL account reads no retained input, which
   *     {@link LogReadability} decides without authority)
   */
  public static RetainedInputs retainedInputs(
      String actor, AuthoritySnapshot authority, Facts facts) {
    return new RetainedInputs(new Evaluation(Objects.requireNonNull(actor), authority, facts));
  }

  /** See {@link #retainedInputs}. */
  public static final class RetainedInputs {
    private final Evaluation evaluation;

    private RetainedInputs(Evaluation evaluation) {
      this.evaluation = evaluation;
    }

    /**
     * Whether the actor's current READ authority admits this retained selection, or empty when a
     * fact could not be served or a row is {@link KnowledgeFact.Oversized}; the caller must then
     * ask SQL.
     */
    public Optional<Boolean> readable(LogTreeFact.JobInput input) {
      Objects.requireNonNull(input);
      try {
        return Optional.of(evaluation.retainedAdmitted(input));
      } catch (Unservable abandoned) {
        return Optional.empty();
      }
    }
  }

  /** Control signal only: abandons the evaluation without deciding anything. */
  private static final class Unservable extends RuntimeException {
    Unservable() {
      super("Readability fact unavailable", null, false, false);
    }
  }

  /**
   * One {@code base_facts} row (V229): a revision joined to its resource, home scope and root
   * policy, all inner joins, with the home project's name from a left join.
   */
  private record Node(
      KnowledgeFact.Revision revision,
      KnowledgeFact.Resource resource,
      AuthoritySnapshot.Scope scope,
      KnowledgeFact.RootPolicy policy,
      Optional<String> projectName) {}

  private static final class Evaluation {
    /** The targeted check's arguments; null for a retained-input evaluation. */
    private final Request request;

    private final String actor;
    private final AuthoritySnapshot authority;
    private final Facts facts;
    private AuthoritySnapshot.KnowledgeReader reader;
    private final Map<UUID, Boolean> retainedGenerallyReadable = new HashMap<>();
    private final Map<UUID, Optional<KnowledgeFact.Revision>> revisions = new HashMap<>();
    private final Map<UUID, Optional<KnowledgeFact.Resource>> resources = new HashMap<>();
    private final Map<UUID, Optional<Node>> nodes = new HashMap<>();
    private final Map<UUID, Boolean> publicPolicies = new HashMap<>();
    private final Map<UUID, Boolean> requiredReferences = new HashMap<>();
    private final Map<UUID, Boolean> generallyReadable = new HashMap<>();

    Evaluation(Request request, AuthoritySnapshot authority, Facts facts) {
      this.request = request;
      this.actor = request.actor();
      this.authority = authority;
      this.facts = facts;
    }

    Evaluation(String actor, AuthoritySnapshot authority, Facts facts) {
      this.request = null;
      this.actor = actor;
      this.authority = authority;
      this.facts = facts;
    }

    boolean admitted() {
      reader = authority.knowledgeReader(actor);
      UUID target = request.target();
      // candidates: information_revisions r JOIN information_resources q, r.id=ANY(targets).
      var root = revision(target);
      if (root.isEmpty()) return false;
      var resource = resource(root.get().resource());
      if (resource.isEmpty()) return false;
      // candidates: availability='active'; ($14 OR NOT excluded) holds because $14 is true.
      if (!root.get().active()) return false;
      // candidates: the selection baseline, then the root selection arms.
      if (!(reader.generalMember() || reader.contained())) return false;
      if (!candidate(root.get(), resource.get())) return false;
      // accepted: count(*)<=1024 over the closure, and readable/in_boundary for every node.
      var closure = closure(target);
      if (closure == null) return false;
      for (UUID id : closure) {
        var node = node(id);
        if (node.isEmpty() || !readable(node.get()) || !inBoundary(node.get())) return false;
      }
      // JOIN facts root_fact ... root_fact.selected
      var rootFact = node(target);
      if (rootFact.isEmpty() || !selected(rootFact.get())) return false;
      // The repository's outer filter: JOIN information_revision_allowances r WHERE
      // r.availability='active' AND InformationSql.content(context,'r').
      return root.get().allowance()
          && (request.typedView() || (!root.get().typedContent() && !root.get().explicitOnly()));
    }

    /**
     * The {@code candidates} selection arms (V229) for the root, over LEFT JOINs of its home scope,
     * the scope's project and its root policy. Related-resource arms do not apply: a targeted check
     * binds no related resource.
     */
    private boolean candidate(KnowledgeFact.Revision root, KnowledgeFact.Resource resource) {
      var scope = resource.homeScope().flatMap(authority::scope);
      var policy = resource.policy();
      boolean member = reader.generalMember();
      String selected = request.scope();
      String project = request.project();
      boolean includeGlobal = request.includeGlobal();
      boolean globalAudience =
          policy.isPresent() && policy.get().audience() == Audience.GLOBAL_READ;
      // (a.general_member AND $2='global_read' AND $3 IS NULL AND NOT $4 AND
      // audience='GLOBAL_READ')
      if (member
          && selected.equals("global_read")
          && project == null
          && !includeGlobal
          && globalAudience) return true;
      // (a.general_member AND $2='account' AND $3 IS NULL AND NOT $4 AND
      // builtin='GLOBAL_KNOWLEDGE')
      if (member
          && selected.equals("account")
          && project == null
          && !includeGlobal
          && scope.isPresent()
          && scope.get().builtin().equals(Optional.of("GLOBAL_KNOWLEDGE"))) return true;
      if (!selected.equals("personal") && !selected.equals("project")) return false;
      if (scope.isPresent()
          && personalArms(root, scope.get(), homeName(scope.get()), selected, project)) return true;
      // (a.general_member AND $4 AND root_policy.audience='GLOBAL_READ')
      return member && includeGlobal && globalAudience;
    }

    /**
     * The four personal/project arms shared by {@code candidates} and {@code facts.selected}
     * (V229): own account knowledge under the personal selection; a readable home in the selected
     * project; own account knowledge linked to the selected project through a readable scope; own
     * account knowledge when the selected project is the actor's personal project.
     */
    private boolean personalArms(
        KnowledgeFact.Revision revision,
        AuthoritySnapshot.Scope scope,
        Optional<String> home,
        String selected,
        String project) {
      boolean ownAccountKnowledge =
          scope.kind().equals("ACCOUNT_KNOWLEDGE") && same(scope.account(), reader.actorId());
      if (selected.equals("personal")
          && project == null
          && ownAccountKnowledge
          && scope.builtin().isEmpty()) return true;
      if (!selected.equals("project")) return false;
      if (home.isPresent()
          && home.get().equals(project)
          && reader.readableScopes().contains(scope.id())) return true;
      if (ownAccountKnowledge && linkedToSelectedProject(revision, project)) return true;
      return ownAccountKnowledge
          && scope.builtin().isEmpty()
          && authority.personalProject(project, actor);
    }

    /**
     * {@code EXISTS(SELECT 1 FROM information_links link JOIN projects linked ON
     * linked.id=link.project_id JOIN authority_scopes ON project_id=linked.id JOIN readable_scopes
     * WHERE link.revision_id=r.id AND linked.name=$3)} (V229).
     */
    private boolean linkedToSelectedProject(KnowledgeFact.Revision revision, String project) {
      for (long link : revision.links()) {
        var linked = authority.project(link);
        if (linked.isEmpty() || !linked.get().name().equals(project)) continue;
        for (var scope : authority.projectScopes(link))
          if (reader.readableScopes().contains(scope.id())) return true;
      }
      return false;
    }

    /**
     * {@code facts.readable} (V229): an active revision with a HOME or GLOBAL_READ audience outside
     * a RESTRICTED home, whose privacy admits the actor, in a readable home scope or generally
     * readable for a non-contained actor.
     */
    private boolean readable(Node node) {
      var policy = node.policy();
      boolean privacy =
          (!policy.privateOnly() && !node.resource().navigation().ownerOnly())
              || same(policy.privacyPrincipal(), reader.actorId());
      return (reader.generalMember() || reader.contained())
          && node.revision().active()
          && (policy.audience() == Audience.HOME || policy.audience() == Audience.GLOBAL_READ)
          && !node.scope().kind().equals("RESTRICTED")
          && privacy
          && (reader.readableScopes().contains(node.scope().id())
              || (!reader.contained() && generalReadable(node)));
    }

    /**
     * {@code facts.selected} (V229), evaluated for the root only: general arms closed to a
     * contained actor, then the personal/project arms, then the include-global arm.
     */
    private boolean selected(Node node) {
      boolean contained = reader.contained();
      String selected = request.scope();
      String project = request.project();
      boolean includeGlobal = request.includeGlobal();
      if (!contained
          && selected.equals("global_read")
          && project == null
          && !includeGlobal
          && generalReadable(node)) return true;
      if (!contained
          && selected.equals("account")
          && project == null
          && !includeGlobal
          && node.scope().builtin().equals(Optional.of("GLOBAL_KNOWLEDGE"))) return true;
      if (!selected.equals("personal") && !selected.equals("project")) return false;
      if (personalArms(node.revision(), node.scope(), node.projectName(), selected, project))
        return true;
      return !contained && includeGlobal && generalReadable(node);
    }

    /**
     * One row of {@code information_readable_input_selections} (V235): the selection survives the
     * {@code selections} join and prune, every node of its closure is {@code facts.readable}, the
     * closure has at most 1,024 nodes ({@code accepted}), and the root's facts row satisfies one of
     * the retained selection arms.
     */
    boolean retainedAdmitted(LogTreeFact.JobInput input) {
      if (reader == null) reader = authority.knowledgeReader(actor);
      UUID target = input.revision();
      // selections: JOIN information_revisions r JOIN information_resources resource JOIN
      // information_resource_policies policy, pruned to a readable home or a GLOBAL_READ audience.
      // The prune is implied by the root's own facts.readable below; it is mirrored, not relied on.
      var root = revision(target);
      if (root.isEmpty()) return false;
      var resource = resource(root.get().resource());
      if (resource.isEmpty() || resource.get().policy().isEmpty()) return false;
      boolean readableHome =
          resource.get().homeScope().map(reader.readableScopes()::contains).orElse(false);
      if (!readableHome && resource.get().policy().get().audience() != Audience.GLOBAL_READ)
        return false;
      // accepted: count(*)<=1024 AND bool_and(coalesce(f.readable,false)) over the closure.
      var closure = closure(target);
      if (closure == null) return false;
      for (UUID id : closure) {
        var node = node(id);
        if (node.isEmpty() || !retainedReadable(node.get())) return false;
      }
      // LEFT JOIN facts f ON f.id=selected.revision_id: a NULL row satisfies no arm.
      var rootFact = node(target);
      return rootFact.isPresent() && retainedSelected(rootFact.get(), input);
    }

    /**
     * {@code facts.readable} of V235: unlike V229 it requires general membership (false for a
     * contained actor, V229's knowledge_read_authority) and has no contained-actor guard on the
     * general arm.
     */
    private boolean retainedReadable(Node node) {
      var policy = node.policy();
      boolean privacy =
          (!policy.privateOnly() && !node.resource().navigation().ownerOnly())
              || same(policy.privacyPrincipal(), reader.actorId());
      return reader.generalMember()
          && node.revision().active()
          && (policy.audience() == Audience.HOME || policy.audience() == Audience.GLOBAL_READ)
          && !node.scope().kind().equals("RESTRICTED")
          && privacy
          && (reader.readableScopes().contains(node.scope().id()) || retainedGeneralReadable(node));
    }

    /**
     * The retained selection arms of V235's final SELECT over the root's facts row. The selection
     * is stored text compared literally, so an unknown scope satisfies no arm.
     */
    private boolean retainedSelected(Node node, LogTreeFact.JobInput input) {
      String selected = input.scope();
      String project = input.project().orElse(null);
      boolean shared = input.includeShared();
      if (selected.equals("global_read")
          && project == null
          && !shared
          && retainedGeneralReadable(node)) return true;
      if (selected.equals("account")
          && project == null
          && !shared
          && node.scope().builtin().equals(Optional.of("GLOBAL_KNOWLEDGE"))) return true;
      if (!selected.equals("personal") && !selected.equals("project")) return false;
      if (personalArms(node.revision(), node.scope(), node.projectName(), selected, project))
        return true;
      return shared && retainedGeneralReadable(node);
    }

    /**
     * {@code public_facts.general_readable} of V235: V229's column without its {@code
     * scope_kind<>'CONTAINED'} conjunct. {@code information_public_dependencies} is unchanged and
     * keeps its own contained exclusion through {@code information_public_policy}.
     */
    private boolean retainedGeneralReadable(Node node) {
      UUID id = node.revision().id();
      var known = retainedGenerallyReadable.get(id);
      if (known != null) return known;
      var policy = node.policy();
      boolean value =
          policy.audience() == Audience.GLOBAL_READ
              && !node.scope().kind().equals("RESTRICTED")
              && !policy.privateOnly()
              && !node.resource().navigation().ownerOnly()
              && node.revision().active()
              && publicDependencies(id);
      retainedGenerallyReadable.put(id, value);
      return value;
    }

    /**
     * {@code facts.in_boundary} (V229): no ceiling; or a readable home that is the boundary home's
     * project or a contained scope inside it ({@code boundary_descendants}); or own account
     * knowledge when the boundary home is the actor's personal project; or, with a global ceiling,
     * a generally readable revision for a general member.
     */
    private boolean inBoundary(Node node) {
      Boolean global = request.boundaryGlobal();
      if (global == null) return true;
      String home = request.boundaryHome();
      var scope = node.scope();
      boolean insideHome =
          (node.projectName().isPresent() && node.projectName().get().equals(home))
              || authority.containedScopeInHome(scope.id(), home);
      if (insideHome && reader.readableScopes().contains(scope.id())) return true;
      if (scope.kind().equals("ACCOUNT_KNOWLEDGE")
          && scope.builtin().isEmpty()
          && same(scope.account(), reader.actorId())
          && authority.personalProject(home, actor)) return true;
      return global && reader.generalMember() && generalReadable(node);
    }

    /**
     * {@code public_facts.general_readable} (V229): a GLOBAL_READ audience outside a RESTRICTED or
     * CONTAINED home, neither private nor owner-only, active, with public dependencies.
     */
    private boolean generalReadable(Node node) {
      UUID id = node.revision().id();
      var known = generallyReadable.get(id);
      if (known != null) return known;
      boolean value = publicShape(node) && publicDependencies(id);
      generallyReadable.put(id, value);
      return value;
    }

    /** The conjuncts shared by {@code general_readable} and {@code information_public_policy}. */
    private static boolean publicShape(Node node) {
      String kind = node.scope().kind();
      return node.policy().audience() == Audience.GLOBAL_READ
          && !kind.equals("RESTRICTED")
          && !kind.equals("CONTAINED")
          && !node.policy().privateOnly()
          && !node.resource().navigation().ownerOnly()
          && node.revision().active();
    }

    /**
     * {@code coalesce(information_public_policy(id),false)} (V223): {@link #publicShape} over the
     * same inner joins; no row (including a NULL id) is false.
     */
    private boolean publicPolicy(UUID id) {
      if (id == null) return false;
      var known = publicPolicies.get(id);
      if (known != null) return known;
      var node = node(id);
      boolean value = node.isPresent() && publicShape(node.get());
      publicPolicies.put(id, value);
      return value;
    }

    /**
     * {@code information_public_dependencies(target)} (V158): over the target and its transitive
     * inputs, each is the target or has a public policy, and each has public required references.
     */
    private boolean publicDependencies(UUID target) {
      var inputs = new LinkedHashSet<UUID>();
      var queue = new ArrayList<UUID>();
      inputs.add(target);
      queue.add(target);
      for (int index = 0; index < queue.size(); index++) {
        var revision = revision(queue.get(index));
        if (revision.isEmpty()) continue;
        for (UUID input : revision.get().inputs()) if (inputs.add(input)) queue.add(input);
      }
      for (UUID id : inputs)
        if (!((id.equals(target) || publicPolicy(id)) && requiredReferencesPublic(id)))
          return false;
      return true;
    }

    /**
     * {@code information_required_references_public(target)} (V158): the obligation dependencies of
     * the target's resource and everything they reach through inputs and obligations, at most 1,024
     * of them, all with a public policy. No obligation is vacuously true.
     */
    private boolean requiredReferencesPublic(UUID target) {
      var known = requiredReferences.get(target);
      if (known != null) return known;
      var required = new LinkedHashSet<UUID>();
      var queue = new ArrayList<UUID>();
      boolean value = true;
      var revision = revision(target);
      if (revision.isPresent()) {
        for (UUID start : obligationEdges(revision.get()))
          if (required.add(start)) queue.add(start);
        value = walk(required, queue);
        if (value) for (UUID id : required) if (!publicPolicy(id)) value = false;
      }
      requiredReferences.put(target, value);
      return value;
    }

    /**
     * {@code knowledge_dependency_revisions(revision)} (V172): the revision and every revision its
     * inputs and reference obligations reach, deduplicated, or null past {@link #CLOSURE_LIMIT}
     * nodes. An unpinned obligation on a resource without revisions contributes one NULL node.
     */
    private LinkedHashSet<UUID> closure(UUID root) {
      var closure = new LinkedHashSet<UUID>();
      var queue = new ArrayList<UUID>();
      closure.add(root);
      queue.add(root);
      return walk(closure, queue) ? closure : null;
    }

    /**
     * Extends {@code seen} breadth first along {@link #edges}, false as soon as it holds more than
     * {@link #CLOSURE_LIMIT} entries (the {@code LIMIT 1025} sentinel of V158 and V172).
     */
    private boolean walk(LinkedHashSet<UUID> seen, List<UUID> queue) {
      if (seen.size() > CLOSURE_LIMIT) return false;
      for (int index = 0; index < queue.size(); index++) {
        UUID current = queue.get(index);
        if (current == null) continue;
        for (UUID next : edges(current)) {
          if (!seen.add(next)) continue;
          if (seen.size() > CLOSURE_LIMIT) return false;
          queue.add(next);
        }
      }
      return true;
    }

    /**
     * The recursive term of {@code knowledge_dependency_revisions} (V172): inputs of the revision,
     * and the dependency of every reference obligation of its resource. A missing revision has
     * neither.
     */
    private List<UUID> edges(UUID id) {
      var revision = revision(id);
      if (revision.isEmpty()) return List.of();
      var edges = new ArrayList<UUID>(revision.get().inputs());
      edges.addAll(obligationEdges(revision.get()));
      return edges;
    }

    /** {@code coalesce(o.pinned_revision,information_navigation_head(o.target))} per obligation. */
    private List<UUID> obligationEdges(KnowledgeFact.Revision revision) {
      var resource = resource(revision.resource());
      if (resource.isEmpty()) return List.of();
      var edges = new ArrayList<UUID>();
      for (var obligation : resource.get().obligations())
        edges.add(
            obligation
                .pinned()
                .orElseGet(
                    () ->
                        resource(obligation.target())
                            .flatMap(target -> target.head())
                            .orElse(null)));
      return edges;
    }

    private Optional<Node> node(UUID id) {
      if (id == null) return Optional.empty();
      var known = nodes.get(id);
      if (known != null) return known;
      Optional<Node> node = Optional.empty();
      var revision = revision(id);
      var resource = revision.flatMap(row -> resource(row.resource()));
      if (resource.isPresent()) {
        var scope = resource.get().homeScope().flatMap(authority::scope);
        var policy = resource.get().policy();
        if (scope.isPresent() && policy.isPresent())
          node =
              Optional.of(
                  new Node(
                      revision.get(),
                      resource.get(),
                      scope.get(),
                      policy.get(),
                      homeName(scope.get())));
      }
      nodes.put(id, node);
      return node;
    }

    /** {@code LEFT JOIN projects p ON p.id=s.project_id}: the home scope's project name. */
    private Optional<String> homeName(AuthoritySnapshot.Scope scope) {
      return scope.project().flatMap(authority::project).map(AuthoritySnapshot.Project::name);
    }

    private Optional<KnowledgeFact.Revision> revision(UUID id) {
      var known = revisions.get(id);
      if (known != null) return known;
      var fact = facts.revision(id).orElseThrow(Unservable::new);
      Optional<KnowledgeFact.Revision> value =
          switch (fact) {
            case KnowledgeFact.Revision row -> Optional.of(row);
            case KnowledgeFact.Absent absent -> Optional.empty();
            case KnowledgeFact.Oversized oversized -> throw new Unservable();
            case KnowledgeFact.Resource wrong ->
                throw new IllegalStateException("Revision fact source returned a resource");
          };
      revisions.put(id, value);
      return value;
    }

    private Optional<KnowledgeFact.Resource> resource(UUID id) {
      var known = resources.get(id);
      if (known != null) return known;
      var fact = facts.resource(id).orElseThrow(Unservable::new);
      Optional<KnowledgeFact.Resource> value =
          switch (fact) {
            case KnowledgeFact.Resource row -> Optional.of(row);
            case KnowledgeFact.Absent absent -> Optional.empty();
            case KnowledgeFact.Oversized oversized -> throw new Unservable();
            case KnowledgeFact.Revision wrong ->
                throw new IllegalStateException("Resource fact source returned a revision");
          };
      resources.put(id, value);
      return value;
    }

    /** {@code a = b} for two nullable columns: NULL on either side is not equal. */
    private static boolean same(Optional<UUID> left, Optional<UUID> right) {
      return left.isPresent() && left.equals(right);
    }
  }
}
