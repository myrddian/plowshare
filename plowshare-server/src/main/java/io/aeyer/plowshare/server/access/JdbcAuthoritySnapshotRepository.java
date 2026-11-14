package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.AuthoritySnapshot.Account;
import io.aeyer.plowshare.server.access.AuthoritySnapshot.Facts;
import io.aeyer.plowshare.server.access.AuthoritySnapshot.Grant;
import io.aeyer.plowshare.server.access.AuthoritySnapshot.Knowledge;
import io.aeyer.plowshare.server.access.AuthoritySnapshot.Member;
import io.aeyer.plowshare.server.access.AuthoritySnapshot.Project;
import io.aeyer.plowshare.server.access.AuthoritySnapshot.Role;
import io.aeyer.plowshare.server.access.AuthoritySnapshot.RoleAction;
import io.aeyer.plowshare.server.access.AuthoritySnapshot.Scope;
import io.aeyer.plowshare.server.access.AuthoritySnapshot.ServiceToken;
import io.aeyer.plowshare.server.access.AuthoritySnapshot.TokenScope;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Reads the authority tables into typed facts. Every table is bounded by {@code limit}: one row
 * past the bound returns empty, so an oversized installation keeps using SQL authority rather than
 * a partial snapshot. Digests and other credential material are never selected.
 */
public final class JdbcAuthoritySnapshotRepository implements AuthoritySnapshotRepository {
  private final JdbcTemplate jdbc;
  private final int limit;

  public JdbcAuthoritySnapshotRepository(JdbcTemplate jdbc, int limit) {
    this.jdbc = Objects.requireNonNull(jdbc);
    if (limit < 1 || limit > 1_000_000)
      throw new IllegalArgumentException("Authority snapshot row limit must be 1..1000000");
    this.limit = limit;
  }

  @Override
  public Optional<Facts> load() {
    // The first statement's now() is the evaluation instant. The caller's version token rejects
    // the snapshot if any authority table changed after its capture, including between these reads.
    OffsetDateTime at = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
    var accounts =
        rows(
            "SELECT handle,account_kind,enabled,bootstrap,server_admin FROM admins ORDER BY handle",
            (row, n) ->
                new Account(
                    row.getString(1),
                    row.getString(2),
                    row.getBoolean(3),
                    row.getBoolean(4),
                    row.getBoolean(5)));
    var authorityAccounts =
        rows(
            "SELECT id,legacy_handle FROM authority_accounts ORDER BY id",
            (row, n) -> Map.entry(row.getObject(1, UUID.class), row.getString(2)));
    var roles =
        rows(
            "SELECT id,name,intrinsic_account,assignable FROM authority_roles ORDER BY id",
            (row, n) ->
                new Role(
                    row.getObject(1, UUID.class),
                    row.getString(2),
                    Optional.ofNullable(row.getObject(3, UUID.class)),
                    row.getBoolean(4)));
    var scopes =
        rows(
            "SELECT id,kind,account_id,project_id,builtin FROM authority_scopes ORDER BY id",
            (row, n) ->
                new Scope(
                    row.getObject(1, UUID.class),
                    row.getString(2),
                    Optional.ofNullable(row.getObject(3, UUID.class)),
                    Optional.ofNullable(row.getObject(4, Long.class)),
                    Optional.ofNullable(row.getString(5))));
    var projects =
        rows(
            "SELECT id,name,personal_owner FROM projects ORDER BY id",
            (row, n) ->
                new Project(
                    row.getLong(1), row.getString(2), Optional.ofNullable(row.getString(3))));
    var tokens =
        rows(
            "SELECT id,principal_handle,owner_handle,credential_issued,revoked_at,expires_at"
                + " FROM service_tokens ORDER BY id",
            (row, n) ->
                new ServiceToken(
                    row.getObject(1, UUID.class),
                    row.getString(2),
                    row.getString(3),
                    row.getBoolean(4),
                    Optional.ofNullable(row.getObject(5, OffsetDateTime.class)),
                    row.getObject(6, OffsetDateTime.class)));
    var tokenScopes =
        rows(
            "SELECT token_id,project_id,role FROM service_token_scopes ORDER BY token_id,project_id",
            (row, n) ->
                new TokenScope(row.getObject(1, UUID.class), row.getLong(2), row.getString(3)));
    var members =
        rows(
            "SELECT project_id,handle,role FROM project_members ORDER BY project_id,handle",
            (row, n) -> new Member(row.getLong(1), row.getString(2), row.getString(3)));
    var grants =
        rows(
            "SELECT * FROM authority_grant_records ORDER BY id",
            (row, n) ->
                new Grant(
                    JdbcScopedGrantRepository.read(row), row.getObject("role_id", UUID.class)));
    var roleActions =
        rows(
            "SELECT role_id,role_version,profile,action FROM authority_role_actions"
                + " ORDER BY role_id,role_version,profile,action",
            (row, n) ->
                new RoleAction(
                    row.getObject(1, UUID.class),
                    row.getInt(2),
                    row.getString(3),
                    row.getString(4)));
    // The knowledge-read columns: builtin accounts, scope containers and contained principals.
    var builtinAccounts =
        rows(
            "SELECT id FROM authority_accounts WHERE builtin IS NOT NULL ORDER BY id",
            (row, n) -> row.getObject(1, UUID.class));
    var containers =
        rows(
            "SELECT id,container FROM authority_scopes WHERE container IS NOT NULL ORDER BY id",
            (row, n) -> Map.entry(row.getObject(1, UUID.class), row.getObject(2, UUID.class)));
    var principals =
        rows(
            "SELECT id,scope_id FROM contained_principals ORDER BY id",
            (row, n) -> Map.entry(row.getObject(1, UUID.class), row.getObject(2, UUID.class)));
    if (Objects.isNull(at)
        || builtinAccounts.isEmpty()
        || containers.isEmpty()
        || principals.isEmpty()
        || accounts.isEmpty()
        || authorityAccounts.isEmpty()
        || roles.isEmpty()
        || scopes.isEmpty()
        || projects.isEmpty()
        || tokens.isEmpty()
        || tokenScopes.isEmpty()
        || members.isEmpty()
        || grants.isEmpty()
        || roleActions.isEmpty()) return Optional.empty();
    var handles = new HashMap<UUID, String>();
    authorityAccounts.get().forEach(entry -> handles.put(entry.getKey(), entry.getValue()));
    return Optional.of(
        new Facts(
            at.toInstant(),
            accounts.get(),
            handles,
            roles.get(),
            scopes.get(),
            projects.get(),
            tokens.get(),
            tokenScopes.get(),
            members.get(),
            grants.get(),
            roleActions.get(),
            new Knowledge(
                Set.copyOf(builtinAccounts.get()),
                entries(containers.get()),
                entries(principals.get()))));
  }

  private static Map<UUID, UUID> entries(List<Map.Entry<UUID, UUID>> rows) {
    var map = new HashMap<UUID, UUID>();
    rows.forEach(entry -> map.put(entry.getKey(), entry.getValue()));
    return map;
  }

  /** Empty when the table has more than {@code limit} rows. */
  private <T> Optional<List<T>> rows(String sql, RowMapper<T> mapper) {
    var rows = jdbc.query(sql + " LIMIT ?", mapper, limit + 1);
    return rows.size() > limit ? Optional.empty() : Optional.of(rows);
  }
}
