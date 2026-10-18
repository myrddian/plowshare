package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.access.IdentityRepository.AccountKind;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Specialist store for atomic role issuance, retained lineage and optimistic revocation. */
@Repository
public final class JdbcScopedGrantRepository implements ScopedGrantRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcScopedGrantRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
    transactions =
        new TransactionTemplate(
            new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
  }

  @Override
  public boolean globalReadEligible(AccountId account) {
    Objects.requireNonNull(account);
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM authority_accounts a WHERE a.id=? AND information_global_read_member(a.legacy_handle))",
            Boolean.class,
            account.value()));
  }

  @Override
  public List<Binding> live(GrantRecipient recipient) {
    Objects.requireNonNull(recipient);
    return List.copyOf(
        jdbc.query(
            "SELECT g.* FROM authority_grant_records g "
                + "JOIN authority_live_grants() l ON l.id=g.id WHERE g.subject_account=? "
                + "AND g.subject_agent IS NOT DISTINCT FROM ? ORDER BY g.created_at,g.id",
            (row, n) -> read(row),
            recipient.account().value(),
            recipient.agent().orElse(null)));
  }

  @Override
  public List<Binding> projectBindings(String project, String account) {
    io.aeyer.plowshare.protocol.Home.of(project);
    if (account == null || account.isBlank())
      throw new IllegalArgumentException("Account required");
    return List.copyOf(
        jdbc.query(
            "SELECT g.* FROM authority_grant_records g JOIN authority_live_grants() l ON l.id=g.id "
                + "WHERE g.project_name=? AND g.subject_handle=? AND g.subject_agent IS NULL AND g.coverage='SELF'",
            (row, n) -> read(row),
            project,
            account));
  }

  @Override
  public List<Binding> lineage(UUID id) {
    Objects.requireNonNull(id);
    return List.copyOf(
        jdbc.query(
            "WITH RECURSIVE chain(id,issuing_grant,depth) AS ("
                + "SELECT g.id,g.issuing_grant,0 FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id WHERE g.id=? "
                + "UNION ALL SELECT p.id,p.issuing_grant,c.depth+1 FROM authority_grants p JOIN chain c ON c.issuing_grant=p.id WHERE c.depth<32) "
                + "SELECT g.* FROM chain c JOIN authority_grant_records g ON g.id=c.id ORDER BY c.depth",
            (row, n) -> read(row),
            id));
  }

  @Override
  public Optional<Binding> find(UUID id) {
    Objects.requireNonNull(id);
    return jdbc
        .query("SELECT * FROM authority_grant_records WHERE id=?", (row, n) -> read(row), id)
        .stream()
        .findFirst();
  }

  @Override
  public Binding issue(Issue request) {
    Objects.requireNonNull(request);
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              lock(request.scope());
              Binding parent = issuing(request.issuer(), request.authority());
              StandardRole effective =
                  RoleGrantPolicy.effective(
                      request.requested(),
                      request.recipient().agent().isPresent(),
                      request.forceManager(),
                      kind(request.issuer().account()),
                      request.issuer().agent().isPresent(),
                      parent.role());
              if (!active(request.recipient().account()))
                throw new CallerFault("Grant recipient is not active");
              return insert(request, effective, "ROLE");
            }));
  }

  private AccountKind kind(AccountId account) {
    var kinds =
        jdbc.queryForList(
            "SELECT u.account_kind FROM authority_accounts a "
                + "JOIN admins u ON u.handle=a.legacy_handle WHERE a.id=?",
            String.class,
            account.value());
    if (kinds.size() != 1) throw new CallerFault("Grant account is unavailable");
    return AccountKind.valueOf(kinds.getFirst());
  }

  private boolean active(AccountId account) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM authority_accounts a "
                + "WHERE a.id=? AND account_active(a.legacy_handle))",
            Boolean.class,
            account.value()));
  }

  private Binding insert(Issue request, StandardRole effective, String provenance) {
    UUID id = request.id();
    int changed =
        jdbc.update(
            "INSERT INTO authority_grants(id,subject_account,subject_agent,scope_id,role_id,requested_role,"
                + "issuer_account,issuer_agent,issuing_grant,expires_at,manager_forced,provenance) "
                + "SELECT ?,?,?,?,r.id,?,?,?,?,?,?,? FROM authority_roles r WHERE r.name=? AND r.assignable",
            id,
            request.recipient().account().value(),
            request.recipient().agent().orElse(null),
            request.scope().value(),
            request.requested().name(),
            request.issuer().account().value(),
            request.issuer().agent().orElse(null),
            request.authority(),
            request.expiresAt().orElse(null),
            request.forceManager(),
            provenance,
            effective.name());
    if (changed != 1) throw new CallerFault("Requested role is unavailable");
    return find(id).orElseThrow(() -> new IllegalStateException("Issued grant disappeared"));
  }

  private Binding issuing(GrantRecipient issuer, UUID authority) {
    return live(issuer).stream()
        .filter(g -> g.id().equals(authority))
        .findFirst()
        .orElseThrow(() -> new CallerFault("Issuing authority is no longer valid"));
  }

  @Override
  public Binding revoke(UUID id, long version, GrantRecipient issuer, UUID authority) {
    Objects.requireNonNull(id);
    Objects.requireNonNull(issuer);
    Objects.requireNonNull(authority);
    if (version < 1) throw new IllegalArgumentException("Grant version is required");
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              Binding existing =
                  find(id).orElseThrow(() -> new CallerFault("No grant has that identity"));
              lock(existing.scope());
              jdbc.queryForObject("SELECT authority_lock_lineage(?)", Object.class, authority);
              Binding parent = issuing(issuer, authority);
              requireManagement(parent, existing.scope());
              if (existing.role() == StandardRole.SYSTEM)
                throw new CallerFault("Root policy cannot be revoked");
              if (existing.role() == StandardRole.ADMIN
                  && (issuer.agent().isPresent()
                      || parent.role() != StandardRole.ADMIN
                          && parent.role() != StandardRole.SYSTEM))
                throw new CallerFault("Issuing grant cannot control ADMIN authority");
              boolean hadManager =
                  jdbc.queryForObject(
                          "SELECT authority_project_manager_count(?)",
                          Long.class,
                          existing.scope().value())
                      > 0;
              if (jdbc.update(
                      "UPDATE authority_grants SET revoked_at=now(),version=version+1,revoked_by=?,"
                          + "revoked_by_agent=?,revoking_grant=? WHERE id=? AND version=? AND revoked_at IS NULL",
                      issuer.account().value(),
                      issuer.agent().orElse(null),
                      authority,
                      id,
                      version)
                  != 1)
                throw new CallerFault("Grant changed; read its current version before retrying");
              // Remove the compatibility row with its binding. A legacy consumer must not
              // recreate access or bootstrap a replacement for revoked authority.
              if (existing.provenance().equals("MEMBERSHIP")) {
                jdbc.update(
                    "DELETE FROM project_members WHERE project_id=(SELECT project_id FROM authority_scopes WHERE id=?) "
                        + "AND handle=(SELECT legacy_handle FROM authority_accounts WHERE id=?)",
                    existing.scope().value(),
                    existing.recipient().account().value());
              }
              if (hadManager) protectManager(existing.scope(), parent);
              return find(id).orElseThrow();
            }));
  }

  private void requireManagement(Binding issuing, ScopeId target) {
    String kind =
        jdbc.queryForObject(
            "SELECT kind FROM authority_scopes WHERE id=?", String.class, target.value());
    boolean covers =
        issuing.scope().equals(target)
            || issuing.coverage() == Coverage.ALL_SCOPES
            || issuing.coverage() == Coverage.ORDINARY_PROJECT_CONTROL && "PROJECT".equals(kind);
    if (!covers || !issuing.actions().contains(AuthorityAction.MANAGE_ACCESS))
      throw new CallerFault("Issuing grant cannot manage that scope");
  }

  private void protectManager(ScopeId scope, Binding issuing) {
    if (issuing.role() == StandardRole.ADMIN || issuing.role() == StandardRole.SYSTEM) return;
    Long managers =
        jdbc.queryForObject("SELECT authority_project_manager_count(?)", Long.class, scope.value());
    if (managers == null || managers < 1)
      throw new CallerFault("Keep an enabled project manager before removing the last manager");
  }

  /**
   * Locks the legacy project before its scope, matching membership mutation order. All source
   * mutations in that project share this lock; no remote work occurs in these transactions.
   */
  private void lock(ScopeId scope) {
    var projects =
        jdbc.queryForList(
            "SELECT project_id FROM authority_scopes WHERE id=? AND project_id IS NOT NULL",
            Long.class,
            scope.value());
    if (!projects.isEmpty())
      jdbc.queryForObject(
          "SELECT id FROM projects WHERE id=? FOR UPDATE", Long.class, projects.getFirst());
    var scopes =
        jdbc.queryForList(
            "SELECT id FROM authority_scopes WHERE id=? AND kind<>'RESTRICTED' FOR UPDATE",
            UUID.class,
            scope.value());
    if (scopes.size() != 1) throw new CallerFault("Scope is unavailable or restricted");
  }

  private static Binding read(ResultSet row) throws SQLException {
    java.sql.Array array = row.getArray("actions");
    Set<AuthorityAction> actions = new HashSet<>();
    try {
      for (Object action : (Object[]) array.getArray())
        actions.add(AuthorityAction.valueOf(action.toString()));
    } finally {
      array.free();
    }
    return new Binding(
        row.getObject("id", UUID.class),
        new GrantRecipient(
            new AccountId(row.getObject("subject_account", UUID.class)),
            Optional.ofNullable(row.getString("subject_agent"))),
        new ScopeId(row.getObject("scope_id", UUID.class)),
        StandardRole.valueOf(row.getString("effective_role")),
        StandardRole.valueOf(row.getString("requested_role")),
        Coverage.valueOf(row.getString("coverage")),
        new GrantRecipient(
            new AccountId(row.getObject("issuer_account", UUID.class)),
            Optional.ofNullable(row.getString("issuer_agent"))),
        Optional.ofNullable(row.getObject("issuing_grant", UUID.class)),
        Optional.ofNullable(row.getObject("issuing_token", UUID.class)),
        row.getLong("version"),
        row.getInt("role_version"),
        row.getBoolean("manager_forced"),
        row.getString("provenance"),
        row.getObject("created_at", OffsetDateTime.class),
        Optional.ofNullable(row.getObject("expires_at", OffsetDateTime.class)),
        Optional.ofNullable(row.getObject("revoked_at", OffsetDateTime.class)),
        actions,
        row.getString("subject_handle"),
        Optional.ofNullable(row.getString("project_name")));
  }
}
