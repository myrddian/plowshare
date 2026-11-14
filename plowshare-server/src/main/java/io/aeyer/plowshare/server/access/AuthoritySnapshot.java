package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.ScopedGrantRepository.Binding;
import io.aeyer.plowshare.server.access.ScopedGrantRepository.Coverage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable typed copy of the installation's authority tables, with Java evaluations of the SQL
 * authority functions over it. The server decides project roles, grant liveness and account
 * activity from these facts instead of re-running recursive SQL for every permission check.
 *
 * <p>Each method mirrors one SQL function clause by clause (the migration that last defined it is
 * named on the method). {@code AuthoritySnapshotDifferentialDatabaseTest} compares every method
 * with its function on generated fixtures, and {@code KnowledgeReadabilityDifferentialDatabaseTest}
 * the knowledge-read methods ({@link #knowledgeReader} and the containment walks); a migration that
 * changes one of these functions must change the matching method in the same change.
 *
 * <p>Time-dependent predicates use {@link #at()}, the {@code now()} of the statement that loaded
 * the facts. A snapshot is only served under a {@code ProjectionVersions} token whose window
 * contains no grant or token expiry, so the verdict at {@code at} equals the verdict at any instant
 * the snapshot is served. Revocation, enablement and membership changes move the AUTHORITY version
 * and invalidate the snapshot before it can answer again.
 *
 * <p>Served snapshots are notification-trusted ({@code AuthoritySnapshotConfiguration}): a
 * committed change, including a grant revocation or a {@code session_version} bump, can take up to
 * {@code plowshare.projection-cache.notified.staleness} (2 s by default) to reach the answers given
 * from it. Callers that cannot accept that bound must not use the snapshot.
 */
public final class AuthoritySnapshot {
  /**
   * One {@code admins} row. {@code sessionVersion} is the socket session fence; every {@code
   * admins} update, including one that only bumps it, moves the AUTHORITY version (V175).
   */
  public record Account(
      String handle,
      String kind,
      boolean enabled,
      boolean bootstrap,
      boolean serverAdmin,
      long sessionVersion) {
    public Account {
      Objects.requireNonNull(handle);
      Objects.requireNonNull(kind);
    }
  }

  /** One {@code authority_roles} row. */
  public record Role(UUID id, String name, Optional<UUID> intrinsicAccount, boolean assignable) {
    public Role {
      Objects.requireNonNull(id);
      Objects.requireNonNull(name);
      Objects.requireNonNull(intrinsicAccount);
    }
  }

  /** One {@code authority_scopes} row. */
  public record Scope(
      UUID id,
      String kind,
      Optional<UUID> account,
      Optional<Long> project,
      Optional<String> builtin) {
    public Scope {
      Objects.requireNonNull(id);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(account);
      Objects.requireNonNull(project);
      Objects.requireNonNull(builtin);
    }
  }

  /** One {@code authority_role_actions} row. */
  public record RoleAction(UUID role, int version, String profile, String action) {
    public RoleAction {
      Objects.requireNonNull(role);
      Objects.requireNonNull(profile);
      Objects.requireNonNull(action);
    }
  }

  /** One {@code projects} row, reduced to identity and personal ownership. */
  public record Project(long id, String name, Optional<String> personalOwner) {
    public Project {
      Objects.requireNonNull(name);
      Objects.requireNonNull(personalOwner);
    }
  }

  /** One {@code service_tokens} row, without its digest. */
  public record ServiceToken(
      UUID id,
      String principal,
      String owner,
      boolean credentialIssued,
      Optional<OffsetDateTime> revokedAt,
      OffsetDateTime expiresAt) {
    public ServiceToken {
      Objects.requireNonNull(id);
      Objects.requireNonNull(principal);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(revokedAt);
      Objects.requireNonNull(expiresAt);
    }
  }

  /** One {@code service_token_scopes} row. */
  public record TokenScope(UUID token, long project, String role) {
    public TokenScope {
      Objects.requireNonNull(token);
      Objects.requireNonNull(role);
    }
  }

  /** One {@code project_members} row. */
  public record Member(long project, String handle, String role) {
    public Member {
      Objects.requireNonNull(handle);
      Objects.requireNonNull(role);
    }
  }

  /** One {@code authority_grant_records} row: the typed binding plus its role identity. */
  public record Grant(Binding binding, UUID role) {
    public Grant {
      Objects.requireNonNull(binding);
      Objects.requireNonNull(role);
    }
  }

  /**
   * The containment and identity columns the knowledge-read functions use beyond the rows above:
   * {@code authority_accounts.builtin} (as the set of builtin account ids), {@code
   * authority_scopes.container} (scope id to container, only non-null rows) and {@code
   * contained_principals} (principal id to its scope). Every table here moves the AUTHORITY version
   * (V175, and V257 for {@code contained_principals}).
   */
  public record Knowledge(
      Set<UUID> builtinAccounts, Map<UUID, UUID> containers, Map<UUID, UUID> principals) {
    public static final Knowledge NONE = new Knowledge(Set.of(), Map.of(), Map.of());

    public Knowledge {
      builtinAccounts = Set.copyOf(builtinAccounts);
      containers = Map.copyOf(containers);
      principals = Map.copyOf(principals);
    }
  }

  /** The table rows, exactly as loaded. */
  public record Facts(
      Instant at,
      List<Account> accounts,
      Map<UUID, String> authorityAccounts,
      List<Role> roles,
      List<Scope> scopes,
      List<Project> projects,
      List<ServiceToken> tokens,
      List<TokenScope> tokenScopes,
      List<Member> members,
      List<Grant> grants,
      List<RoleAction> roleActions,
      Knowledge knowledge) {
    /**
     * Facts without the knowledge-read columns: every knowledge-read decision then treats every
     * account as non-builtin, every scope as uncontained and no principal as contained.
     */
    public Facts(
        Instant at,
        List<Account> accounts,
        Map<UUID, String> authorityAccounts,
        List<Role> roles,
        List<Scope> scopes,
        List<Project> projects,
        List<ServiceToken> tokens,
        List<TokenScope> tokenScopes,
        List<Member> members,
        List<Grant> grants,
        List<RoleAction> roleActions) {
      this(
          at,
          accounts,
          authorityAccounts,
          roles,
          scopes,
          projects,
          tokens,
          tokenScopes,
          members,
          grants,
          roleActions,
          Knowledge.NONE);
    }

    /** Facts without role actions: every system-processing decision is then false. */
    public Facts(
        Instant at,
        List<Account> accounts,
        Map<UUID, String> authorityAccounts,
        List<Role> roles,
        List<Scope> scopes,
        List<Project> projects,
        List<ServiceToken> tokens,
        List<TokenScope> tokenScopes,
        List<Member> members,
        List<Grant> grants) {
      this(
          at,
          accounts,
          authorityAccounts,
          roles,
          scopes,
          projects,
          tokens,
          tokenScopes,
          members,
          grants,
          List.of());
    }

    public Facts {
      Objects.requireNonNull(at);
      accounts = List.copyOf(accounts);
      authorityAccounts = Map.copyOf(authorityAccounts);
      roles = List.copyOf(roles);
      scopes = List.copyOf(scopes);
      projects = List.copyOf(projects);
      tokens = List.copyOf(tokens);
      tokenScopes = List.copyOf(tokenScopes);
      members = List.copyOf(members);
      grants = List.copyOf(grants);
      roleActions = List.copyOf(roleActions);
      Objects.requireNonNull(knowledge);
    }

    /** Approximate retained bytes, for the cache's weight budget. */
    public long weight() {
      return 4096L
          + 256L
              * (accounts.size()
                  + authorityAccounts.size()
                  + roles.size()
                  + scopes.size()
                  + projects.size()
                  + tokens.size()
                  + tokenScopes.size()
                  + members.size()
                  + roleActions.size()
                  + knowledge.builtinAccounts().size()
                  + knowledge.containers().size()
                  + knowledge.principals().size())
          + 1024L * grants.size();
    }
  }

  /** Maximum grant chain depth, as in {@code authority_live_grants} ({@code l.depth<32}). */
  static final int MAX_DEPTH = 32;

  private final Facts facts;
  private final Map<String, Account> accounts = new HashMap<>();
  private final Map<UUID, Role> roles = new HashMap<>();
  private final Map<UUID, Scope> scopes = new HashMap<>();
  private final Map<Long, Project> projectsById = new HashMap<>();
  private final Map<String, Project> projectsByName = new HashMap<>();
  private final Map<String, ServiceToken> tokensByPrincipal = new HashMap<>();
  private final Map<UUID, ServiceToken> tokensById = new HashMap<>();
  private final Map<UUID, List<TokenScope>> tokenScopes = new HashMap<>();
  private final Map<Long, Map<String, Member>> members = new HashMap<>();
  private final Map<UUID, Grant> grants = new HashMap<>();
  private final Map<String, UUID> authorityAccountsByHandle = new HashMap<>();
  private final Map<Long, List<Scope>> scopesByProject = new HashMap<>();
  private final Map<UUID, Integer> live;
  private final Map<GrantRecipient, List<Binding>> liveByRecipient;
  private final boolean systemProcessing;
  private final boolean globalReadRole;

  public AuthoritySnapshot(Facts facts) {
    this.facts = Objects.requireNonNull(facts);
    facts.accounts().forEach(account -> accounts.put(account.handle(), account));
    // legacy_handle is UNIQUE (V155): at most one authority account per handle.
    facts.authorityAccounts().forEach((id, handle) -> authorityAccountsByHandle.put(handle, id));
    facts
        .scopes()
        .forEach(
            scope ->
                scope
                    .project()
                    .ifPresent(
                        project ->
                            scopesByProject
                                .computeIfAbsent(project, id -> new ArrayList<>())
                                .add(scope)));
    facts.roles().forEach(role -> roles.put(role.id(), role));
    facts.scopes().forEach(scope -> scopes.put(scope.id(), scope));
    for (var project : facts.projects()) {
      projectsById.put(project.id(), project);
      projectsByName.put(project.name(), project);
    }
    for (var token : facts.tokens()) {
      tokensByPrincipal.put(token.principal(), token);
      tokensById.put(token.id(), token);
    }
    facts
        .tokenScopes()
        .forEach(
            scope ->
                tokenScopes.computeIfAbsent(scope.token(), id -> new ArrayList<>()).add(scope));
    facts
        .members()
        .forEach(
            member ->
                members
                    .computeIfAbsent(member.project(), id -> new HashMap<>())
                    .put(member.handle(), member));
    facts.grants().forEach(grant -> grants.put(grant.binding().id(), grant));
    live = Collections.unmodifiableMap(liveGrants());
    liveByRecipient = liveByRecipient();
    systemProcessing = intrinsicProcessingGrant();
    globalReadRole = globalReadRoleAction();
  }

  /** The actions a SYSTEM processing grant's role must carry under the DEFAULT profile. */
  private static final Set<String> PROCESSING_ACTIONS =
      Set.of("READ", "WRITE", "CONVERSATION_CREATE", "RUN_MODEL");

  /**
   * {@code EXISTS(SELECT 1 FROM intrinsic)} of {@code information_system_processing_revisions}
   * (V236), the same grant test as {@code information_system_scope_valid} (V161): a live grant of a
   * non-assignable role to that role's own intrinsic account, covering all scopes with no agent,
   * whose role version carries all four processing actions under the DEFAULT profile.
   */
  public boolean systemProcessingAuthorized() {
    return systemProcessing;
  }

  /**
   * {@code information_system_scope_valid(project)} (V161): processing is authorized and the target
   * scope exists, the GLOBAL_KNOWLEDGE builtin for a null project, otherwise one bound to the
   * project of that name. An unknown project name has no scope.
   */
  public boolean systemScopeValid(String project) {
    if (!systemProcessing) return false;
    if (project == null)
      return facts.scopes().stream()
          .anyMatch(scope -> scope.builtin().equals(Optional.of("GLOBAL_KNOWLEDGE")));
    var named = projectsByName.get(project);
    if (named == null) return false;
    return facts.scopes().stream()
        .anyMatch(scope -> scope.project().equals(Optional.of(named.id())));
  }

  private boolean intrinsicProcessingGrant() {
    var actions = new HashMap<String, Set<String>>();
    for (var row : facts.roleActions())
      if (row.profile().equals("DEFAULT"))
        actions
            .computeIfAbsent(row.role() + ":" + row.version(), key -> new HashSet<>())
            .add(row.action());
    for (UUID id : live.keySet()) {
      var grant = grants.get(id);
      var binding = grant.binding();
      var role = roles.get(grant.role());
      if (role == null
          || role.assignable()
          || !role.intrinsicAccount().equals(Optional.of(binding.recipient().account().value()))
          || binding.coverage() != Coverage.ALL_SCOPES
          || binding.recipient().agent().isPresent()) continue;
      if (actions
          .getOrDefault(role.id() + ":" + binding.roleVersion(), Set.of())
          .containsAll(PROCESSING_ACTIONS)) return true;
    }
    return false;
  }

  public Facts facts() {
    return facts;
  }

  /** The {@code now()} of the loading statement; see the class contract. */
  public Instant at() {
    return facts.at();
  }

  /** {@code account_active(account)} (V138). */
  public boolean accountActive(String handle) {
    var account = accounts.get(handle);
    if (account == null || !account.enabled()) return false;
    if (!account.kind().equals("SERVICE_TOKEN")) return true;
    var token = tokensByPrincipal.get(handle);
    if (token == null
        || !token.credentialIssued()
        || token.revokedAt().isPresent()
        || !token.expiresAt().toInstant().isAfter(at())) return false;
    var owner = accounts.get(token.owner());
    return owner != null && owner.enabled() && owner.kind().equals("SERVICE");
  }

  /** {@code SELECT id, depth FROM authority_live_grants()} (V216), as an id to depth map. */
  public Map<UUID, Integer> liveGrants() {
    if (live != null) return live;
    var result = new LinkedHashMap<UUID, Integer>();
    var children = new HashMap<UUID, List<Grant>>();
    var frontier = new ArrayDeque<UUID>();
    for (var grant : facts.grants()) {
      grant
          .binding()
          .authority()
          .ifPresent(
              parent -> children.computeIfAbsent(parent, id -> new ArrayList<>()).add(grant));
      if (rootPolicy(grant)) {
        result.putIfAbsent(grant.binding().id(), 0);
        frontier.add(grant.binding().id());
      }
    }
    // Breadth first, so each grant records the shallowest depth the recursive CTE reaches it at.
    // The forest has one parent per grant; a visited grant is already live, so revisiting it (as
    // UNION ALL would, only in a cycle) cannot add a live grant.
    while (!frontier.isEmpty()) {
      UUID parent = frontier.poll();
      int depth = result.get(parent);
      if (depth >= MAX_DEPTH) continue;
      for (var child : children.getOrDefault(parent, List.of())) {
        if (result.containsKey(child.binding().id()) || !delegated(child)) continue;
        result.put(child.binding().id(), depth + 1);
        frontier.add(child.binding().id());
      }
    }
    return result;
  }

  /** The non-recursive term of {@code authority_live_grants} (V216). */
  private boolean rootPolicy(Grant grant) {
    var binding = grant.binding();
    var role = roles.get(grant.role());
    String handle = facts.authorityAccounts().get(binding.recipient().account().value());
    var account = handle == null ? null : accounts.get(handle);
    var scope = scopes.get(binding.scope().value());
    return role != null
        && account != null
        && scope != null
        && binding.provenance().equals("ROOT_POLICY")
        && binding.revokedAt().isEmpty()
        && scope.kind().equals("ADMINISTRATION")
        && role.intrinsicAccount().isPresent()
        && scope.account().equals(role.intrinsicAccount())
        && role.intrinsicAccount().get().equals(binding.recipient().account().value())
        && !role.assignable()
        && account.kind().equals("BUILTIN")
        && !account.enabled()
        && binding.coverage() == Coverage.ALL_SCOPES;
  }

  /** The recursive term's per-grant conditions in {@code authority_live_grants} (V216). */
  private boolean delegated(Grant grant) {
    var binding = grant.binding();
    String handle = facts.authorityAccounts().get(binding.recipient().account().value());
    var account = handle == null ? null : accounts.get(handle);
    var scope = scopes.get(binding.scope().value());
    if (account == null || scope == null) return false;
    if (binding.revokedAt().isPresent()) return false;
    if (binding.expiresAt().isPresent() && !binding.expiresAt().get().toInstant().isAfter(at()))
      return false;
    if (scope.kind().equals("RESTRICTED") || scope.kind().equals("CONTAINED")) return false;
    if (!accountActive(account.handle()) || account.bootstrap()) return false;
    if (scope.kind().equals("PERSONAL")
        && !scope.account().equals(Optional.of(binding.recipient().account().value())))
      return false;
    if (binding.issuingToken().isPresent() && !tokenIssued(binding.issuingToken().get(), scope))
      return false;
    return !(binding.provenance().equals("ADMIN_CONTROL")
            || binding.provenance().equals("LEGACY_ADMIN_DATA"))
        || (account.kind().equals("USER") && account.serverAdmin());
  }

  /** The {@code issuing_token} EXISTS clause of {@code authority_live_grants} (V216). */
  private boolean tokenIssued(UUID id, Scope scope) {
    var token = tokensById.get(id);
    if (token == null || scope.project().isEmpty()) return false;
    long project = scope.project().get();
    return projectsById.containsKey(project)
        && tokenScopes.getOrDefault(id, List.of()).stream()
            .anyMatch(granted -> granted.project() == project && granted.role().equals("MANAGER"))
        && accountActive(token.principal());
  }

  /** {@code authority_project_role(project, account)} (V156): MANAGER, CONTRIBUTOR or VIEWER. */
  public Optional<String> authorityProjectRole(String project, String account) {
    if (project == null || account == null) return Optional.empty();
    var target = projectsByName.get(project);
    if (target == null) return Optional.empty();
    boolean manager = false, contributor = false, reader = false;
    for (UUID id : live.keySet()) {
      var grant = grants.get(id);
      var binding = grant.binding();
      UUID subject = binding.recipient().account().value();
      if (!account.equals(facts.authorityAccounts().get(subject))) continue;
      var scope = scopes.get(binding.scope().value());
      if (scope == null || !scope.project().equals(Optional.of(target.id()))) continue;
      if (binding.recipient().agent().isPresent() || binding.coverage() != Coverage.SELF) continue;
      if (!(scope.kind().equals("PROJECT")
          || (scope.kind().equals("PERSONAL") && scope.account().equals(Optional.of(subject)))))
        continue;
      var role = roles.get(grant.role());
      if (role == null) continue;
      switch (role.name()) {
        case "MANAGER" -> manager = true;
        case "CONTRIBUTOR" -> contributor = true;
        case "READER" -> reader = true;
        default -> {}
      }
    }
    if (manager) return Optional.of("MANAGER");
    if (contributor) return Optional.of("CONTRIBUTOR");
    if (reader) return Optional.of("VIEWER");
    return Optional.empty();
  }

  /** {@code service_project_role(project, principal)} (V156): the token ceiling of the owner. */
  public Optional<String> serviceProjectRole(String project, String principal) {
    if (project == null || principal == null) return Optional.empty();
    var token = tokensByPrincipal.get(principal);
    var target = projectsByName.get(project);
    if (token == null
        || target == null
        || target.personalOwner().isPresent()
        || project.startsWith("client:")
        || project.startsWith("personal:")
        || !accountActive(principal)) return Optional.empty();
    // principal_handle is UNIQUE and token scopes are keyed by (token, project): at most one row.
    var ceiling =
        tokenScopes.getOrDefault(token.id(), List.of()).stream()
            .filter(scope -> scope.project() == target.id())
            .findFirst();
    if (ceiling.isEmpty()) return Optional.empty();
    var effective = authorityProjectRole(project, token.owner());
    if (effective.isEmpty()) return Optional.empty();
    String limit = ceiling.get().role();
    if (effective.get().equals("VIEWER") || limit.equals("VIEWER")) return Optional.of("VIEWER");
    if (effective.get().equals("CONTRIBUTOR") || limit.equals("CONTRIBUTOR"))
      return Optional.of("CONTRIBUTOR");
    return Optional.of("MANAGER");
  }

  /** The owner whose authority caps a service principal, if the principal is active. */
  public Optional<String> serviceOwner(String principal) {
    var token = tokensByPrincipal.get(principal);
    return token != null && accountActive(principal)
        ? Optional.of(token.owner())
        : Optional.empty();
  }

  /** {@code admins.enabled AND server_admin AND NOT bootstrap}. */
  public boolean serverAdmin(String handle) {
    var account = accounts.get(handle);
    return account != null && account.enabled() && account.serverAdmin() && !account.bootstrap();
  }

  /** The raw {@code project_members} role of an enabled account, if any. */
  public Optional<String> memberRole(String project, String handle) {
    var target = projectsByName.get(project);
    var account = accounts.get(handle);
    if (target == null || account == null || !account.enabled()) return Optional.empty();
    return Optional.ofNullable(members.getOrDefault(target.id(), Map.of()).get(handle))
        .map(Member::role);
  }

  /**
   * {@code ScopedGrantRepository.projectLineages}: one chain per live SELF project grant of the
   * account, each from the grant up its issuing ancestry (at most {@value #MAX_DEPTH} links), in
   * PostgreSQL's order of root identifiers.
   */
  public List<List<Binding>> projectLineages(String project, String account) {
    var roots =
        live.keySet().stream()
            .map(grants::get)
            .filter(
                grant -> {
                  var binding = grant.binding();
                  return binding.project().equals(Optional.of(project))
                      && binding.recipientHandle().equals(account)
                      && binding.recipient().agent().isEmpty()
                      && binding.coverage() == Coverage.SELF;
                })
            .map(grant -> grant.binding().id())
            .sorted(AuthoritySnapshot::postgresOrder)
            .toList();
    var chains = new ArrayList<List<Binding>>();
    for (UUID root : roots) chains.add(chain(root));
    return List.copyOf(chains);
  }

  /**
   * {@code ScopedGrantRepository.live(recipient)}: the live {@code authority_grant_records} rows
   * whose subject account and agent equal the recipient's ({@code subject_agent IS NOT DISTINCT
   * FROM}), ordered by {@code created_at, id} as PostgreSQL orders them.
   */
  public List<Binding> live(GrantRecipient recipient) {
    Objects.requireNonNull(recipient);
    return liveByRecipient.getOrDefault(recipient, List.of());
  }

  /**
   * {@code ScopedGrantRepository.lineage(id)}: empty unless the grant is live; otherwise the grant
   * and its issuing ancestry, child to parent, at most {@value #MAX_DEPTH} links above it.
   * Ancestors are followed whether or not they are live themselves, exactly as the recursive CTE
   * joins plain {@code authority_grants}.
   */
  public List<Binding> lineage(UUID id) {
    Objects.requireNonNull(id);
    return live.containsKey(id) ? chain(id) : List.of();
  }

  /**
   * {@code SELECT session_version FROM admins WHERE handle=? AND account_active(handle)}: the
   * session fence of an active account, empty for an inactive or unknown one. {@code
   * AdminStore.sessionCurrent} and {@code currentSessions} accept a socket session exactly when
   * this equals its version.
   */
  public Optional<Long> activeSessionVersion(String handle) {
    if (handle == null || !accountActive(handle)) return Optional.empty();
    return Optional.of(accounts.get(handle).sessionVersion());
  }

  /**
   * The recursive {@code chain} term shared by {@code lineage} and {@code projectLineages}: the
   * grant, then each {@code issuing_grant} while {@code depth<32}, so at most {@value #MAX_DEPTH} +
   * 1 rows. Every {@code authority_grants} row has its {@code authority_grant_records} row (the
   * view inner-joins only foreign keys), so following the loaded rows is following the table.
   */
  private List<Binding> chain(UUID root) {
    var chain = new ArrayList<Binding>();
    var current = grants.get(root);
    for (int depth = 0; current != null && depth <= MAX_DEPTH; depth++) {
      chain.add(current.binding());
      if (depth == MAX_DEPTH) break;
      current = current.binding().authority().map(grants::get).orElse(null);
    }
    return List.copyOf(chain);
  }

  /** The live bindings per recipient, each list in {@code ORDER BY created_at, id}. */
  private Map<GrantRecipient, List<Binding>> liveByRecipient() {
    var byRecipient = new HashMap<GrantRecipient, List<Binding>>();
    for (UUID id : live.keySet()) {
      var binding = grants.get(id).binding();
      byRecipient.computeIfAbsent(binding.recipient(), key -> new ArrayList<>()).add(binding);
    }
    var order =
        Comparator.comparing((Binding binding) -> binding.createdAt().toInstant())
            .thenComparing(Binding::id, AuthoritySnapshot::postgresOrder);
    var result = new HashMap<GrantRecipient, List<Binding>>();
    byRecipient.forEach(
        (recipient, bindings) -> {
          bindings.sort(order);
          result.put(recipient, List.copyOf(bindings));
        });
    return Map.copyOf(result);
  }

  /**
   * The reader half of a knowledge read: {@code knowledge_read_authority(actor)} (V229) as one
   * value, plus {@code contained_knowledge_actor(actor)}, which {@code
   * knowledge_readable_candidates} evaluates beside it. {@code actorId} is the {@code
   * authority_accounts} id of the handle, {@code generalMember} the {@code general_member} column
   * (false for a contained actor) and {@code readableScopes} the non-null {@code scope_id} rows.
   */
  public record KnowledgeReader(
      Optional<UUID> actorId, boolean generalMember, boolean contained, Set<UUID> readableScopes) {
    public KnowledgeReader {
      Objects.requireNonNull(actorId);
      readableScopes = Set.copyOf(readableScopes);
    }
  }

  // contained_knowledge_actor's guard: ~* '^...$' over the whole text, case-insensitive.
  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);

  /**
   * {@code contained_knowledge_actor(actor)} (V229): the text parses as a UUID (tested before the
   * cast, so a handle such as {@code alice} is simply false) and names a live {@code
   * contained_principals} row.
   */
  public boolean containedKnowledgeActor(String actor) {
    if (actor == null || !UUID_TEXT.matcher(actor).matches()) return false;
    return facts.knowledge().principals().containsKey(UUID.fromString(actor));
  }

  /**
   * {@code information_global_read_member(account)} (V158): an active, non-bootstrap {@code admins}
   * row with a non-builtin {@code authority_accounts} row, on an installation whose GLOBAL_READ
   * role carries READ at version 1 under the DEFAULT profile. The role test is a cross join, not a
   * grant: it does not depend on the account.
   */
  public boolean globalReadMember(String actor) {
    if (actor == null || !accountActive(actor)) return false;
    var account = accounts.get(actor);
    UUID authority = authorityAccountsByHandle.get(actor);
    return account != null
        && !account.bootstrap()
        && authority != null
        && !facts.knowledge().builtinAccounts().contains(authority)
        && globalReadRole;
  }

  private boolean globalReadRoleAction() {
    for (var action : facts.roleActions()) {
      var role = roles.get(action.role());
      if (role != null
          && role.name().equals("GLOBAL_READ")
          && action.action().equals("READ")
          && action.version() == 1
          && action.profile().equals("DEFAULT")) return true;
    }
    return false;
  }

  /**
   * {@code knowledge_read_authority(actor)} (V229) with {@code contained_knowledge_actor(actor)}.
   * Mirrors, clause by clause: {@code actor_fact} (member is pinned false for a contained actor),
   * {@code project_reads} (live SELF grants of READ-capable roles on a PROJECT scope or the
   * subject's own PERSONAL scope), and the four {@code readable_scopes} arms: own account
   * knowledge, the GLOBAL_KNOWLEDGE builtin for a USER holding a live ADMIN grant with READ, a
   * PROJECT or PERSONAL scope read directly or through a service token's owner (not for client,
   * personal or personally owned projects), and a CONTAINED scope admitted by {@link
   * #containedScopeReadable}.
   */
  public KnowledgeReader knowledgeReader(String actor) {
    UUID actorId = actor == null ? null : authorityAccountsByHandle.get(actor);
    var account = actor == null ? null : accounts.get(actor);
    boolean contained = containedKnowledgeActor(actor);
    boolean member = globalReadMember(actor) && !contained;
    var projectReads = projectReads();
    var readable = new HashSet<UUID>();
    for (var scope : facts.scopes()) {
      boolean memberArm = member && memberReads(actor, actorId, account, scope, projectReads);
      // The kind test is redundant with containedScopeReadable's own and kept as in SQL.
      boolean containedArm =
          scope.kind().equals("CONTAINED") && containedScopeReadable(actor, scope.id(), member);
      if (memberArm || containedArm) readable.add(scope.id());
    }
    return new KnowledgeReader(Optional.ofNullable(actorId), member, contained, readable);
  }

  /** The {@code a.member AND (...)} arms of {@code readable_scopes} (V229). */
  private boolean memberReads(
      String actor, UUID actorId, Account account, Scope scope, Map<UUID, Set<UUID>> projectReads) {
    if (scope.kind().equals("ACCOUNT_KNOWLEDGE")
        && scope.builtin().isEmpty()
        && actorId != null
        && scope.account().equals(Optional.of(actorId))) return true;
    if (scope.builtin().equals(Optional.of("GLOBAL_KNOWLEDGE"))
        && account != null
        && account.kind().equals("USER")
        && actorId != null
        && globalKnowledgeRead(actorId, scope)) return true;
    if (!scope.kind().equals("PROJECT") && !scope.kind().equals("PERSONAL")) return false;
    if (actorId != null && projectReads.getOrDefault(actorId, Set.of()).contains(scope.id()))
      return true;
    return tokenOwnerReads(actor, scope, projectReads);
  }

  /**
   * The GLOBAL_KNOWLEDGE arm's EXISTS (V229): a live SELF grant of the ADMIN role to the account
   * itself (no agent) on this scope, whose role version carries READ under the DEFAULT profile or
   * the scope's own builtin profile.
   */
  private boolean globalKnowledgeRead(UUID actorId, Scope scope) {
    for (UUID id : live.keySet()) {
      var grant = grants.get(id);
      var binding = grant.binding();
      var role = roles.get(grant.role());
      if (role == null
          || !role.name().equals("ADMIN")
          || !binding.recipient().account().value().equals(actorId)
          || binding.recipient().agent().isPresent()
          || !binding.scope().value().equals(scope.id())
          || binding.coverage() != Coverage.SELF) continue;
      for (var action : facts.roleActions())
        if (action.role().equals(role.id())
            && action.version() == binding.roleVersion()
            && action.action().equals("READ")
            && (action.profile().equals("DEFAULT")
                || scope.builtin().equals(Optional.of(action.profile())))) return true;
    }
    return false;
  }

  /**
   * {@code project_reads} (V229), as subject account to scopes: exactly {@code
   * authority_project_role}'s READ-capable roles, SELF coverage, no agent, on a PROJECT scope or
   * the subject's own PERSONAL scope, for a subject with an {@code authority_accounts} row.
   */
  private Map<UUID, Set<UUID>> projectReads() {
    var reads = new HashMap<UUID, Set<UUID>>();
    for (UUID id : live.keySet()) {
      var grant = grants.get(id);
      var binding = grant.binding();
      var role = roles.get(grant.role());
      var scope = scopes.get(binding.scope().value());
      UUID subject = binding.recipient().account().value();
      if (role == null
          || scope == null
          || !facts.authorityAccounts().containsKey(subject)
          || binding.recipient().agent().isPresent()
          || binding.coverage() != Coverage.SELF
          || !Set.of("READER", "CONTRIBUTOR", "MANAGER").contains(role.name())) continue;
      if (scope.kind().equals("PROJECT")
          || (scope.kind().equals("PERSONAL") && scope.account().equals(Optional.of(subject))))
        reads.computeIfAbsent(subject, key -> new HashSet<>()).add(scope.id());
    }
    return reads;
  }

  /**
   * The service-token arm of {@code readable_scopes} (V229): the actor is a token principal whose
   * token is scoped to this scope's project and whose owner reads the scope, on a project that is
   * neither personally owned nor named {@code client:} or {@code personal:}. Token liveness is not
   * re-tested here, exactly as in SQL: general membership already requires an active account.
   */
  private boolean tokenOwnerReads(String actor, Scope scope, Map<UUID, Set<UUID>> projectReads) {
    var token = actor == null ? null : tokensByPrincipal.get(actor);
    if (token == null || scope.project().isEmpty()) return false;
    long project = scope.project().get();
    var named = projectsById.get(project);
    if (named == null
        || named.personalOwner().isPresent()
        || named.name().startsWith("client:")
        || named.name().startsWith("personal:")) return false;
    UUID owner = authorityAccountsByHandle.get(token.owner());
    if (owner == null || !projectReads.getOrDefault(owner, Set.of()).contains(scope.id()))
      return false;
    return tokenScopes.getOrDefault(token.id(), List.of()).stream()
        .anyMatch(granted -> granted.project() == project);
  }

  /**
   * {@code contained_scope_readable(actor, scope, general_member)} (V223): a CONTAINED scope read
   * by its own principal (a handle with no {@code admins} row whose canonical lower-case UUID text
   * equals the principal id, compared as text), or by a general member holding a project role on a
   * PROJECT or PERSONAL ancestor.
   */
  public boolean containedScopeReadable(String actor, UUID scope, boolean generalMember) {
    var target = scopes.get(scope);
    if (target == null || !target.kind().equals("CONTAINED")) return false;
    if (actor != null && !accounts.containsKey(actor)) {
      for (var principal : facts.knowledge().principals().entrySet())
        if (principal.getValue().equals(scope) && principal.getKey().toString().equals(actor))
          return true;
    }
    if (!generalMember) return false;
    for (UUID id : containedScopeAncestors(scope)) {
      var ancestor = scopes.get(id);
      if (ancestor == null
          || !(ancestor.kind().equals("PROJECT") || ancestor.kind().equals("PERSONAL"))
          || ancestor.project().isEmpty()) continue;
      var project = projectsById.get(ancestor.project().get());
      if (project != null && authorityProjectRole(project.name(), actor).isPresent()) return true;
    }
    return false;
  }

  /**
   * {@code contained_scope_ancestors(descendant)} (V223): the container chain upwards, nearest
   * first, at most 64 links. Each link follows an existing scope's non-null container.
   */
  public List<UUID> containedScopeAncestors(UUID descendant) {
    var chain = new ArrayList<UUID>();
    var containers = facts.knowledge().containers();
    UUID current = scopes.containsKey(descendant) ? containers.get(descendant) : null;
    for (int distance = 1; current != null; distance++) {
      chain.add(current);
      if (distance >= 64 || !scopes.containsKey(current)) break;
      current = containers.get(current);
    }
    return chain;
  }

  /**
   * {@code contained_scope_in_home(scope, home_project)} (V223): a CONTAINED scope with an ancestor
   * bound to the project named {@code home}. A null home matches nothing.
   */
  public boolean containedScopeInHome(UUID scope, String home) {
    var target = scopes.get(scope);
    if (home == null || target == null || !target.kind().equals("CONTAINED")) return false;
    for (UUID id : containedScopeAncestors(scope)) {
      var ancestor = scopes.get(id);
      if (ancestor == null || ancestor.project().isEmpty()) continue;
      var project = projectsById.get(ancestor.project().get());
      if (project != null && project.name().equals(home)) return true;
    }
    return false;
  }

  /** One {@code authority_scopes} row. */
  public Optional<Scope> scope(UUID id) {
    return Optional.ofNullable(scopes.get(id));
  }

  /** One {@code projects} row by id. */
  public Optional<Project> project(long id) {
    return Optional.ofNullable(projectsById.get(id));
  }

  /** The {@code authority_scopes} rows bound to one project. */
  public List<Scope> projectScopes(long project) {
    return List.copyOf(scopesByProject.getOrDefault(project, List.of()));
  }

  /**
   * {@code EXISTS(SELECT 1 FROM projects personal WHERE personal.name=name AND
   * personal.personal_owner=owner)}, as the personal-project arms of V229 write it.
   */
  public boolean personalProject(String name, String owner) {
    if (name == null || owner == null) return false;
    var project = projectsByName.get(name);
    return project != null && project.personalOwner().equals(Optional.of(owner));
  }

  /** PostgreSQL compares UUIDs as unsigned bytes; Java's {@link UUID#compareTo} is signed. */
  static int postgresOrder(UUID left, UUID right) {
    int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
    return high != 0
        ? high
        : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
  }
}
