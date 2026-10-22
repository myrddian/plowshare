package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns additive UUID mappings; unresolved/restricted records never become alternate scopes. */
@Repository
public class JdbcIdentityRepository implements IdentityRepository {
  private static final String SCOPES =
      "SELECT s.*,a.account_kind AS owner_kind FROM authority_scopes s "
          + "LEFT JOIN authority_accounts i ON i.id=s.account_id LEFT JOIN admins a ON a.handle=i.legacy_handle ";
  private final JdbcTemplate jdbc;

  public JdbcIdentityRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public Account account(String handle) {
    if (handle == null || handle.isBlank()) throw absent();
    return one(
        jdbc.query(
            "SELECT i.id,i.legacy_handle,a.account_kind,i.builtin IS NOT NULL AS locked "
                + "FROM authority_accounts i JOIN admins a ON a.handle=i.legacy_handle WHERE i.legacy_handle=?",
            (row, n) ->
                new Account(
                    new AccountId(row.getObject("id", UUID.class)),
                    row.getString("legacy_handle"),
                    AccountKind.valueOf(row.getString("account_kind")),
                    row.getBoolean("locked")),
            handle));
  }

  @Override
  public Role role(RoleId id) {
    Objects.requireNonNull(id);
    return one(
        jdbc.query(
            "SELECT * FROM authority_roles WHERE id=?",
            (row, n) ->
                new Role(
                    id,
                    row.getString("name"),
                    row.getBoolean("assignable"),
                    Optional.ofNullable(row.getObject("intrinsic_account", UUID.class))
                        .map(AccountId::new)),
            id.value()));
  }

  @Override
  public AuthorityScope scope(ScopeId id) {
    Objects.requireNonNull(id);
    return one(jdbc.query(SCOPES + "WHERE s.id=?", (row, n) -> read(row), id.value()));
  }

  @Override
  public AuthorityScope project(String name) {
    Home.of(name);
    return one(
        jdbc.query(
            SCOPES + "JOIN projects p ON p.id=s.project_id WHERE p.name=?",
            (row, n) -> read(row),
            name));
  }

  /**
   * One read of the project row the scope already points at. The name is not derived from the
   * scope's kind or owner — a personal space's name is an ordinary {@code projects.name} — so a
   * renamed project answers its current name and nothing reconstructs a stale one.
   */
  @Override
  public Home home(AuthorityScope scope) {
    Objects.requireNonNull(scope);
    long project =
        switch (scope) {
          case AuthorityScope.Project value -> value.projectId();
          case AuthorityScope.Personal value -> value.projectId();
          case AuthorityScope.AccountKnowledge ignored -> 0L;
          case AuthorityScope.Administration ignored -> 0L;
        };
    if (project == 0L) throw absent();
    return Home.of(
        one(jdbc.queryForList("SELECT name FROM projects WHERE id=?", String.class, project)));
  }

  @Override
  public AuthorityScope.Personal personal(AccountId account) {
    Objects.requireNonNull(account);
    return (AuthorityScope.Personal)
        one(
            jdbc.query(
                SCOPES + "WHERE s.kind='PERSONAL' AND s.account_id=?",
                (row, n) -> read(row),
                account.value()));
  }

  @Override
  public AuthorityScope.AccountKnowledge knowledge(AccountId account) {
    Objects.requireNonNull(account);
    return (AuthorityScope.AccountKnowledge)
        one(
            jdbc.query(
                SCOPES + "WHERE s.kind='ACCOUNT_KNOWLEDGE' AND s.account_id=?",
                (row, n) -> read(row),
                account.value()));
  }

  private static AuthorityScope read(ResultSet row) throws SQLException {
    ScopeId id = new ScopeId(row.getObject("id", UUID.class));
    String kind = row.getString("kind");
    String ownerKind = row.getString("owner_kind");
    if ("PERSONAL".equals(kind) && !"USER".equals(ownerKind)) throw absent();
    if ("ACCOUNT_KNOWLEDGE".equals(kind)
        && !("USER".equals(ownerKind)
            || "SERVICE".equals(ownerKind)
            || "GLOBAL_KNOWLEDGE".equals(row.getString("builtin")))) throw absent();
    return switch (kind) {
      case "PROJECT" -> new AuthorityScope.Project(id, row.getLong("project_id"));
      case "PERSONAL" ->
          new AuthorityScope.Personal(
              id,
              new AccountId(row.getObject("account_id", UUID.class)),
              row.getLong("project_id"));
      case "ACCOUNT_KNOWLEDGE" ->
          new AuthorityScope.AccountKnowledge(
              id, new AccountId(row.getObject("account_id", UUID.class)));
      case "ADMINISTRATION" ->
          new AuthorityScope.Administration(
              id, new AccountId(row.getObject("account_id", UUID.class)));
      default -> throw absent();
    };
  }

  private static <T> T one(List<T> values) {
    if (values.size() != 1) throw absent();
    return values.getFirst();
  }

  private static CallerFault absent() {
    return new CallerFault("Identity or scope mapping is unavailable or restricted");
  }

  @Override
  public void verifyBuiltIns() {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT authority_builtin_registry_valid()", Boolean.class)))
      throw new IllegalStateException(
          "Reserved identity registry is missing or inconsistent; restore the migrated records");
  }
}
