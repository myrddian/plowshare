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
   * <p><strong>The child admission is retained but is not yet live, and this method does not
   * pretend otherwise.</strong> Unlike {@code derive}, it does not finish with {@code
   * require(child)}, because it could not succeed: {@code requireOwn} proves an admission's
   * retained home and scope still agree by resolving the home's project name back to a scope, and
   * {@link IdentityRepository#project} refuses to resolve a contained scope from a project name. So
   * a contained admission is inert — every live-authority surface declines it — which is the
   * intended posture of this slice and is asserted in {@code ContainmentHopTest}. The ancestry edge
   * is retained regardless, because evidence of a hop is worth having and evidence is not
   * permission. Teaching {@code require} to walk the container chain to its container's live grants
   * is the work of the task that makes a contained scope resolvable; it must change the home/scope
   * agreement check and the ancestry walk together, since a hop changes both by design.
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
    require(parent);
    var contained =
        new Admission(
            UUID.randomUUID(),
            parent.executor(),
            parent.account(),
            child.id(),
            // The hop changes home. A contained admission fenced to its parent's project would be
            // fenced to the one project the child must not reach, so it carries its own.
            identities.containedHome(child),
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
    var enclosing = identities.scope(parent.scope());
    while (enclosing instanceof AuthorityScope.Contained contained) {
      if (++depth > bounds.maxDepth() || !seen.add(contained.id())) throw denied();
      enclosing = identities.scope(contained.container());
    }
    if (depth > bounds.maxDepth()) throw denied();
  }

  @Override
  public void require(Admission admission) {
    var visited = new HashSet<UUID>();
    for (int depth = 0; ; depth++) {
      if (depth >= 64 || !visited.add(admission.id())) throw denied();
      requireOwn(admission);
      for (var resource : records.targets(admission.id())) {
        var targets = resources.projects(resource);
        if (targets.size() != 1 || !admission.home().project().equals(targets.getFirst()))
          throw denied();
      }
      var parent = records.parent(admission.id());
      if (parent.isEmpty()) return;
      var ancestor = records.find(parent.get()).orElseThrow(PinnedOperationAuthority::denied);
      if (!ancestor.executor().equals(admission.executor())
          || !ancestor.account().equals(admission.account())
          || !ancestor.scope().equals(admission.scope())
          || !ancestor.home().equals(admission.home())
          || !ancestor.source().equals(admission.source())
          || !ancestor.support().containsAll(admission.support())
          || admission.globalRead() && !ancestor.globalRead()) throw denied();
      admission = ancestor;
    }
  }

  private void requireOwn(Admission admission) {
    if (admission.globalRead()
        && !knowledge.allows(admission.executor(), BuiltInIdentities.GLOBAL, AuthorityAction.READ))
      throw denied();
    var executor = identities.account(admission.executor());
    if (executor.kind() == IdentityRepository.AccountKind.BUILTIN
        || !executor.id().equals(admission.account())) throw denied();
    // One read resolves the scope AND proves the two retained columns still agree: the admission's
    // project name must still map to the admission's scope id. Resolving by scope id instead would
    // be a read of the same size that cannot see a disagreement -- and the role ceiling below is
    // keyed by project name either way, so a project that was renamed, or dropped and recreated
    // under the same name, would have its new members' roles checked against the old scope's
    // retained grants. A stale admission must die here instead.
    var scope = identities.project(admission.home().project());
    if (!scope.id().equals(admission.scope())) throw denied();
    var source =
        members
            .authorityAccount(admission.executor())
            .orElseThrow(PinnedOperationAuthority::denied);
    if (!GrantRecipient.account(identities.account(source).id()).equals(admission.source()))
      throw denied();
    var role =
        members
            .role(admission.home().project(), admission.executor())
            .orElseThrow(PinnedOperationAuthority::denied);
    for (var item : admission.support()) {
      if (!ceiling(role, item.action())
          || !authority.allowsUsing(admission.source(), scope, item.action(), item.grant()))
        throw denied();
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
