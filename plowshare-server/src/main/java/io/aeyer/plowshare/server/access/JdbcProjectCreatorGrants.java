package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Specialist store for {@code PROJECT_CREATOR} grants (V262).
 *
 * <p>Neither {@code JdbcScopedGrantRepository} path fits this role: no grant covers the
 * PROJECT_CREATION scope (an administrator's {@code ADMIN_CONTROL} grant is
 * ORDINARY_PROJECT_CONTROL at ADMINISTRATION) and nobody holds MANAGE_ACCESS there. So issue and
 * revoke are authorized here by the actor's own live {@code ADMIN_CONTROL} grant, and the V262
 * trigger branch re-checks the same facts on insert.
 *
 * <p>Each mutation is one transaction (joining the caller's, such as account administration's) that
 * takes locks in a fixed order: the PROJECT_CREATION scope row, which serializes every creator
 * mutation, then the lineage of the actor's {@code ADMIN_CONTROL} grant, so a concurrent demotion
 * cannot commit between the authority check and the write. The grant mutation and audit triggers
 * record the actor and their {@code ADMIN_CONTROL} grant on every row written here.
 */
public final class JdbcProjectCreatorGrants implements ProjectCreatorGrants {
  /** The text {@code AdminStore.requireServerAdmin} uses for the same refusal. */
  static final String NOT_ADMIN = "Only a server administrator may manage server resources";

  private static final UUID CREATION = BuiltInIdentities.PROJECT_CREATION.value();

  /**
   * The actor's live {@code ADMIN_CONTROL} grant at ADMINISTRATION, held by an active,
   * non-bootstrap USER server administrator with no agent: the authority the V262 trigger requires.
   */
  private static final String ADMIN_CONTROL =
      "SELECT g.id,g.subject_account FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id"
          + " JOIN authority_roles r ON r.id=g.role_id"
          + " JOIN authority_accounts a ON a.id=g.subject_account"
          + " JOIN admins u ON u.handle=a.legacy_handle"
          + " WHERE a.legacy_handle=? AND g.provenance='ADMIN_CONTROL' AND r.name='ADMIN'"
          + " AND g.scope_id=? AND g.subject_agent IS NULL AND u.account_kind='USER'"
          + " AND u.server_admin AND NOT u.bootstrap AND account_active(u.handle)";

  /** The actor's {@code ADMIN_CONTROL} grant and the actor's own authority account. */
  private record Authority(UUID grant, UUID account) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcProjectCreatorGrants(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
    transactions =
        new TransactionTemplate(
            new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
  }

  @Override
  public boolean holds(String handle) {
    if (handle == null || handle.isBlank()) return false;
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT authority_may_create_projects(?)", Boolean.class, handle));
  }

  /**
   * One statement. The cheap unrevoked-creator-row filters select candidates and the SQL function
   * decides each, so the answer is {@code authority_may_create_projects} itself while the
   * live-grant walk runs only for accounts that hold a creator row at all.
   */
  @Override
  public Set<String> holdersAmong(Collection<String> handles) {
    var selected =
        Objects.requireNonNull(handles).stream()
            .filter(handle -> handle != null && !handle.isBlank())
            .distinct()
            .toArray();
    if (selected.length == 0) return Set.of();
    return Set.copyOf(
        jdbc.queryForList(
            "SELECT a.legacy_handle FROM authority_grants g"
                + " JOIN authority_accounts a ON a.id=g.subject_account"
                + " WHERE g.scope_id=? AND g.revoked_at IS NULL AND a.legacy_handle=ANY(?)"
                + " AND authority_may_create_projects(a.legacy_handle)",
            String.class,
            CREATION,
            new SqlArrayValue("text", selected)));
  }

  @Override
  public boolean grant(String actor, String handle) {
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              Authority authority = authorize(actor);
              UUID subject = recipient(handle);
              boolean self = subject.equals(authority.account());
              Optional<Existing> existing = unrevoked(subject);
              if (existing.isPresent() && holds(handle)) {
                // A live grant is kept unless it is a bound self-grant and another administrator
                // now grants the role: their grant is durable, so it replaces the bound one rather
                // than being shadowed by a grant that ends with the holder's own admin control.
                if (self || !existing.get().bound()) return false;
              }
              // Otherwise an unrevoked grant is dead although the recipient is active, USER and
              // non-bootstrap: it expired, or (a self-grant) its ADMIN_CONTROL lineage was revoked.
              // Either way it occupies the unique slot and is revoked first.
              existing.ifPresent(replaced -> revokeRow(replaced.id(), authority));
              // A self-grant is bound: it hangs off the actor's ADMIN_CONTROL grant and ends with
              // it, because the database refuses exercised self-issuance. Any other recipient's
              // grant is SYSTEM-rooted and records the exercised ADMIN_CONTROL grant, so it
              // outlives the actor.
              jdbc.update(
                  "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,"
                      + "issuer_account,issuing_grant,exercised_grant,provenance)"
                      + " VALUES (?,?,?,'PROJECT_CREATOR',?,?,?,'ROLE')",
                  subject,
                  CREATION,
                  BuiltInIdentities.PROJECT_CREATOR.value(),
                  authority.account(),
                  self ? authority.grant() : root(),
                  self ? null : authority.grant());
              return true;
            }));
  }

  @Override
  public boolean revoke(String actor, String handle) {
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              Authority authority = authorize(actor);
              if (handle == null || handle.isBlank()) return false;
              var existing =
                  jdbc
                      .queryForList(
                          "SELECT id FROM authority_accounts WHERE legacy_handle=?",
                          UUID.class,
                          handle)
                      .stream()
                      .findFirst()
                      .flatMap(this::unrevoked);
              if (existing.isEmpty()) return false;
              // A dead (expired or lineage-dead) row is cleaned up, but the account was not a
              // holder, so nothing changed for it.
              boolean held = holds(handle);
              revokeRow(existing.get().id(), authority);
              return held;
            }));
  }

  /**
   * Locks the creation scope, then finds and lineage-locks the actor's {@code ADMIN_CONTROL} grant.
   * The grant is read again after the lock: a demotion that committed while this transaction waited
   * revoked it, and the second read (a new statement under READ COMMITTED) no longer finds it.
   */
  private Authority authorize(String actor) {
    jdbc.queryForObject(
        "SELECT id FROM authority_scopes WHERE id=? FOR UPDATE", UUID.class, CREATION);
    if (actor == null || actor.isBlank()) throw new CallerFault(NOT_ADMIN);
    Authority candidate = control(actor).orElseThrow(() -> new CallerFault(NOT_ADMIN));
    jdbc.queryForObject("SELECT authority_lock_lineage(?)", Object.class, candidate.grant());
    return control(actor).filter(candidate::equals).orElseThrow(() -> new CallerFault(NOT_ADMIN));
  }

  private Optional<Authority> control(String actor) {
    List<Authority> grants =
        jdbc.query(
            ADMIN_CONTROL,
            (row, n) -> new Authority(row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
            actor,
            BuiltInIdentities.ADMINISTRATION.value());
    // authority_refresh_admin keeps at most one unrevoked ADMIN_CONTROL grant per scope.
    return grants.stream().findFirst();
  }

  /** The authority account of an existing, active, non-bootstrap USER account. */
  private UUID recipient(String handle) {
    if (handle != null && !handle.isBlank())
      for (UUID id :
          jdbc.queryForList(
              "SELECT a.id FROM admins u JOIN authority_accounts a ON a.legacy_handle=u.handle"
                  + " WHERE u.handle=? AND u.account_kind='USER' AND NOT u.bootstrap"
                  + " AND account_active(u.handle)",
              UUID.class,
              handle)) return id;
    throw new CallerFault("The project creator role can only be granted to an active user account");
  }

  /** An unrevoked creator row; {@code bound} for a self-grant (no exercised grant). */
  private record Existing(UUID id, boolean bound) {}

  /** The subject's unrevoked creator grant, live or dead; the unique index allows one. */
  private Optional<Existing> unrevoked(UUID subject) {
    return jdbc
        .query(
            "SELECT id,exercised_grant IS NULL FROM authority_grants"
                + " WHERE scope_id=? AND subject_account=? AND revoked_at IS NULL FOR UPDATE",
            (row, n) -> new Existing(row.getObject(1, UUID.class), row.getBoolean(2)),
            CREATION,
            subject)
        .stream()
        .findFirst();
  }

  private UUID root() {
    return jdbc.queryForObject(
        "SELECT id FROM authority_grants WHERE provenance='ROOT_POLICY'", UUID.class);
  }

  /** The one versioned revocation {@code authority_grant_mutation} permits, on one creator row. */
  private void revokeRow(UUID grant, Authority authority) {
    if (jdbc.update(
            "UPDATE authority_grants SET revoked_at=now(),version=version+1,revoked_by=?,revoking_grant=?"
                + " WHERE id=? AND scope_id=? AND revoked_at IS NULL",
            authority.account(),
            authority.grant(),
            grant,
            CREATION)
        != 1) throw new IllegalStateException("Creator grant changed under the scope lock");
  }
}
