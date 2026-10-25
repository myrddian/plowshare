package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Keeps admission ceilings separate from live supporting authority and resource capability fences.
 */
@Component
public final class PinnedOperationAuthority implements OperationAuthority {
  /**
   * The actions an admission may be narrowed to, whether the narrowing holds scope fixed ({@code
   * derive}) or crosses a containment hop ({@code contain}). The management and administration
   * actions are deliberately absent: a narrowed authority runs work, it does not re-grant it.
   */
  private static final Set<AuthorityAction> DERIVABLE =
      Set.of(
          AuthorityAction.READ,
          AuthorityAction.WRITE,
          AuthorityAction.CONVERSATION_CREATE,
          AuthorityAction.RUN_MODEL);

  private final IdentityRepository identities;
  private final ScopedGrantRepository grants;
  private final ScopedAuthority authority;
  private final ProjectMembers members;
  private final OperationRecords records;
  private final ResourceScopeRepository resources;
  private final io.aeyer.plowshare.server.information.AccountKnowledgePermissions knowledge;

  /** Server configuration, never a caller's argument; absent until an operator configures it. */
  private final ContainmentBounds bounds;

  public PinnedOperationAuthority(
      IdentityRepository identities,
      ScopedGrantRepository grants,
      ScopedAuthority authority,
      ProjectMembers members,
      OperationRecords records,
      ResourceScopeRepository resources) {
    this(
        identities,
        grants,
        authority,
        members,
        records,
        resources,
        io.aeyer.plowshare.server.information.AccountKnowledgePermissions.DENY);
  }

  public PinnedOperationAuthority(
      IdentityRepository identities,
      ScopedGrantRepository grants,
      ScopedAuthority authority,
      ProjectMembers members,
      OperationRecords records,
      ResourceScopeRepository resources,
      io.aeyer.plowshare.server.information.AccountKnowledgePermissions knowledge) {
    this(
        identities,
        grants,
        authority,
        members,
        records,
        resources,
        knowledge,
        ContainmentBounds.absent());
  }

  /**
   * The bounds bean is optional rather than required, so a deployment that has not opted into
   * containment still starts; it then holds {@link ContainmentBounds#absent()} and refuses every
   * containment hop. Absence must not be a startup failure for a capability the server does not
   * use, and it must not become an unlimited default either.
   */
  @org.springframework.beans.factory.annotation.Autowired
  public PinnedOperationAuthority(
      IdentityRepository identities,
      ScopedGrantRepository grants,
      ScopedAuthority authority,
      ProjectMembers members,
      OperationRecords records,
      ResourceScopeRepository resources,
      io.aeyer.plowshare.server.information.AccountKnowledgePermissions knowledge,
      org.springframework.beans.factory.ObjectProvider<ContainmentBounds> bounds) {
    this(
        identities,
        grants,
        authority,
        members,
        records,
        resources,
        knowledge,
        bounds.getIfAvailable(ContainmentBounds::absent));
  }

  public PinnedOperationAuthority(
      IdentityRepository identities,
      ScopedGrantRepository grants,
      ScopedAuthority authority,
      ProjectMembers members,
      OperationRecords records,
      ResourceScopeRepository resources,
      io.aeyer.plowshare.server.information.AccountKnowledgePermissions knowledge,
      ContainmentBounds bounds) {
    this.bounds = Objects.requireNonNull(bounds);
    this.knowledge = Objects.requireNonNull(knowledge);
    this.identities = Objects.requireNonNull(identities);
    this.grants = Objects.requireNonNull(grants);
    this.authority = Objects.requireNonNull(authority);
    this.members = Objects.requireNonNull(members);
    this.records = Objects.requireNonNull(records);
    this.resources = Objects.requireNonNull(resources);
  }

  @Override
  public AuthorityScope scope(Home home) {
    Objects.requireNonNull(home);
    // The global memory tier is no execution scope, and it is refused here rather than resolved so
    // that a caller still holding a legacy home gets the admission refusal it gets today. An
    // account's own space is reached by resolving that scope, never by omitting a project.
    if (home.isGlobal()) throw denied();
    return identities.project(home.project());
  }

  @Override
  public Admission admit(String executor, AuthorityScope target, Set<AuthorityAction> actions) {
    Objects.requireNonNull(target);
    actions = Set.copyOf(actions);
    // The admissible kinds are checked before any identity is resolved, where the global-home
    // refusal used to sit: Global knowledge and administration are separate authorities with their
    // own gates, and an unresolvable executor must not change which refusal a caller sees.
    if (actions.isEmpty()
        || !(target instanceof AuthorityScope.Project || target instanceof AuthorityScope.Personal))
      throw denied();
    var account = identities.account(executor);
    if (account.kind() == IdentityRepository.AccountKind.BUILTIN) throw denied();
    if (target instanceof AuthorityScope.Personal personal
        && !personal.account().equals(account.id())) throw denied();
    // The retained home belongs to the scope by construction rather than by the caller's say-so.
    var home = identities.home(target);
    String source =
        members.authorityAccount(executor).orElseThrow(PinnedOperationAuthority::denied);
    var recipient = GrantRecipient.account(identities.account(source).id());
    var live = grants.live(recipient);
    var support = new ArrayList<Support>();
    for (var action : actions) {
      var binding =
          live.stream()
              .filter(grant -> authority.allowsUsing(recipient, target, action, grant.id()))
              .findFirst()
              .orElseThrow(PinnedOperationAuthority::denied);
      support.add(new Support(action, binding.id()));
    }
    var admission =
        new Admission(
            UUID.randomUUID(),
            executor,
            account.id(),
            target.id(),
            home,
            recipient,
            support,
            actions.contains(AuthorityAction.READ)
                && knowledge.allows(executor, BuiltInIdentities.GLOBAL, AuthorityAction.READ));
    require(admission);
    records.retain(admission);
    return admission;
  }

  @Override
  public Optional<Conversation> conversation(String id) {
    return records.conversation(id);
  }

  @Override
  public void bind(Conversation conversation) {
    requireTargets(
        conversation.admission(),
        conversation.admission().home(),
        List.of(
            new AccessRequest.Resource(
                ResourceScopeRepository.Kind.CONVERSATION, conversation.id())));
    if (!DERIVABLE.containsAll(conversation.admission().actions())) throw denied();
    if (conversation.parent() != null) {
      if (records.parent(conversation.admission().id()).isEmpty()) throw denied();
      requireTargets(
          conversation.admission(),
          conversation.admission().home(),
          List.of(
              new AccessRequest.Resource(
                  ResourceScopeRepository.Kind.CONVERSATION, conversation.parent())));
    }
    records.bind(conversation);
    require(conversation.admission());
  }

  @Override
  public Admission derive(Admission parent, Set<AuthorityAction> actions) {
    require(parent);
    if (actions.isEmpty()
        || !parent.actions().containsAll(actions)
        || !DERIVABLE.containsAll(actions)) throw denied();
    var child =
        new Admission(
            UUID.randomUUID(),
            parent.executor(),
            parent.account(),
            parent.scope(),
            parent.home(),
            parent.source(),
            parent.support().stream().filter(item -> actions.contains(item.action())).toList(),
            parent.globalRead() && actions.contains(AuthorityAction.READ));
    records.retainChild(child, parent.id());
    require(child);
    return child;
  }

  /**
   * One containment hop. The order below is the contract: the structural questions — is containment
   * configured at all, does this child name this parent, is the chain short enough — are resolution
   * failures and are answered before any identity, role or grant is read, so reaching above or
   * beside oneself never becomes a permission question. Only then are the actions narrowed, and
   * only then is the parent's own live authority rechecked.
   *
   * <p>Nothing here can enlarge. The support rows are filtered from the parent's own list, so each
   * carries the parent's grant id and no grant is issued or resolved for the child; the account,
   * executor and source are copied; {@code globalRead} is dropped; and the action set is checked
   * against the parent's retained actions before anything is built. Applied repeatedly, each hop
   * takes a subset of a subset.
   *
   * <p><strong>The child admission is retained and it is live, and the two gates that decide that
   * were opened together.</strong> {@code requireOwn} proves the child's stored row still resolves
   * to the exact home the admission retained — the agreement proof, run in the only direction
   * containment permits, because {@link IdentityRepository#project} still refuses to resolve a
   * contained scope from a project name — and then checks the retained grants against the nearest
   * enclosing uncontained scope, which is the authority this hop was projected from. The ancestry
   * walk accepts an ancestor whose scope and home differ from the child's exactly when the child's
   * own row names that ancestor as its container, and for nothing else. Teaching one gate without
   * the other would have left a contained admission refused anyway, and would have looked like a
   * defect here rather than in the gate that still refused.
   *
   * <p>This method still does not finish with {@code require(child)}. The ancestry edge is retained
   * and the child is checked by whoever goes on to use it, so a hop records evidence of itself even
   * when the resulting authority is never exercised; evidence is not permission.
   */
  @Override
  public Admission contain(
      Admission parent, AuthorityScope.Contained child, Set<AuthorityAction> requested) {
    Objects.requireNonNull(parent);
    Objects.requireNonNull(child);
    var actions = Set.copyOf(requested);
    if (!bounds.configured()) throw denied();
    if (!child.container().equals(parent.scope())) throw denied();
    requireDepth(parent, child);
    if (actions.isEmpty()
        || !parent.actions().containsAll(actions)
        || !DERIVABLE.containsAll(actions)) throw denied();
    // The hop changes home, and resolving the child's home is also what resolves the child. The
    // container comparison above is a free pre-check on a value the caller populated; this is the
    // read that proves the stored row says the same thing. It therefore precedes any authority
    // resolution, so a child naming a sibling subtree fails to resolve rather than being denied a
    // permission. A contained admission fenced to its parent's project would be fenced to the one
    // project the child must not reach, so it carries its own.
    var home = identities.containedHome(child, parent.scope());
    require(parent);
    var contained =
        new Admission(
            UUID.randomUUID(),
            parent.executor(),
            parent.account(),
            child.id(),
            home,
            parent.source(),
            parent.support().stream().filter(item -> actions.contains(item.action())).toList(),
            // Global knowledge is outside every containment subtree and is never carried across.
            false);
    records.retainChild(contained, parent.id());
    return contained;
  }

  /**
   * Refuses a hop whose resulting container chain would exceed the configured depth. Depth is the
   * length of that chain: the child sits one hop below its parent, so the parent's own chain is
   * walked and the child counted on top of it.
   *
   * <p>The walk also refuses a chain that revisits a scope. A cycle is unconstructible — a scope
   * cannot contain itself, a container must already exist when a contained scope is inserted, and
   * V196 forbids repointing an existing one — but a walk that would loop forever if the data were
   * wrong is not a walk worth trusting, so it refuses instead of looping.
   */
  private void requireDepth(Admission parent, AuthorityScope.Contained child) {
    int depth = 1;
    var seen = new HashSet<AuthorityIds.ScopeId>();
    seen.add(child.id());
    // The relation is walked through the read that answers "contained, or nothing", not through
    // the general scope read. That read refuses a CONTAINED row by design, so a walk built on it
    // could only ever observe an uncontained parent and compute depth 1 -- stricter than
    // configured, but it also made a configured depth above 1 unreachable and left this record's
    // claim that authority narrows transitively at depth unexercised by anything that runs.
    var enclosing = identities.contained(parent.scope());
    while (enclosing.isPresent()) {
      if (++depth > bounds.maxDepth() || !seen.add(enclosing.get().id())) throw denied();
      enclosing = identities.contained(enclosing.get().container());
    }
    if (depth > bounds.maxDepth()) throw denied();
  }

  @Override
  public void require(Admission admission) {
    var visited = new HashSet<UUID>();
    for (int depth = 0; ; depth++) {
      if (depth >= 64 || !visited.add(admission.id())) throw denied();
      var containment = requireOwn(admission);
      for (var resource : records.targets(admission.id())) {
        var targets = resources.projects(resource);
        if (targets.size() != 1 || !admission.home().project().equals(targets.getFirst()))
          throw denied();
      }
      var parent = records.parent(admission.id());
      if (parent.isEmpty()) {
        // A contained admission comes into being only through a hop, so an ancestry edge is not
        // optional for one: with no retained parent there is no evidence that any container ever
        // narrowed to it, and a hand-built value must not be accepted for want of a parent.
        if (containment.isPresent()) throw denied();
        return;
      }
      var ancestor = records.find(parent.get()).orElseThrow(PinnedOperationAuthority::denied);
      // GATE TWO. A narrowing that holds scope fixed -- what derive produces -- must still present
      // an ancestor with the identical scope and home. A hop changes both by design, so for a
      // contained admission the equality is replaced by the containment edge itself: the child's
      // own stored row must name this ancestor's scope as its container. Nothing else is relaxed.
      // The admission's home is not taken on trust here either; requireOwn has already proved it
      // is the home that child row resolves to.
      boolean hop =
          containment.isPresent() && containment.get().container().equals(ancestor.scope());
      if (!ancestor.executor().equals(admission.executor())
          || !ancestor.account().equals(admission.account())
          || !hop
              && (!ancestor.scope().equals(admission.scope())
                  || !ancestor.home().equals(admission.home()))
          || !ancestor.source().equals(admission.source())
          || !ancestor.support().containsAll(admission.support())
          || admission.globalRead() && !ancestor.globalRead()) throw denied();
      admission = ancestor;
    }
  }

  /**
   * GATE ONE. An admission's own live authority, and the scope its retained grants are checked
   * against.
   *
   * @return the containment row when this admission is contained, so the ancestry walk above can
   *     ask the same question without a second read
   */
  private Optional<AuthorityScope.Contained> requireOwn(Admission admission) {
    if (admission.globalRead()
        && !knowledge.allows(admission.executor(), BuiltInIdentities.GLOBAL, AuthorityAction.READ))
      throw denied();
    var executor = identities.account(admission.executor());
    if (executor.kind() == IdentityRepository.AccountKind.BUILTIN
        || !executor.id().equals(admission.account())) throw denied();
    var containment = identities.contained(admission.scope());
    String project;
    AuthorityScope scope;
    if (containment.isEmpty()) {
      // Unchanged, and this is still the path every pre-existing admission takes. One read
      // resolves the scope AND proves the two retained columns still agree: the admission's
      // project name must still map to the admission's scope id. Resolving by scope id instead
      // would be a read of the same size that cannot see a disagreement -- and the role ceiling
      // below is keyed by project name either way, so a project that was renamed, or dropped and
      // recreated under the same name, would have its new members' roles checked against the old
      // scope's retained grants. A stale admission must die here instead.
      scope = identities.project(admission.home().project());
      if (!scope.id().equals(admission.scope())) throw denied();
      project = admission.home().project();
    } else {
      // The same agreement proof in the only direction containment permits. project(String) still
      // refuses to resolve a contained scope from a project name -- so nothing travels from a
      // child's name back to its scope -- and the proof therefore runs the other way: the stored
      // child row must still resolve to the exact home this admission retained. A renamed, dropped
      // or recreated child project makes the two disagree and kills the admission here, which is
      // what the replaced check bought and what this must not lose.
      if (!bounds.configured()) throw denied();
      // Global knowledge sits outside every containment subtree and contain drops it
      // unconditionally, so a contained admission claiming it was not produced by a hop.
      if (admission.globalRead()) throw denied();
      var child = containment.get();
      if (!identities.containedHome(child, child.container()).equals(admission.home()))
        throw denied();
      // A child's authority is a projection of its container's: contain copies the container's own
      // grant rows rather than issuing any, and a grant against a contained scope is excluded from
      // issuance and from coverage by design. So the live check walks to the nearest enclosing
      // uncontained scope and asks there -- which is the same question the hop answered, asked
      // again now. The two extra reads are the cost of authority that genuinely lives in another
      // row; a contained admission cannot reuse a project name it does not have.
      scope = mediating(child);
      project = identities.home(scope).project();
    }
    var source =
        members
            .authorityAccount(admission.executor())
            .orElseThrow(PinnedOperationAuthority::denied);
    if (!GrantRecipient.account(identities.account(source).id()).equals(admission.source()))
      throw denied();
    var role =
        members.role(project, admission.executor()).orElseThrow(PinnedOperationAuthority::denied);
    for (var item : admission.support()) {
      if (!ceiling(role, item.action())
          || !authority.allowsUsing(admission.source(), scope, item.action(), item.grant()))
        throw denied();
    }
    return containment;
  }

  /**
   * The nearest enclosing uncontained scope: the one whose live grants a hop was projected from.
   *
   * <p>Bounded by the configured depth and by a visited set, for the same reason {@code
   * requireDepth} is: a fence that depends on a walk terminating cannot also be what proves it
   * terminates. A chain longer than the configured depth refuses rather than being followed, so a
   * depth bound lowered after a child was created closes over that child on its next live check.
   */
  private AuthorityScope mediating(AuthorityScope.Contained child) {
    var seen = new HashSet<AuthorityIds.ScopeId>();
    seen.add(child.id());
    var enclosing = child.container();
    for (int depth = 1; ; depth++) {
      if (depth > bounds.maxDepth() || !seen.add(enclosing)) throw denied();
      var contained = identities.contained(enclosing);
      if (contained.isEmpty()) return identities.scope(enclosing);
      enclosing = contained.get().container();
    }
  }

  private static boolean ceiling(ProjectRole role, AuthorityAction action) {
    return switch (action) {
      case READ -> true;
      case WRITE, CONVERSATION_CREATE, RUN_MODEL -> role != ProjectRole.VIEWER;
      case SET_AUDIENCE, MANAGE_ACCESS, MANAGE_SCOPE -> role == ProjectRole.MANAGER;
      case ADMINISTER -> false;
    };
  }

  @Override
  public void requireTargets(
      Admission admission, Home home, List<AccessRequest.Resource> references) {
    require(admission);
    if (!admission.home().equals(home)) throw denied();
    for (var reference : references) {
      var projects = resources.projects(reference);
      if (projects.size() != 1 || !home.project().equals(projects.getFirst())) throw denied();
    }
  }

  @Override
  public void requireReadTargets(
      Admission admission, Home home, List<AccessRequest.Resource> references) {
    require(admission);
    if (!admission.actions().contains(AuthorityAction.READ)
        || !admission.home().equals(home) && !(home.isGlobal() && admission.globalRead()))
      throw denied();
    for (var reference : references) {
      var projects = resources.projects(reference);
      if (projects.size() != 1) throw denied();
      var project = projects.getFirst();
      if (!admission.home().project().equals(project)
          && !(project == null
              && reference.kind() == ResourceScopeRepository.Kind.MEMORY
              && admission.globalRead())) throw denied();
    }
  }

  @Override
  public void started(Admission admission, String job) {
    require(admission);
    records.started(admission.id(), job);
  }

  private static CallerFault denied() {
    return new CallerFault("Operation is outside its retained scoped authority");
  }
}
