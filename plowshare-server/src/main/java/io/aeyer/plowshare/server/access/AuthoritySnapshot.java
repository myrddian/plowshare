package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.ScopedGrantRepository.Binding;
import io.aeyer.plowshare.server.access.ScopedGrantRepository.Coverage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Immutable typed copy of the installation's authority tables, with Java evaluations of the SQL
 * authority functions over it. The server decides project roles, grant liveness and account
 * activity from these facts instead of re-running recursive SQL for every permission check.
 *
 * <p>Each method mirrors one SQL function clause by clause (the migration that last defined it is
 * named on the method). {@code AuthoritySnapshotDifferentialDatabaseTest} compares every method
 * with its function on generated fixtures; a migration that changes one of these functions must
 * change the matching method in the same change.
 *
 * <p>Time-dependent predicates use {@link #at()}, the {@code now()} of the statement that loaded
 * the facts. A snapshot is only served under a {@code ProjectionVersions} token whose window
 * contains no grant or token expiry, so the verdict at {@code at} equals the verdict at any instant
 * the snapshot is served. Revocation, enablement and membership changes move the AUTHORITY version
 * and invalidate the snapshot before it can answer again.
 */
public final class AuthoritySnapshot {
  /** One {@code admins} row. */
  public record Account(
      String handle, String kind, boolean enabled, boolean bootstrap, boolean serverAdmin) {
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
      List<RoleAction> roleActions) {
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
                  + roleActions.size())
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
  private final Map<UUID, Integer> live;
  private final boolean systemProcessing;

  public AuthoritySnapshot(Facts facts) {
    this.facts = Objects.requireNonNull(facts);
    facts.accounts().forEach(account -> accounts.put(account.handle(), account));
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
    systemProcessing = intrinsicProcessingGrant();
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
            .computeIfAbsent(row.role() + ":" + row.version(), key -> new java.util.HashSet<>())
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
    for (UUID root : roots) {
      var chain = new ArrayList<Binding>();
      var current = grants.get(root);
      for (int depth = 0; current != null && depth <= MAX_DEPTH; depth++) {
        chain.add(current.binding());
        if (depth == MAX_DEPTH) break;
        current = current.binding().authority().map(grants::get).orElse(null);
      }
      chains.add(List.copyOf(chain));
    }
    return List.copyOf(chains);
  }

  /** PostgreSQL compares UUIDs as unsigned bytes; Java's {@link UUID#compareTo} is signed. */
  static int postgresOrder(UUID left, UUID right) {
    int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
    return high != 0
        ? high
        : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
  }
}
