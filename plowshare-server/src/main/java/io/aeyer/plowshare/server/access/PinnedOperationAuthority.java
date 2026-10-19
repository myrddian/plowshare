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
  private final IdentityRepository identities;
  private final ScopedGrantRepository grants;
  private final ScopedAuthority authority;
  private final ProjectMembers members;
  private final OperationRecords records;
  private final ResourceScopeRepository resources;
  private final io.aeyer.plowshare.server.information.AccountKnowledgePermissions knowledge;

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

  @org.springframework.beans.factory.annotation.Autowired
  public PinnedOperationAuthority(
      IdentityRepository identities,
      ScopedGrantRepository grants,
      ScopedAuthority authority,
      ProjectMembers members,
      OperationRecords records,
      ResourceScopeRepository resources,
      io.aeyer.plowshare.server.information.AccountKnowledgePermissions knowledge) {
    this.knowledge = Objects.requireNonNull(knowledge);
    this.identities = Objects.requireNonNull(identities);
    this.grants = Objects.requireNonNull(grants);
    this.authority = Objects.requireNonNull(authority);
    this.members = Objects.requireNonNull(members);
    this.records = Objects.requireNonNull(records);
    this.resources = Objects.requireNonNull(resources);
  }

  @Override
  public Admission admit(String executor, Home home, Set<AuthorityAction> actions) {
    Objects.requireNonNull(home);
    actions = Set.copyOf(actions);
    if (home.isGlobal() || actions.isEmpty()) throw denied();
    var account = identities.account(executor);
    if (account.kind() == IdentityRepository.AccountKind.BUILTIN) throw denied();
    var target = identities.project(home.project());
    if (!(target instanceof AuthorityScope.Project || target instanceof AuthorityScope.Personal))
      throw denied();
    if (target instanceof AuthorityScope.Personal personal
        && !personal.account().equals(account.id())) throw denied();
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
    if (!Set.of(
            AuthorityAction.READ,
            AuthorityAction.WRITE,
            AuthorityAction.CONVERSATION_CREATE,
            AuthorityAction.RUN_MODEL)
        .containsAll(conversation.admission().actions())) throw denied();
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
        || !Set.of(
                AuthorityAction.READ,
                AuthorityAction.WRITE,
                AuthorityAction.CONVERSATION_CREATE,
                AuthorityAction.RUN_MODEL)
            .containsAll(actions)) throw denied();
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
