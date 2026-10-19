package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.protocol.ApplicationIdentity.Binding;
import io.aeyer.plowshare.server.applications.ApplicationIdentityRepository;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Normalized consent persistence. Source declarations, consent, receipts and audit join the owning
 * transaction. Reads do not cache account state, eligibility, grant ceilings or revocation.
 */
public final class JdbcActingRepository implements ActingRepository {
  private final JdbcTemplate jdbc;
  private final ApplicationIdentityRepository identities;
  private final BooleanSupplier enabled;

  public JdbcActingRepository(
      JdbcTemplate jdbc, ApplicationIdentityRepository identities, BooleanSupplier enabled) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.identities = Objects.requireNonNull(identities);
    this.enabled = Objects.requireNonNull(enabled);
  }

  @Override
  public boolean enabled() {
    return enabled.getAsBoolean();
  }

  @Override
  public void lock() {
    transaction();
    // Same global lock and ordering as service credentials and Application deployment activation.
    jdbc.execute("SELECT pg_advisory_xact_lock(734921865)");
  }

  @Override
  public boolean accountActive(String account) {
    ActingValues.actor(account);
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT account_active(?)", Boolean.class, account));
  }

  @Override
  public boolean subjectActive(String account) {
    if (io.aeyer.plowshare.server.access.BuiltInIdentities.reservedAccount(account)) return false;
    ActingValues.actor(account);
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM admins WHERE handle=? AND enabled AND NOT bootstrap AND account_kind='USER')",
            Boolean.class,
            account));
  }

  @Override
  public boolean administrator(String account) {
    ActingValues.account(account);
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM admins WHERE handle=? AND enabled AND NOT bootstrap AND account_kind='USER' AND server_admin)",
            Boolean.class,
            account));
  }

  @Override
  public Optional<Binding> application(String application) {
    return identities.find(ActingValues.application(application), false);
  }

  @Override
  public Optional<Grant> grant(String application, String name) {
    ActingValues.application(application);
    ActingValues.account(name);
    return jdbc
        .query(
            """
        SELECT g.* FROM acting_grants g
        JOIN application_identities i ON i.project_name=g.application AND i.state='ACTIVE'
        JOIN application_deployment_heads h ON h.project_id=i.project_id AND h.revision=g.revision
        WHERE g.application=? AND g.name=? AND g.declared
        """,
            (row, n) ->
                new Grant(
                    row.getString("application"),
                    row.getString("name"),
                    ProjectRole.valueOf(row.getString("ceiling")),
                    row.getBoolean("personal"),
                    row.getBoolean("acting_token") ? Set.of(ProofKind.ACTING_TOKEN) : Set.of()),
            application,
            name)
        .stream()
        .findFirst();
  }

  @Override
  public boolean eligible(String application, String name, String subject) {
    ActingValues.application(application);
    ActingValues.account(name);
    ActingValues.account(subject);
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM acting_eligible_accounts
            WHERE application=? AND grant_name=? AND account=?)
        OR EXISTS(SELECT 1 FROM acting_eligible_projects e
            JOIN project_members m ON m.project_id=e.project_id AND m.handle=?
            WHERE e.application=? AND e.grant_name=?)
        """,
            Boolean.class,
            application,
            name,
            subject,
            subject,
            application,
            name));
  }

  @Override
  public void activate(String application, UUID revision, List<Grant> grants, String actor) {
    transaction();
    ActingValues.application(application);
    ActingValues.actor(actor);
    Objects.requireNonNull(revision);
    grants = List.copyOf(grants);
    if (grants.size() > 64) throw new IllegalArgumentException("Too many acting grants");
    var names = new HashSet<String>();
    for (Grant grant : grants) {
      if (!grant.application().equals(application) || !names.add(grant.name()))
        throw new IllegalArgumentException("Invalid Application grant binding");
    }
    // Legacy non-owned sources have no acting declarations and need no retained grant rows.
    if (application(application).isEmpty() && grants.isEmpty()) return;
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM application_identities i
            JOIN application_deployment_heads h ON h.project_id=i.project_id
            WHERE i.project_name=? AND i.state='ACTIVE' AND h.revision=?)
        """,
            Boolean.class,
            application,
            revision))) throw refused();
    jdbc.update("UPDATE acting_grants SET declared=FALSE WHERE application=?", application);
    for (Grant grant : grants) {
      jdbc.update(
          """
          INSERT INTO acting_grants(application,name,revision,declared,ceiling,personal,acting_token)
          VALUES (?,?,?,TRUE,?,?,?) ON CONFLICT(application,name) DO UPDATE SET
          revision=EXCLUDED.revision,declared=TRUE,ceiling=EXCLUDED.ceiling,
          personal=EXCLUDED.personal,acting_token=EXCLUDED.acting_token
          """,
          application,
          grant.name(),
          revision,
          grant.ceiling().name(),
          grant.personal(),
          grant.proofs().contains(ProofKind.ACTING_TOKEN));
    }
    jdbc.update(
        "INSERT INTO acting_audit(actor,action,application,revision,occurred_at) VALUES (?,'ACTIVATE',?,?,now())",
        actor,
        application,
        revision);
  }

  @Override
  public void eligibility(ActingTokens.Eligibility request) {
    transaction();
    Objects.requireNonNull(request);
    if (grant(request.application(), request.grant()).isEmpty()) throw refused();
    jdbc.update(
        "DELETE FROM acting_eligible_accounts WHERE application=? AND grant_name=?",
        request.application(),
        request.grant());
    jdbc.update(
        "DELETE FROM acting_eligible_projects WHERE application=? AND grant_name=?",
        request.application(),
        request.grant());
    for (String account : request.accounts()) {
      if (!subjectActive(account)) throw refused();
      jdbc.update(
          "INSERT INTO acting_eligible_accounts(application,grant_name,account) VALUES (?,?,?)",
          request.application(),
          request.grant(),
          account);
    }
    for (String project : request.memberProjects()) {
      int changed =
          jdbc.update(
              "INSERT INTO acting_eligible_projects(application,grant_name,project_id) SELECT ?,?,id FROM projects WHERE name=? AND personal_owner IS NULL",
              request.application(),
              request.grant(),
              project);
      if (changed != 1) throw refused();
    }
  }

  @Override
  public void insert(
      Token token, String digest, Optional<String> externalSubject, Instant createdAt) {
    transaction();
    Objects.requireNonNull(token);
    Objects.requireNonNull(createdAt);
    Objects.requireNonNull(externalSubject);
    if (digest == null
        || !digest.matches("[0-9a-f]{64}")
        || token.revoked()
        || !token.expiresAt().isAfter(createdAt)
        || token.scope().projects().isEmpty() && !token.scope().personal())
      throw new IllegalArgumentException("Invalid acting token state");
    externalSubject.ifPresent(
        value -> {
          if (value.isBlank()
              || value.length() > 512
              || !value.equals(value.strip())
              || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid external subject");
        });
    var binding = application(token.application()).orElseThrow(JdbcActingRepository::refused);
    var grant =
        grant(token.application(), token.grant()).orElseThrow(JdbcActingRepository::refused);
    if (!binding.principal().equals(token.actor())
        || !subjectActive(token.subject())
        || !grant.ceiling().allows(token.scope().ceiling())
        || token.scope().personal() && !grant.personal()
        || !grant.proofs().contains(ProofKind.ACTING_TOKEN)) throw refused();
    jdbc.update(
        """
        INSERT INTO acting_tokens(id,application,grant_name,actor,subject,ceiling,personal,digest,external_subject,created_at,expires_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """,
        token.id(),
        token.application(),
        token.grant(),
        token.actor(),
        token.subject(),
        token.scope().ceiling().name(),
        token.scope().personal(),
        digest,
        externalSubject.orElse(null),
        java.sql.Timestamp.from(createdAt),
        java.sql.Timestamp.from(token.expiresAt()));
    for (String project : token.scope().projects()) {
      if (jdbc.update(
              "INSERT INTO acting_token_projects(token_id,project_id) SELECT ?,id FROM projects WHERE name=? AND personal_owner IS NULL",
              token.id(),
              project)
          != 1) throw refused();
    }
  }

  @Override
  public void contextAudit(ContextAudit audit) {
    Objects.requireNonNull(audit);
    jdbc.update(
        "INSERT INTO acting_context_audit(actor,application,grant_name,action,context_id,subject) VALUES (?,?,?,?,?,?)",
        audit.actor(),
        audit.application(),
        audit.grant(),
        audit.action().name(),
        audit.context().orElse(null),
        audit.subject().orElse(null));
  }

  @Override
  public Optional<Token> proof(String digest) {
    if (digest == null || !digest.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid proof digest");
    return jdbc
        .queryForList("SELECT id FROM acting_tokens WHERE digest=?", UUID.class, digest)
        .stream()
        .findFirst()
        .flatMap(this::token);
  }

  @Override
  public Optional<Token> token(UUID id) {
    Objects.requireNonNull(id);
    return jdbc
        .query(
            "SELECT * FROM acting_tokens WHERE id=?",
            (row, n) ->
                new Token(
                    id,
                    row.getString("application"),
                    row.getString("actor"),
                    row.getString("grant_name"),
                    row.getString("subject"),
                    new ActingScope(
                        ProjectRole.valueOf(row.getString("ceiling")),
                        Set.copyOf(
                            jdbc.queryForList(
                                "SELECT p.name FROM acting_token_projects s JOIN projects p ON p.id=s.project_id WHERE s.token_id=?",
                                String.class,
                                id)),
                        row.getBoolean("personal")),
                    row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                    row.getObject("revoked_at") != null),
            id)
        .stream()
        .findFirst();
  }

  @Override
  public List<Token> tokens(String subject) {
    ActingValues.account(subject);
    return jdbc
        .queryForList(
            "SELECT id FROM acting_tokens WHERE subject=? ORDER BY created_at DESC,id LIMIT 256",
            UUID.class,
            subject)
        .stream()
        .map(id -> token(id).orElseThrow())
        .toList();
  }

  @Override
  public void revoke(UUID token, Instant revokedAt) {
    transaction();
    Objects.requireNonNull(token);
    Objects.requireNonNull(revokedAt);
    if (jdbc.update(
            "UPDATE acting_tokens SET revoked_at=COALESCE(revoked_at,?) WHERE id=?",
            java.sql.Timestamp.from(revokedAt),
            token)
        != 1) throw refused();
  }

  @Override
  public Optional<Submission> receipt(UUID requestId) {
    Objects.requireNonNull(requestId);
    return jdbc
        .query(
            "SELECT * FROM acting_mutation_receipts WHERE request_id=?",
            (row, n) ->
                new Submission(
                    row.getString("actor"),
                    row.getString("fingerprint"),
                    new ActingTokens.Receipt(
                        requestId,
                        ActingTokens.Action.valueOf(row.getString("action")),
                        row.getString("application"),
                        row.getString("grant_name"),
                        Optional.ofNullable(row.getObject("token_id", UUID.class)),
                        row.getObject("completed_at", OffsetDateTime.class).toInstant())),
            requestId)
        .stream()
        .findFirst();
  }

  @Override
  public void complete(Submission submission) {
    transaction();
    Objects.requireNonNull(submission);
    var receipt = submission.receipt();
    if (receipt.token().isPresent()) {
      var token = token(receipt.token().get()).orElseThrow(JdbcActingRepository::refused);
      if (!token.application().equals(receipt.application())
          || !token.grant().equals(receipt.grant())) throw refused();
    }
    jdbc.update(
        """
        INSERT INTO acting_mutation_receipts(request_id,actor,fingerprint,action,application,grant_name,token_id,completed_at)
        VALUES (?,?,?,?,?,?,?,?)
        """,
        receipt.requestId(),
        submission.actor(),
        submission.fingerprint(),
        receipt.action().name(),
        receipt.application(),
        receipt.grant(),
        receipt.token().orElse(null),
        java.sql.Timestamp.from(receipt.completedAt()));
    jdbc.update(
        "INSERT INTO acting_audit(actor,action,application,grant_name,token_id,request_id,occurred_at) VALUES (?,?,?,?,?,?,?)",
        submission.actor(),
        receipt.action().name(),
        receipt.application(),
        receipt.grant(),
        receipt.token().orElse(null),
        receipt.requestId(),
        java.sql.Timestamp.from(receipt.completedAt()));
  }

  private static void transaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Acting mutations require an owning transaction");
  }

  private static CallerFault refused() {
    return new CallerFault("Acting persistence state is unavailable");
  }
}
