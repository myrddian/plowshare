package io.aeyer.plowshare.server.testing;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test-only truncation preserves the exact migration-owned builtin registry/root binding.
 * PostgreSQL CASCADE truncates entire referencing tables, even for unrelated project fixtures. This
 * is destructive fixture setup, not production bootstrap, recovery or an application API.
 */
public final class AuthorityFixtures {
  private AuthorityFixtures() {}

  /** The SQL is a code-owned fixture statement. No external value may reach this method. */
  public static void truncate(JdbcTemplate jdbc, String statement) {
    if (!statement.startsWith("TRUNCATE ") || !statement.endsWith(" CASCADE"))
      throw new IllegalArgumentException("Expected a fixture truncation");
    new TransactionTemplate(
            new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())))
        .executeWithoutResult(
            status -> {
              jdbc.execute(
                  "CREATE TEMP TABLE fixture_builtin_admins ON COMMIT DROP AS SELECT * FROM admins WHERE account_kind='BUILTIN'");
              jdbc.execute(
                  "CREATE TEMP TABLE fixture_builtin_accounts ON COMMIT DROP AS SELECT * FROM authority_accounts WHERE builtin IS NOT NULL");
              jdbc.execute(
                  "CREATE TEMP TABLE fixture_builtin_scopes ON COMMIT DROP AS SELECT * FROM authority_scopes WHERE builtin IS NOT NULL");
              jdbc.execute(
                  "CREATE TEMP TABLE fixture_builtin_roles ON COMMIT DROP AS SELECT * FROM authority_roles");
              jdbc.execute(
                  "CREATE TEMP TABLE fixture_role_actions ON COMMIT DROP AS SELECT * FROM authority_role_actions");
              jdbc.execute(
                  "CREATE TEMP TABLE fixture_root_policy ON COMMIT DROP AS SELECT * FROM authority_grants WHERE provenance='ROOT_POLICY'");
              jdbc.execute(statement);
              // Restore the original rows without source triggers manufacturing replacement UUIDs.
              jdbc.execute("ALTER TABLE admins DISABLE TRIGGER authority_map_account");
              jdbc.execute("ALTER TABLE admins DISABLE TRIGGER authority_z_admin_changed");
              jdbc.execute(
                  "INSERT INTO admins SELECT * FROM fixture_builtin_admins ON CONFLICT DO NOTHING");
              jdbc.execute("ALTER TABLE admins ENABLE TRIGGER authority_map_account");
              jdbc.execute("ALTER TABLE admins ENABLE TRIGGER authority_z_admin_changed");
              jdbc.execute(
                  "INSERT INTO authority_accounts SELECT * FROM fixture_builtin_accounts ON CONFLICT DO NOTHING");
              jdbc.execute(
                  "INSERT INTO authority_roles SELECT * FROM fixture_builtin_roles ON CONFLICT DO NOTHING");
              jdbc.execute(
                  "INSERT INTO authority_role_actions SELECT * FROM fixture_role_actions ON CONFLICT DO NOTHING");
              jdbc.execute(
                  "INSERT INTO authority_scopes SELECT * FROM fixture_builtin_scopes ON CONFLICT DO NOTHING");
              jdbc.execute("ALTER TABLE authority_grants DISABLE TRIGGER authority_grant_insert");
              jdbc.execute(
                  "INSERT INTO authority_grants SELECT * FROM fixture_root_policy ON CONFLICT DO NOTHING");
              jdbc.execute("ALTER TABLE authority_grants ENABLE TRIGGER authority_grant_insert");
            });
  }
}
